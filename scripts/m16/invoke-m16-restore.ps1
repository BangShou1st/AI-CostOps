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
    .\scripts\m16\invoke-m16-restore.ps1 -Suffix restore1 -RawKey ... -OrgId 25 -GatewayEnvKeys ...
#>
[CmdletBinding()]
param(
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13307,
    [string]$Database = "m16accept",
    [string]$MysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$MysqldumpBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe",
    [string]$GatewayBase = "http://127.0.0.1:18081",
    [Parameter(Mandatory)][string]$Suffix,
    [Parameter(Mandatory)][string]$RawKey,
    [Parameter(Mandatory)][long]$OrgId,
    [Parameter(Mandatory)][string]$GatewayEnvKeys,
    [string]$ModelKey = "m16-accept-chat"
)

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
$sourceCounts = @{}
foreach ($t in $lineageTables) {
    $sourceCounts[$t] = [long](Invoke-Root -Sql ("SELECT COUNT(*) FROM " + $t + ";"))
}
Write-Output ("[M16-E04] source lineage tables captured (" + $lineageTables.Count + " tables)")

# Backup via mysqldump (single transaction, routines/triggers included).
# NOTE: dump WITHOUT --databases on purpose. With --databases the dump
# contains CREATE DATABASE `m16accept` + USE `m16accept`, and loading it with
# --database=<restoreDb> would still execute that USE and write the data back
# into the SOURCE database instead of the isolated restore database.
# Without --databases the dump is plain table DDL+DML applied to the
# connection-selected database (--database=$restoreDb).
$stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmss")
$restoreDb = "m16restore_" + $stamp
# Dump path must be visible to both PowerShell and cmd.exe: use a temp file
# under the repo (removed afterwards), never a Git-Bash-only /tmp path.
$dumpFile = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) (".m16dump-" + $stamp + ".tmp")
$env:MYSQL_PWD = $rootPassword
try {
    & $MysqldumpBin -h $MysqlHost -P $MysqlPort -u root --single-transaction --routines --triggers $Database --result-file=$dumpFile 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "mysqldump failed" }
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}
$dumpSize = (Get-Item $dumpFile).Length
Write-Output ("[M16-E04] dump size=" + $dumpSize + " bytes")

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

# Empty-Redis Gateway against the restored DB.
# NOTE: the JDBC URL is pre-assembled into $restoreUrl. An inline ("..."+$x+"...")
# group expression on a backtick-continued docker line mis-parses and eats the
# image reference (docker: invalid reference format); a plain "...=$var" argument
# is robust regardless of continuation.
$restoreUrl = "jdbc:mysql://m16-mysql-accept:3306/" + $restoreDb + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
docker exec m16-redis-accept redis-cli -a 'm16-redis-accept-pass' -n 1 FLUSHDB | Out-Null
docker rm -f m16-gateway-restore | Out-Null
docker run -d --name m16-gateway-restore --network m16-accept-net -p 127.0.0.1:18082:8081 `
  -e "SPRING_DATASOURCE_URL=$restoreUrl" `
  -e SPRING_DATASOURCE_USERNAME=gw_m16 -e SPRING_DATASOURCE_PASSWORD='GwM16-Runtime-2026-Accept' `
  -e SPRING_DATA_REDIS_HOST=m16-redis-accept -e SPRING_DATA_REDIS_PORT=6379 -e SPRING_DATA_REDIS_PASSWORD='m16-redis-accept-pass' `
  -e SPRING_DATA_REDIS_DATABASE=1 `
  -e AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1="$GatewayEnvKeys" -e AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1="$GatewayEnvKeys" -e AICOSTOPS_PROVIDER_KEK_V1="$GatewayEnvKeys" `
  -e AICOSTOPS_GATEWAY_RATE_LIMIT_CAPACITY=10000 -e AICOSTOPS_GATEWAY_RATE_LIMIT_REFILL_PER_SECOND=1000 `
  -e AICOSTOPS_GATEWAY_QUOTA_REQUESTS_PER_DAY=100000 `
  ai-costops-gateway:m16 | Out-Null
Start-Sleep 22
$ready = Invoke-WebRequest -Uri "http://127.0.0.1:18082/actuator/health/readiness" -SkipHttpErrorCheck -TimeoutSec 30
Write-Output ("[M16-E04] restored-gateway readiness: HTTP " + $ready.StatusCode)
if ($ready.StatusCode -ne 200) { Add-Failure "E04: restored gateway not ready." }

# Read a restored request through the status API surface.
$restoredReqId = Invoke-Root -Sql ("SELECT public_request_id FROM gateway_request WHERE org_id=" + $OrgId + " ORDER BY id DESC LIMIT 1;") -Db $restoreDb
$headers = @{ Authorization = "Bearer $RawKey" }
try {
    $status = Invoke-WebRequest -Uri ("http://127.0.0.1:18082/v1/gateway/requests/" + $restoredReqId) -Headers $headers -SkipHttpErrorCheck -TimeoutSec 30
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
    $new = Invoke-WebRequest -Uri "http://127.0.0.1:18082/v1/chat/completions" -Method Post -Headers $nheaders -Body $nbody -SkipHttpErrorCheck -TimeoutSec 90
    Write-Output ("[M16-E04] new work on restored DB: HTTP " + $new.StatusCode)
    if ($new.StatusCode -ne 200) { Add-Failure ("E04: new work got " + $new.StatusCode + " on restored DB.") }
} catch {
    Add-Failure ("E04: new work failed: " + $_.Exception.Message)
}

docker rm -f m16-gateway-restore | Out-Null
docker exec m16-redis-accept redis-cli -a 'm16-redis-accept-pass' -n 1 FLUSHDB | Out-Null
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
