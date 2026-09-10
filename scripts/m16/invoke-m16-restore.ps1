<#
.SYNOPSIS
    M16 E04: V2 backup/restore drill for Gateway + reconciliation lineage.

.DESCRIPTION
    Source: the live M16 acceptance MySQL (m16accept) with V1..V23 schema and
    synthetic Gateway lineage (request/route/usage/reservation + M15
    reconciliation tables).
    Steps:
      1. Snapshot source lineage counts (per-table, one org + global catalogs).
      2. mysqldump the source database (structure + data, single transaction).
      3. Create an isolated restore database on the SAME server (no new
         container; name m16restore_<stamp>) and load the dump.
      4. Point a Gateway at the restored DB with an EMPTY Redis (fresh
         redis database index) and prove: readiness UP, status API reads the
         restored request, new work flows, financial counts match source.
      5. Print M16_RESTORE_PASS with sanitized counts.
    HMAC/KEK recovery is external to the DB backup by design (keys travel via
    environment, asserted by reusing the same env keys for the restored run).

.EXAMPLE
    .\scripts\m16\invoke-m16-restore.ps1 -Suffix restore1 -RawKey ... -OrgId 25
#>
[CmdletBinding()]
param(
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13307,
    [string]$Database = "m16accept",
    [string]$MysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$MysqldumpBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe",
    [string]$GatewayBase = "http://127.0.0.1:18081",
    [string]$MockBase = "http://127.0.0.1:18089",
    [Parameter(Mandatory)][string]$Suffix,
    [Parameter(Mandatory)][string]$RawKey,
    [Parameter(Mandatory)][long]$OrgId,
    [string]$ModelKey = "m16-accept-chat",
    # Acceptance-harness hygiene: runtime credentials travel via environment,
    # never hard-coded. Defaults preserve the historical local values so
    # existing runs keep working.
    [string]$GatewayUser = "gw_m16",
    [string]$RestoreGatewayPort = "18084",
    [string]$RedisDb = "1"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$E04failures = [System.Collections.ArrayList]::new()
function Add-E04Failure([string]$Message) {
    Write-Output ("[M16-E04-RED] " + $Message)
    $E04failures.Add($Message) | Out-Null
}
function Get-E04Fingerprint([string]$Val) {
    if ([string]::IsNullOrWhiteSpace($Val)) { return "MISSING" }
    $bytes = [Text.Encoding]::UTF8.GetBytes($Val)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return (-join ($hash[0..3] | ForEach-Object { $_.ToString("x2") }))
}
# Key-separation contract: three independent secrets via process-env inheritance.
$e04Cred = [Environment]::GetEnvironmentVariable("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1")
$e04Req = [Environment]::GetEnvironmentVariable("AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1")
$e04Kek = [Environment]::GetEnvironmentVariable("AICOSTOPS_PROVIDER_KEK_V1")
if ([string]::IsNullOrWhiteSpace($e04Cred)) { Add-E04Failure "E04 setup: AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1 not set." }
if ([string]::IsNullOrWhiteSpace($e04Req)) { Add-E04Failure "E04 setup: AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1 not set." }
if ([string]::IsNullOrWhiteSpace($e04Kek)) { Add-E04Failure "E04 setup: AICOSTOPS_PROVIDER_KEK_V1 not set." }
if ($E04failures.Count -gt 0) { Write-Output ("[M16-E04-RED] M16_E04_FAIL (" + $E04failures.Count + " violation(s))" ); exit 1 }
if (($e04Cred -ceq $e04Req) -or ($e04Cred -ceq $e04Kek) -or ($e04Req -ceq $e04Kek)) {
    Add-E04Failure "E04 setup: three secret roles must remain distinct."
    Write-Output ("[M16-E04-RED] M16_E04_FAIL (" + $E04failures.Count + " violation(s))" )
    exit 1
}
Write-Output ("[M16-E04] secret fp cred=" + (Get-E04Fingerprint $e04Cred) + " req=" + (Get-E04Fingerprint $e04Req) + " kek=" + (Get-E04Fingerprint $e04Kek))
$gatewayPassword = [Environment]::GetEnvironmentVariable("MYSQL_M16_GATEWAY_PASSWORD")
if ([string]::IsNullOrWhiteSpace($gatewayPassword)) { throw "MYSQL_M16_GATEWAY_PASSWORD is not set." }
$redisPassword = [Environment]::GetEnvironmentVariable("M16_REDIS_PASSWORD")
if ([string]::IsNullOrWhiteSpace($redisPassword)) {
    # Historical local default for the isolated acceptance stack.
    $redisPassword = "m16-redis-accept-pass"
}

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$failures = [System.Collections.ArrayList]::new()
function Add-Failure([string]$Message) {
    Write-Output ("[M16-E04-RED] " + $Message)
    $failures.Add($Message) | Out-Null
}
$rootPassword = [Environment]::GetEnvironmentVariable("MYSQL_M16_ROOT_PASSWORD")
if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw "MYSQL_M16_ROOT_PASSWORD is not set." }
function Invoke-Root([string]$Sql, [string]$Db = $Database) {
    $env:MYSQL_PWD = $rootPassword
    try {
        $out = & $MysqlBin -h $MysqlHost -P $MysqlPort -u root -N -B $Db -e $Sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw ("MySQL failed [db=" + $Db + "] [sql=" + $Sql + "]: " + (($out | Out-String)).Trim()) }
        if ($null -eq $out) { return "" }
        return (($out | Out-String)).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

$lineageTables = @("gateway_request", "gateway_route_attempt", "gateway_usage_fact",
    "gateway_usage_dimension", "budget_reservation", "budget", "billing_period",
    "gateway_credential", "routing_policy", "routing_policy_candidate",
    "pricing_version", "pricing_rate", "provider_account", "provider_credential",
    "ledger_posting", "ledger_entry", "gateway_settlement",
    "provider_charge_disposition", "reconciliation_adjustment",
    "gateway_financial_resolution", "reconciliation_evidence")

# Backup via mysqldump (single transaction, routines/triggers included).
# NOTE: dump WITHOUT --databases on purpose. With --databases the dump
# contains CREATE DATABASE `m16accept` + USE `m16accept`, and loading it with
# --database=<restoreDb> would still execute that USE and write the data back
# into the SOURCE database instead of the isolated restore database.
# Without --databases the dump is plain table DDL+DML applied to the
# connection-selected database (--database=$restoreDb).
$stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmss")

# The seed creates governed data but no request. Drive ONE request through the
# live Gateway first so this run's org owns a full lineage
# (request/route/usage/reservation/settlement) BEFORE the dump; otherwise the
# org-scoped status read below would resolve to another org's request (or
# nothing) and the status API would privacy-404 by design.
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
$seedKey = "m16-restore-seed-" + $stamp
$seedHeaders = @{ Authorization = "Bearer $RawKey"; "Idempotency-Key" = $seedKey; "Content-Type" = "application/json" }
$seedBody = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 restore lineage"}]}'
try {
    $seedReq = Invoke-WebRequest -Uri ($GatewayBase + "/v1/chat/completions") -Method Post -Headers $seedHeaders -Body $seedBody -SkipHttpErrorCheck -TimeoutSec 90
    Write-Output ("[M16-E04] seed lineage request: HTTP " + $seedReq.StatusCode)
    if ($seedReq.StatusCode -ne 200) { Add-Failure ("E04 setup: seed request got " + $seedReq.StatusCode + " (want 200).") }
} catch {
    Add-Failure ("E04 setup: seed request failed: " + $_.Exception.Message)
}
# The backend settlement worker settles asynchronously (5s poll). Wait for
# THIS run's request to reach SETTLED before snapshotting; otherwise the
# dump races the worker and the source keeps mutating under the comparison.
$settledOk = $false
for ($i = 0; $i -lt 24; $i++) {
    Start-Sleep 5
    $st = Invoke-Root ("SELECT s.status FROM gateway_settlement s JOIN gateway_request r ON r.id=s.request_id WHERE r.org_id=" + $OrgId + " ORDER BY s.id DESC LIMIT 1;")
    if ($st -eq "SETTLED") { $settledOk = $true; break }
}
Write-Output ("[M16-E04] seed lineage settled=" + $settledOk)
if (-not $settledOk) { Add-Failure "E04 setup: seed request did not reach SETTLED before dump." }
# Snapshot the source counts AFTER convergence: the settlement worker mutates
# the source asynchronously, so counts taken before would race the dump.
$sourceCounts = @{}
$restoreDb = "m16restore_" + $stamp
# Dump path must be visible to both PowerShell and cmd.exe: use a temp file
# under the repo (removed afterwards), never a Git-Bash-only /tmp path.
$dumpFile = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) (".m16dump-" + $stamp + ".tmp")
# NOTE: NEVER --result-file on Windows: mysqldump's --result-file re-encodes
# binary (UNHEX digest) bytes through the console code page and corrupts the
# dump (E04 RED: `Unknown command '\?'` on load). cmd.exe `>` redirection
# writes raw bytes untouched; add --hex-blob so binary columns are ASCII-safe.
$env:MYSQL_PWD = $rootPassword
try {
    $dumpCmd = '"' + $MysqldumpBin + '" -h ' + $MysqlHost + ' -P ' + $MysqlPort + ' -u root --single-transaction --routines --triggers --hex-blob ' + $Database + ' > "' + $dumpFile + '"'
    $dumpOut = & cmd /c $dumpCmd 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw ("mysqldump failed: " + $dumpOut.Trim()) }
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}
$dumpSize = (Get-Item $dumpFile).Length
Write-Output ("[M16-E04] dump size=" + $dumpSize + " bytes")
foreach ($t in $lineageTables) {
    $sourceCounts[$t] = [long](Invoke-Root -Sql ("SELECT COUNT(*) FROM " + $t + ";"))
}
Write-Output ("[M16-E04] source lineage tables captured (" + $lineageTables.Count + " tables)")

# Restore into an isolated database; grant the runtime identity there too.
# The dump is loaded via cmd.exe input redirection (never a PowerShell pipe:
# piping re-encodes the dump through the console code page and corrupts it).
$env:MYSQL_PWD = $rootPassword
try {
    & $MysqlBin -h $MysqlHost -P $MysqlPort -u root -e "CREATE DATABASE ``$restoreDb`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" 2>&1 | Out-Null
    $loadCmd = '"' + $MysqlBin + '" -h ' + $MysqlHost + ' -P ' + $MysqlPort + ' -u root --database=' + $restoreDb + ' < "' + $dumpFile + '"'
    Write-Output ("[M16-E04] load cmd: " + $loadCmd)
    $loadOut = & cmd /c $loadCmd 2>&1 | Out-String
    Write-Output ("[M16-E04] load rc=" + $LASTEXITCODE + " out=" + $loadOut.Trim())
    if ($LASTEXITCODE -ne 0) { throw "restore load failed with exit $LASTEXITCODE" }
    & $MysqlBin -h $MysqlHost -P $MysqlPort -u root -e "GRANT SELECT ON ``$restoreDb``.* TO 'gw_m16'@'%'; GRANT LOCK TABLES ON ``$restoreDb``.* TO 'gw_m16'@'%'; GRANT SELECT, INSERT, UPDATE ON ``$restoreDb``.budget_reservation TO 'gw_m16'@'%'; GRANT SELECT, INSERT, UPDATE ON ``$restoreDb``.gateway_request TO 'gw_m16'@'%'; GRANT SELECT, INSERT, UPDATE ON ``$restoreDb``.gateway_route_attempt TO 'gw_m16'@'%'; GRANT SELECT, INSERT, UPDATE ON ``$restoreDb``.gateway_usage_fact TO 'gw_m16'@'%'; GRANT SELECT, INSERT, UPDATE ON ``$restoreDb``.gateway_usage_dimension TO 'gw_m16'@'%';" 2>&1 | Out-Null
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}
Remove-Item $dumpFile -Force -ErrorAction SilentlyContinue
foreach ($t in $lineageTables) {
    $restored = [long](Invoke-Root -Sql ("SELECT COUNT(*) FROM " + $t + ";") -Db $restoreDb)
    if ($restored -ne $sourceCounts[$t]) {
        Add-Failure ("E04: table " + $t + " restored=" + $restored + " source=" + $sourceCounts[$t])
    }
}
if ($failures.Count -eq 0) { Write-Output "[M16-E04] all lineage table counts match." }

# ---- Financial semantic comparison: source vs restore must preserve the
# actual monetary truth, not just row counts. Every query below selects
# deterministic ordered value rows with TAB separators and LF line endings
# (mysql -N -B output is tab-separated; PowerShell re-joins multi-line output
# with spaces, so normalize before comparing). Auto-increment ids are
# excluded EXCEPT as join keys inside one query (both sides come from the
# same dump, so internal ids are stable); timestamps are never compared.
function Normalize-Rows([string]$Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    $lines = @($Text -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
    return @($lines | Sort-Object)
}
function Compare-Semantic([string]$Label, [string]$Sql) {
    $src = @(Normalize-Rows (Invoke-Root -Sql $Sql -Db $Database))
    $rst = @(Normalize-Rows (Invoke-Root -Sql $Sql -Db $restoreDb))
    $same = ($src.Count -eq $rst.Count)
    if ($same) {
        for ($i = 0; $i -lt $src.Count; $i++) {
            if ($src[$i] -cne $rst[$i]) { $same = $false; break }
        }
    }
    if ($same) {
        Write-Output ("[M16-E04] semantic PASS: " + $Label + " (" + $src.Count + " row(s))")
    } else {
        Add-Failure ("E04 semantic mismatch: " + $Label + " (source=" + $src.Count + " restore=" + $rst.Count + ")")
        $n = [Math]::Max($src.Count, $rst.Count)
        for ($i = 0; $i -lt $n; $i++) {
            $a = if ($i -lt $src.Count) { $src[$i] } else { "<missing>" }
            $b = if ($i -lt $rst.Count) { $rst[$i] } else { "<missing>" }
            if ($a -cne $b) {
                Write-Output ("[M16-E04-RED] row ${i}: source=[" + $a + "] restore=[" + $b + "]")
                if ($i -ge 4) { Write-Output "[M16-E04-RED] ... (truncated)"; break }
            }
        }
    }
}
Compare-Semantic "gateway_settlement" ("SELECT settlement_key,request_id,status,CAST(posted_amount AS CHAR),currency,ledger_posting_id FROM gateway_settlement ORDER BY settlement_key;")
Compare-Semantic "ledger_posting" ("SELECT posting_key,source_type,source_id,status FROM ledger_posting ORDER BY posting_key;")
Compare-Semantic "ledger_entry" ("SELECT posting_id,entry_index,entry_type,CAST(amount AS CHAR),currency FROM ledger_entry ORDER BY posting_id,entry_index;")
Compare-Semantic "budget" ("SELECT org_id,billing_period_id,scope_type,scope_id,CAST(actual_amount AS CHAR),CAST(committed_amount AS CHAR),CAST(total_amount AS CHAR),status,currency FROM budget ORDER BY org_id,billing_period_id,scope_type,scope_id;")
Compare-Semantic "budget_reservation" ("SELECT request_id,route_attempt_id,status,CAST(reserved_amount AS CHAR),currency,finalized_at IS NOT NULL,released_at IS NOT NULL FROM budget_reservation ORDER BY request_id,route_attempt_id;")
Compare-Semantic "reconciliation" ("SELECT 'run',id,status FROM reconciliation_run UNION ALL SELECT 'case',id,status FROM reconciliation_case UNION ALL SELECT 'evidence',id,match_kind FROM reconciliation_evidence ORDER BY 1,2,3;")
# Expected-zero tables in the normal acceptance path (no statement charges
# imported, so no dispositions/adjustments/resolutions exist). Assert BOTH
# sides are zero: the invariant is "nothing fabricated", not "equal counts".
foreach ($zt in @("provider_charge_disposition", "reconciliation_adjustment", "gateway_financial_resolution")) {
    $zsrc = [long](Invoke-Root -Sql ("SELECT COUNT(*) FROM " + $zt + ";") -Db $Database)
    $zrst = [long](Invoke-Root -Sql ("SELECT COUNT(*) FROM " + $zt + ";") -Db $restoreDb)
    if ($zsrc -eq 0 -and $zrst -eq 0) {
        Write-Output ("[M16-E04] semantic PASS: " + $zt + " expected zero (source=0 restore=0)")
    } else {
        Add-Failure ("E04: " + $zt + " expected zero but source=" + $zsrc + " restore=" + $zrst)
    }
}

# Empty-Redis Gateway against the restored DB.
# NOTE: the JDBC URL is pre-assembled into $restoreUrl. An inline ("..."+$x+"...")
# group expression on a backtick-continued docker line mis-parses and eats the
# image reference (docker: invalid reference format); a plain "...=$var" argument
# is robust regardless of continuation.
$restoreUrl = "jdbc:mysql://m16-mysql-accept:3306/" + $restoreDb + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
docker exec m16-redis-accept redis-cli -a "$redisPassword" -n $RedisDb FLUSHDB | Out-Null
docker rm -f m16-gateway-restore | Out-Null
docker run -d --name m16-gateway-restore --network m16-accept-net -p ("127.0.0.1:" + $RestoreGatewayPort + ":8081") `
  -e "SPRING_DATASOURCE_URL=$restoreUrl" `
  -e SPRING_DATASOURCE_USERNAME="$GatewayUser" -e SPRING_DATASOURCE_PASSWORD="$gatewayPassword" `
  -e SPRING_DATA_REDIS_HOST=m16-redis-accept -e SPRING_DATA_REDIS_PORT=6379 -e SPRING_DATA_REDIS_PASSWORD="$redisPassword" `
  -e SPRING_DATA_REDIS_DATABASE=$RedisDb `
  -e AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1="$e04Cred" -e AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1="$e04Req" -e AICOSTOPS_PROVIDER_KEK_V1="$e04Kek" `
  -e AICOSTOPS_GATEWAY_RATE_LIMIT_CAPACITY=10000 -e AICOSTOPS_GATEWAY_RATE_LIMIT_REFILL_PER_SECOND=1000 `
  -e AICOSTOPS_GATEWAY_QUOTA_REQUESTS_PER_DAY=100000 `
  ai-costops-gateway:m16 | Out-Null
Start-Sleep 22
$restoreBase = "http://127.0.0.1:" + $RestoreGatewayPort
$ready = Invoke-WebRequest -Uri ($restoreBase + "/actuator/health/readiness") -SkipHttpErrorCheck -TimeoutSec 30
Write-Output ("[M16-E04] restored-gateway readiness: HTTP " + $ready.StatusCode)
if ($ready.StatusCode -ne 200) { Add-Failure "E04: restored gateway not ready." }

# Read a restored request through the status API surface. The request must
# belong to THIS run's org: the status surface only serves the owning
# credential (cross-credential reads are privacy-preserving 404 by design).
$restoredReqId = Invoke-Root -Sql ("SELECT public_request_id FROM gateway_request WHERE org_id=" + $OrgId + " ORDER BY id DESC LIMIT 1;") -Db $restoreDb
$headers = @{ Authorization = "Bearer $RawKey" }
try {
    $status = Invoke-WebRequest -Uri ($restoreBase + "/v1/gateway/requests/" + $restoredReqId) -Headers $headers -SkipHttpErrorCheck -TimeoutSec 30
    Write-Output ("[M16-E04] restored request status API: HTTP " + $status.StatusCode)
    if ($status.StatusCode -ne 200) { Add-Failure "E04: restored request not readable via Gateway." }
} catch {
    Add-Failure ("E04: status API failed: " + $_.Exception.Message)
}

# New work flows on restored truth with empty Redis.
$newKey = "m16-restore-new-" + $stamp
$nheaders = @{ Authorization = "Bearer $RawKey"; "Idempotency-Key" = $newKey; "Content-Type" = "application/json" }
$nbody = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 restore probe"}]}'
try {
    $new = Invoke-WebRequest -Uri ($restoreBase + "/v1/chat/completions") -Method Post -Headers $nheaders -Body $nbody -SkipHttpErrorCheck -TimeoutSec 90
    Write-Output ("[M16-E04] new work on restored DB: HTTP " + $new.StatusCode)
    if ($new.StatusCode -ne 200) { Add-Failure ("E04: new work got " + $new.StatusCode + " on restored DB.") }
} catch {
    Add-Failure ("E04: new work failed: " + $_.Exception.Message)
}

docker rm -f m16-gateway-restore | Out-Null
docker exec m16-redis-accept redis-cli -a "$redisPassword" -n $RedisDb FLUSHDB | Out-Null
$env:MYSQL_PWD = $rootPassword
try {
    & $MysqlBin -h $MysqlHost -P $MysqlPort -u root -e ("DROP DATABASE " + $restoreDb + ";") 2>&1 | Out-Null
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}
Write-Output "[M16-E04] isolated restore database dropped; source untouched."

if ($failures.Count -gt 0) {
    Write-Output ("[M16-E04-RED] M16_E04_FAIL (" + $failures.Count + " violation(s))")
    exit 1
}
Write-Output "[M16-E04] M16_E04_PASS (V2 lineage restored; empty-Redis Gateway converges)"
exit 0
