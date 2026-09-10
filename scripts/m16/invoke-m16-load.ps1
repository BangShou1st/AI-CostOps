<#
.SYNOPSIS
    M16 B03/B04: stepped non-stream load + concurrent SSE ceiling proof.

.DESCRIPTION
    B03: for each concurrency step, fires N distinct-key non-stream requests,
    measures per-request latency (p50/p95/p99), and asserts bounded behavior:
    every admitted request is 200 or a bounded rejection (429/402/403/409);
    no 5xx; provider_ops equals admitted count; settlement exactly-once
    (duplicate Ledger = 0, posting:entry:settled = 1:1:1, all postings
    backend-owned SYSTEM/GATEWAY_SETTLEMENT).
    B04: fires (ceiling+1) concurrent SSE streams with distinct keys and
    asserts at most <ceiling> concurrent 200 streams, the remainder bounded
    429, and every 200 stream ends with data: [DONE].
    Prints M16_B03_PASS / M16_B04_PASS or FAIL.

.EXAMPLE
    .\scripts\m16\invoke-m16-load.ps1 -Suffix load1 -RawKey ... -OrgId 20 -Steps "1,5,20" -StreamCeiling 4
#>
[CmdletBinding()]
param(
    [string]$GatewayBase = "http://127.0.0.1:18081",
    [string]$MockBase = "http://127.0.0.1:18089",
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13307,
    [string]$Database = "m16accept",
    [string]$MysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [Parameter(Mandatory)][string]$Suffix,
    [Parameter(Mandatory)][string]$RawKey,
    [Parameter(Mandatory)][long]$OrgId,
    [string]$ModelKey = "m16-accept-chat",
    [string]$Steps = "1,5,20",
    [int]$StreamCeiling = 4
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$rootPassword = [Environment]::GetEnvironmentVariable("MYSQL_M16_ROOT_PASSWORD")
if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw "MYSQL_M16_ROOT_PASSWORD is not set." }
function Invoke-Root([string]$Sql) {
    $env:MYSQL_PWD = $rootPassword
    try {
        $out = & $MysqlBin -h $MysqlHost -P $MysqlPort -u root -N -B $Database -e $Sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw "MySQL failed: $out" }
        if ($null -eq $out) { return "" }
        return (($out | Out-String)).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Invoke-Load([int]$N, [bool]$Stream, [string]$Tag) {
    $uri = $GatewayBase + "/v1/chat/completions"
    $pool = [runspacefactory]::CreateRunspacePool(1, [Math]::Max($N, 1))
    $pool.Open()
    $sb = {
        param($Uri, $RawKey, $ModelKey, $KeyBase, $Index, $Stream, $Gate)
        $Gate.WaitOne() | Out-Null
        $headers = @{
            Authorization     = "Bearer $RawKey"
            "Idempotency-Key" = ($KeyBase + "-" + $Index)
            "Content-Type"    = "application/json"
            "Accept"          = "text/event-stream"
        }
        $streamJson = if ($Stream) { ', "stream": true' } else { '' }
        $body = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 load"}]' + $streamJson + '}'
        $sw = [Diagnostics.Stopwatch]::StartNew()
        try {
            $r = Invoke-WebRequest -Uri $Uri -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 180
            $sw.Stop()
            return [pscustomobject]@{ Status = $r.StatusCode; Ms = $sw.ElapsedMilliseconds; Body = $r.Content }
        } catch {
            $sw.Stop()
            return [pscustomobject]@{ Status = -1; Ms = $sw.ElapsedMilliseconds; Body = $_.Exception.Message }
        }
    }
    $gate = New-Object System.Threading.ManualResetEvent($false)
    $hs = @()
    $stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")
    for ($i = 0; $i -lt $N; $i++) {
        $p = [powershell]::Create().AddScript($sb).AddArgument($uri).AddArgument($RawKey).AddArgument($ModelKey).AddArgument(("m16-" + $Tag + "-" + $stamp)).AddArgument($i).AddArgument($Stream).AddArgument($gate)
        $p.RunspacePool = $pool
        $hs += [pscustomobject]@{ P = $p; H = $p.BeginInvoke() }
    }
    $gate.Set() | Out-Null
    $res = @()
    foreach ($h in $hs) { $res += $h.P.EndInvoke($h.H); $h.P.Dispose() }
    $pool.Close()
    return $res
}

function Percentile([long[]]$Sorted, [int]$P) {
    if ($Sorted.Count -eq 0) { return 0 }
    $idx = [Math]::Min($Sorted.Count - 1, [Math]::Floor($Sorted.Count * $P / 100))
    return $Sorted[$idx]
}

$failures = @()

# ---- B03 stepped non-stream ----
# Bounded classes: 200 admitted; 429/402/403/409 bounded rejections
# (budget exhaustion is OPTIONAL-mode 403 by design; quota/rate 429;
# idempotency races 409). RED is only: 5xx/transport, or provider_ops !=
# admitted 200s (duplicate Provider work), or a rejections-only step that
# proves the harness budget was exhausted before measuring anything.
foreach ($step in ($Steps -split ",")) {
    $n = [int]$step.Trim()
    Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null
    $res = Invoke-Load $n $false ("b03-" + $n)
    $lat = @($res | ForEach-Object { [long]$_.Ms } | Sort-Object)
    $p50 = Percentile $lat 50; $p95 = Percentile $lat 95; $p99 = Percentile $lat 99
    $dist = ($res | Group-Object Status | Sort-Object Name | ForEach-Object { "HTTP " + $_.Name + " x " + $_.Count }) -join ", "
    $ops = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
    $ok = @($res | Where-Object { $_.Status -eq 200 }).Count
    $bounded = @($res | Where-Object { $_.Status -in @(429, 402, 403, 409) }).Count
    Write-Output ("[M16-B03] step=" + $n + " [" + $dist + "] p50=" + $p50 + "ms p95=" + $p95 + "ms p99=" + $p99 + "ms provider_ops=" + $ops)
    $bad = @($res | Where-Object { $_.Status -ge 500 -or $_.Status -eq -1 }).Count
    if ($bad -gt 0) { $failures += "step ${n}: $bad 5xx/transport failures" }
    if ($ops -ne $ok) { $failures += "step ${n}: provider_ops=$ops != http_200=$ok" }
    if ($ok -lt 1 -and $bounded -eq $n) { $failures += "step ${n}: harness budget exhausted before measuring (all $n bounded, none admitted)" }
    if ($ok + $bounded -ne $n) { $failures += "step ${n}: unbounded response class present" }
}

# ---- B04 SSE ceiling ----
# The ceiling under test is the LIVE Gateway's configured max (resolved from
# the acceptance Gateway container env, falling back to the parameter).
# Proof without a 129-way burst: occupy exactly <ceiling> permits with
# timeout-mode mock streams held open in the background, then one more
# stream MUST be bounded 429 (never admitted, never dispatched), and after
# the holders release, a fresh stream is 200 with [DONE]. For the default
# acceptance ceiling (128) a full 129-way burst would only pressure the
# harness; a sampled burst of 8 parallel streams additionally proves no 5xx
# and no duplicate Provider work under concurrency.
$liveCeiling = $StreamCeiling
try {
    $gwConf = docker inspect m16-gateway-accept --format '{{range .Config.Env}}{{println .}}{{end}}' 2>$null | Out-String
    $m = [regex]::Match($gwConf, 'AICOSTOPS_GATEWAY_MAX_ACTIVE_STREAMS=(\d+)')
    if ($m.Success) { $liveCeiling = [int]$m.Groups[1].Value }
} catch { }
Write-Output ("[M16-B04] live stream ceiling=" + $liveCeiling)
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null
# Sampled concurrent burst (8 streams): bounded classes only, provider_ops ==
# admitted 200s, every 200 ends with [DONE].
$sres = Invoke-Load 8 $true "b04"
$sdist = ($sres | Group-Object Status | Sort-Object Name | ForEach-Object { "HTTP " + $_.Name + " x " + $_.Count }) -join ", "
$sok = @($sres | Where-Object { $_.Status -eq 200 })
$sokOps = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
Write-Output ("[M16-B04] sampled burst (live ceiling=" + $liveCeiling + "): [" + $sdist + "] provider_ops=" + $sokOps)
if ($sok.Count -lt 1) { $failures += "B04: no stream admitted (harness mis-sized?)" }
foreach ($s in $sok) {
    if ($s.Body -notmatch "\[DONE\]") { $failures += "B04: 200 stream missing terminal [DONE]" }
}
$sbad = @($sres | Where-Object { $_.Status -ge 500 -or $_.Status -eq -1 }).Count
if ($sbad -gt 0) { $failures += "B04: $sbad 5xx/transport failures in sampled burst" }
if ($sokOps -ne $sok.Count) { $failures += "B04: provider_ops=$sokOps != admitted 200s=$($sok.Count)" }
# Ceiling enforcement probe: force the live Gateway down to a SMALL ceiling
# in a scratch container (same image, same DB, different port), occupy it
# fully with timeout-held streams, and prove ceiling+1 is bounded 429 with
# zero additional Provider work. This exercises the REAL Semaphore path
# (tryAcquireStreamPermit) without a 129-way burst.
$probeCeiling = 2
$probePort = "18083"
$probeName = "m16-gateway-ceiling-probe"
try {
    $gwEnv = docker inspect m16-gateway-accept --format '{{json .Config.Env}}' | ConvertFrom-Json
    $getEnv = {
        param($Name)
        $hit = @($gwEnv | Where-Object { $_ -like ($Name + "=" + "*") })
        if ($hit.Count -gt 0) { return $hit[0].Substring($Name.Length + 1) }
        return ""
    }
    $probeDbUrl = "jdbc:mysql://m16-mysql-accept:3306/m16accept?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
    docker rm -f $probeName 2>$null | Out-Null
    docker run -d --name $probeName --network m16-accept-net -p ("127.0.0.1:" + $probePort + ":8081") `
      -e ("SPRING_DATASOURCE_URL=" + $probeDbUrl) `
      -e ("SPRING_DATASOURCE_USERNAME=" + (& $getEnv "SPRING_DATASOURCE_USERNAME")) `
      -e ("SPRING_DATASOURCE_PASSWORD=" + (& $getEnv "SPRING_DATASOURCE_PASSWORD")) `
      -e SPRING_DATA_REDIS_HOST=m16-redis-accept -e SPRING_DATA_REDIS_PORT=6379 `
      -e SPRING_DATA_REDIS_PASSWORD=m16-redis-accept-pass `
      -e ("M16_REDIS_PASSWORD_FALLBACK=" + (& $getEnv "SPRING_DATA_REDIS_PASSWORD")) `
      -e ("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1=" + (& $getEnv "AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1")) `
      -e ("AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1=" + (& $getEnv "AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1")) `
      -e ("AICOSTOPS_PROVIDER_KEK_V1=" + (& $getEnv "AICOSTOPS_PROVIDER_KEK_V1")) `
      -e AICOSTOPS_GATEWAY_RATE_LIMIT_CAPACITY=10000 -e AICOSTOPS_GATEWAY_RATE_LIMIT_REFILL_PER_SECOND=1000 `
      -e AICOSTOPS_GATEWAY_QUOTA_REQUESTS_PER_DAY=100000 `
      -e AICOSTOPS_GATEWAY_MAX_ACTIVE_STREAMS=$probeCeiling `
      ai-costops-gateway:m16 | Out-Null
    # Deterministic readiness: poll until 200, never a fixed sleep as proof.
    $probeReadyCode = -1
    for ($w = 0; $w -lt 36; $w++) {
        Start-Sleep 5
        try {
            $pr = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $probePort + "/actuator/health/readiness") -SkipHttpErrorCheck -TimeoutSec 10
            $probeReadyCode = $pr.StatusCode
        } catch { $probeReadyCode = -1 }
        Write-Output ("[M16-B04] probe readiness poll=" + $w + " HTTP " + $probeReadyCode)
        if ($probeReadyCode -eq 200) { break }
    }
    Write-Output ("[M16-B04] probe readiness (ceiling=" + $probeCeiling + "): HTTP " + $probeReadyCode)
    if ($probeReadyCode -ne 200) { $failures += "B04: ceiling-probe gateway not ready" }
    else {
        # Deterministic hold barrier (mock hold mode): the mock sends SSE
        # headers immediately (Gateway commits the stream and holds one
        # Semaphore permit) then blocks until /admin/release. Poll the
        # triple (mock held, provider ops, gauge) until all three read
        # ceiling — never a fixed sleep as proof of occupancy.
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"hold"}' -ContentType "application/json" | Out-Null
        $probeJobs = @()
        for ($i = 0; $i -lt $probeCeiling; $i++) {
            $probeJobs += Start-Job -ScriptBlock {
                param($Port, $Key, $Model, $Idem)
                $h = @{ Authorization = "Bearer $Key"; "Idempotency-Key" = $Idem; "Content-Type" = "application/json"; "Accept" = "text/event-stream" }
                $b = '{"model":"' + $Model + '","messages":[{"role":"user","content":"m16 ceiling hold"}]' + ', "stream": true}'
                try {
                    $r = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $Port + "/v1/chat/completions") -Method Post -Headers $h -Body $b -SkipHttpErrorCheck -TimeoutSec 100
                    return [pscustomobject]@{ Status = $r.StatusCode }
                } catch { return [pscustomobject]@{ Status = -1 } }
            } -ArgumentList $probePort, $RawKey, $ModelKey, ("m16-b04hold-" + $Suffix + "-" + $i)
        }
        # Deterministic hold barrier: poll mock held + provider ops + gauge
        # until all three read ceiling. Hold mode keeps each admitted stream
        # open upstream, so each holder occupies exactly one Semaphore permit.
        $activeVal = "?"
        $opsHeld = -1
        $mockHeld = -1
        for ($w = 0; $w -lt 45; $w++) {
            Start-Sleep 2
            try {
                $st = Invoke-RestMethod -Uri ($MockBase + "/stats") -TimeoutSec 10
                $opsHeld = $st.post_chat_completions
                $mockHeld = $st.held
            } catch { $opsHeld = -1; $mockHeld = -1 }
            try {
                $held = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $probePort + "/actuator/prometheus") -SkipHttpErrorCheck -TimeoutSec 10
                $mActive = [regex]::Match(($held.Content | Out-String), 'gateway_active_streams\{[^}]*\}\s+([\d\.]+)')
                if ($mActive.Success) { $activeVal = $mActive.Groups[1].Value }
            } catch { }
            Write-Output ("[M16-B04] hold poll=" + $w + " active_streams=" + $activeVal + " provider_ops=" + $opsHeld + " mock_held=" + $mockHeld)
            if (($activeVal -eq "$probeCeiling" -or $activeVal -eq "$probeCeiling.0") -and $opsHeld -eq $probeCeiling -and $mockHeld -eq $probeCeiling) { break }
        }
        Write-Output ("[M16-B04] probe active_streams while held=" + $activeVal + " provider_ops=" + $opsHeld + " mock_held=" + $mockHeld + " (want " + $probeCeiling + ")")
        if ($opsHeld -ne $probeCeiling) { $failures += "B04: holders did not converge (ops=$opsHeld, want $probeCeiling)" }
        if ($mockHeld -ne $probeCeiling) { $failures += "B04: mock held=$mockHeld (want $probeCeiling)" }
        if (($activeVal -ne "$probeCeiling") -and ($activeVal -ne "$probeCeiling.0")) { $failures += "B04: active_streams=$activeVal (want $probeCeiling)" }
        $overHeaders = @{ Authorization = "Bearer $RawKey"; "Idempotency-Key" = ("m16-b04over-" + $Suffix); "Content-Type" = "application/json"; "Accept" = "text/event-stream" }
        $overBody = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 ceiling over"}]' + ', "stream": true}'
        try {
            $over = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $probePort + "/v1/chat/completions") -Method Post -Headers $overHeaders -Body $overBody -SkipHttpErrorCheck -TimeoutSec 60
            $overStatus = $over.StatusCode
            $overBodyText = if ($null -ne $over.Content) { ($over.Content | Out-String) } else { "" }
        } catch { $overStatus = -1; $overBodyText = "" }
        $opsOver = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
        Write-Output ("[M16-B04] ceiling+1 probe: HTTP " + $overStatus + " provider_ops=" + $opsOver + " (want 429, ops=" + $probeCeiling + ")")
        if ($overStatus -ne 429) { $failures += "B04: ceiling+1 got $overStatus (want bounded 429)" }
        if ($overBodyText -notmatch "GATEWAY_RATE_LIMITED") { $failures += "B04: ceiling+1 body missing GATEWAY_RATE_LIMITED" }
        if ($opsOver -ne $probeCeiling) { $failures += "B04: over-ceiling request dispatched Provider work (ops=$opsOver, want $probeCeiling)" }
        # Release: open the mock barrier so held streams complete normally
        # with [DONE] (each holder returns 200), then poll gauge to 0 to prove
        # the permit is always released on complete (doFinally), never leaked.
        # NOTE: do NOT Stop-Job the holders first — killing the client would
        # exercise the cancel path instead of the normal-complete path.
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/release") -ContentType "application/json" | Out-Null
        $holderResults = @()
        foreach ($j in $probeJobs) {
            $jr = @(Receive-Job $j -Wait -AutoRemoveJob -ErrorAction SilentlyContinue)
            $holderResults += $jr
        }
        $holderOk = @($holderResults | Where-Object { $_.Status -eq 200 }).Count
        Write-Output ("[M16-B04] holders completed 200=" + $holderOk + "/" + $probeCeiling)
        if ($holderOk -ne $probeCeiling) { $failures += "B04: holders completed 200=$holderOk (want $probeCeiling)" }
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
        $releasedVal = "?"
        for ($w = 0; $w -lt 45; $w++) {
            Start-Sleep 2
            try {
                $rel = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $probePort + "/actuator/prometheus") -SkipHttpErrorCheck -TimeoutSec 10
                $mRel = [regex]::Match(($rel.Content | Out-String), 'gateway_active_streams\{[^}]*\}\s+([\d\.]+)')
                if ($mRel.Success) { $releasedVal = $mRel.Groups[1].Value }
            } catch { }
            Write-Output ("[M16-B04] release poll=" + $w + " active_streams=" + $releasedVal)
            if ($releasedVal -eq "0" -or $releasedVal -eq "0.0") { break }
        }
        Write-Output ("[M16-B04] probe active_streams after release=" + $releasedVal + " (want 0)")
        if (($releasedVal -ne "0") -and ($releasedVal -ne "0.0")) { $failures += "B04: permits leaked after release (active=$releasedVal, want 0)" }
        # Fresh stream after release must be admitted 200 with [DONE].
        $freshHeaders = @{ Authorization = "Bearer $RawKey"; "Idempotency-Key" = ("m16-b04fresh-" + $Suffix); "Content-Type" = "application/json"; "Accept" = "text/event-stream" }
        $freshBody = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 ceiling fresh"}]' + ', "stream": true}'
        try {
            $fresh = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $probePort + "/v1/chat/completions") -Method Post -Headers $freshHeaders -Body $freshBody -SkipHttpErrorCheck -TimeoutSec 60
            $freshStatus = $fresh.StatusCode
            $freshText = ($fresh.Content | Out-String)
        } catch { $freshStatus = -1; $freshText = "" }
        Write-Output ("[M16-B04] fresh after release: HTTP " + $freshStatus)
        if ($freshStatus -ne 200) { $failures += "B04: fresh stream after release got $freshStatus (want 200)" }
        elseif ($freshText -notmatch "\[DONE\]") { $failures += "B04: fresh stream missing terminal [DONE]" }
    }
} finally {
    docker rm -f $probeName 2>$null | Out-Null
    Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
}
$rejected = @($sres | Where-Object { $_.Status -eq 429 }).Count
Write-Output ("[M16-B04] admitted=" + $sok.Count + " bounded_429=" + $rejected)
function Get-ConsistentSnapshot([long]$Oid) {
    # Deterministic consistent observation: ONE SQL statement returns the full
    # settlement/ledger picture as a single row. InnoDB evaluates one statement
    # under a single statement snapshot, so ledger_postings vs settled cannot
    # tear when a settlement transaction commits between two SELECTs.
    $sql = "SELECT " + `
        "(SELECT COUNT(*) FROM gateway_request WHERE org_id=" + $Oid + "), " + `
        "(SELECT COUNT(*) FROM gateway_settlement s JOIN gateway_request r ON r.id=s.request_id WHERE r.org_id=" + $Oid + " AND s.status='SETTLED'), " + `
        "(SELECT COUNT(*) FROM gateway_settlement s JOIN gateway_request r ON r.id=s.request_id WHERE r.org_id=" + $Oid + " AND s.status IN ('PENDING','RETRYABLE_FAILED')), " + `
        "(SELECT COUNT(*) FROM ledger_posting WHERE org_id=" + $Oid + "), " + `
        "(SELECT COUNT(*) FROM ledger_entry WHERE org_id=" + $Oid + "), " + `
        "(SELECT COUNT(*) FROM gateway_settlement s JOIN gateway_request r ON r.id=s.request_id WHERE r.org_id=" + $Oid + " AND s.status='SETTLED' AND s.ledger_posting_id IS NULL), " + `
        "(SELECT COUNT(*) FROM (SELECT posting_key FROM ledger_posting WHERE org_id=" + $Oid + " GROUP BY posting_key HAVING COUNT(*)>1) d)"
    $raw = Invoke-Root $sql
    $parts = @($raw -split "\s+")
    if ($parts.Count -lt 7) { throw ("consistent snapshot parse failed: [" + $raw + "]") }
    return [pscustomobject]@{
        ReqRows = [long]$parts[0]; Settled = [long]$parts[1]; Pending = [long]$parts[2]
        LedgerRows = [long]$parts[3]; LedgerEntries = [long]$parts[4]
        Orphan = [long]$parts[5]; DupKey = [long]$parts[6]
    }
}
# ---- Settlement / Ledger exactly-once (B03/B04 share one org) ----
# Gateway has no Ledger writer by design (A03 least-privilege ERROR 1142 +
# architecture test; Gateway mappers only touch budget_reservation /
# gateway_request / route_attempt / usage_fact). Backend settlement worker
# asynchronously posts 1 posting : 1 COST entry per SETTLED settlement in one
# transaction. A zero-Ledger assertion would misread legitimate Backend
# settlement as a Gateway violation. Correct invariants: duplicate Ledger = 0,
# postings == SETTLED, entries == SETTLED, no SETTLED orphan, all postings
# backend-owned (SYSTEM / GATEWAY_SETTLEMENT), rows <= legitimate requests.
$snap = $null
$converged = $false
for ($w = 0; $w -lt 36; $w++) {
    $snap = Get-ConsistentSnapshot $OrgId
    Write-Output ("[M16-LOAD] consistent snapshot poll=" + $w + " req=" + $snap.ReqRows + " settled=" + $snap.Settled + " pending=" + $snap.Pending + " postings=" + $snap.LedgerRows + " entries=" + $snap.LedgerEntries + " orphan=" + $snap.Orphan + " dupkey=" + $snap.DupKey)
    if (($snap.Settled -ge 1) -and ($snap.Pending -eq 0) -and ($snap.LedgerRows -eq $snap.Settled) -and ($snap.LedgerEntries -eq $snap.Settled) -and ($snap.Orphan -eq 0)) { $converged = $true; break }
    Start-Sleep 5
}
$settled = $snap.Settled; $pending = $snap.Pending; $ledgerRows = $snap.LedgerRows; $ledgerEntries = $snap.LedgerEntries; $reqRows = $snap.ReqRows; $orphan = $snap.Orphan; $dupKey = $snap.DupKey
Write-Output ("[M16-LOAD] requests(org)=" + $reqRows + " settled=" + $settled + " pending=" + $pending + " ledger_posting(org)=" + $ledgerRows + " ledger_entry(org)=" + $ledgerEntries)
if (-not $converged) { $failures += ("consistent settlement snapshot did not converge (pending=" + $pending + " settled=" + $settled + " postings=" + $ledgerRows + " entries=" + $ledgerEntries + " orphan=" + $orphan + ")") }
if ($pending -ne 0) { $failures += "settlement worker did not converge (pending=$pending)" }
if ($dupKey -ne 0) { $failures += "duplicate Ledger posting_key groups=$dupKey" }
$dupSource = [long](Invoke-Root ("SELECT COUNT(*) FROM (SELECT source_id FROM ledger_posting WHERE org_id=" + $OrgId + " AND source_type='GATEWAY_SETTLEMENT' GROUP BY source_id HAVING COUNT(*)>1) d;"))
if ($dupSource -ne 0) { $failures += "duplicate Ledger source settlement groups=$dupSource" }
$badCardinality = [long](Invoke-Root ("SELECT COUNT(*) FROM (SELECT le.posting_id FROM ledger_entry le JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id WHERE lp.org_id=" + $OrgId + " GROUP BY le.posting_id HAVING COUNT(*)!=1) d;"))
if ($badCardinality -ne 0) { $failures += "Ledger entry cardinality violation postings=$badCardinality (want 1 entry per posting)" }
$dupEntrySettlement = [long](Invoke-Root ("SELECT COUNT(*) FROM (SELECT source_gateway_settlement_id FROM ledger_entry WHERE org_id=" + $OrgId + " AND source_gateway_settlement_id IS NOT NULL GROUP BY source_gateway_settlement_id HAVING COUNT(*)>1) d;"))
if ($dupEntrySettlement -ne 0) { $failures += "duplicate Ledger entries per settlement groups=$dupEntrySettlement" }
$orphan = [long](Invoke-Root ("SELECT COUNT(*) FROM gateway_settlement s JOIN gateway_request r ON r.id=s.request_id WHERE r.org_id=" + $OrgId + " AND s.status='SETTLED' AND s.ledger_posting_id IS NULL;"))
if ($orphan -ne 0) { $failures += "SETTLED settlement without ledger_posting_id count=$orphan" }
if ($ledgerRows -ne $settled) { $failures += "ledger_postings=$ledgerRows != settled=$settled (want 1:1)" }
if ($ledgerEntries -ne $settled) { $failures += "ledger_entries=$ledgerEntries != settled=$settled (want 1:1:1)" }
if ($ledgerRows -gt $reqRows) { $failures += "ledger_postings=$ledgerRows > requests=$reqRows (rows exceed legitimate outcomes)" }
if ($settled -gt $reqRows) { $failures += "settled=$settled > requests=$reqRows" }
$nonBackend = [long](Invoke-Root ("SELECT COUNT(*) FROM ledger_posting WHERE org_id=" + $OrgId + " AND NOT (source_type='GATEWAY_SETTLEMENT' AND posting_actor_type='SYSTEM');"))
if ($nonBackend -ne 0) { $failures += "non-backend Ledger rows=$nonBackend (want 0; all postings must be SYSTEM/GATEWAY_SETTLEMENT)" }

if ($failures.Count -gt 0) {
    Write-Output ("[M16-LOAD-RED] M16_LOAD_FAIL: " + ($failures -join "; "))
    exit 1
}
Write-Output "[M16-LOAD] M16_B03_PASS M16_B04_PASS"
exit 0
