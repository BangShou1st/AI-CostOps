<#
.SYNOPSIS
    M16 B05: deterministic overload via production rate-limiter bound.

.DESCRIPTION
    Proves bounded safe rejection on a REAL production bounded resource
    (Redis token-bucket rate limiter), NOT the stream ceiling (B04 owns that).
    Uses a scratch gateway on the SAME image (ai-costops-gateway:m16), SAME DB,
    SAME redis, SAME mock upstream, different host port, with a SMALL
    deterministic rate-limit capacity.

    Frozen contract:
      capacity requests -> admitted 200 (resource occupied)
      capacity+1        -> bounded 429 GATEWAY_RATE_LIMITED, zero new dispatch
      occupancy never exceeds capacity
      refill/release converges, fresh work 200
      no duplicate dispatch, no budget overspend, rejected ops = 0
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
    [Parameter(Mandatory)][long]$CredentialId,
    [string]$ModelKey = "m16-accept-chat",
    [int]$Capacity = 5,
    [string]$RefillPerSecond = "0.1"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$rootPassword = [Environment]::GetEnvironmentVariable("MYSQL_M16_ROOT_PASSWORD")
if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw "MYSQL_M16_ROOT_PASSWORD is not set." }
$redisPassword = [Environment]::GetEnvironmentVariable("M16_REDIS_PASSWORD")
if ([string]::IsNullOrWhiteSpace($redisPassword)) { $redisPassword = "m16-redis-accept-pass" }

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

function Send-ProbeChat([string]$Base, [string]$KeySuffix) {
    $headers = @{
        Authorization = "Bearer $RawKey"
        "Idempotency-Key" = $KeySuffix
        "Content-Type" = "application/json"
    }
    $body = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 b05 overload"}]}'
    try {
        $r = Invoke-WebRequest -Uri ($Base + "/v1/chat/completions") -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 60
        $txt = if ($null -ne $r.Content) { ($r.Content | Out-String) } else { "" }
        return [pscustomobject]@{ Status = $r.StatusCode; Body = $txt }
    } catch {
        return [pscustomobject]@{ Status = -1; Body = $_.Exception.Message }
    }
}

function Redis-Get([string]$Key) {
    $v = docker exec m16-redis-accept redis-cli -a $redisPassword GET $Key 2>$null | Out-String
    if ($null -eq $v) { return "" }
    return $v.Trim()
}

$failures = @()
$probePort = "18084"
$probeName = "m16-gateway-b05-probe"
$probeBase = "http://127.0.0.1:" + $probePort
$stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")

try {
    Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
    Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null

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
      -e ("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1=" + (& $getEnv "AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1")) `
      -e ("AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1=" + (& $getEnv "AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1")) `
      -e ("AICOSTOPS_PROVIDER_KEK_V1=" + (& $getEnv "AICOSTOPS_PROVIDER_KEK_V1")) `
      -e ("AICOSTOPS_GATEWAY_RATE_LIMIT_CAPACITY=" + $Capacity) -e ("AICOSTOPS_GATEWAY_RATE_LIMIT_REFILL_PER_SECOND=" + $RefillPerSecond) `
      -e AICOSTOPS_GATEWAY_QUOTA_REQUESTS_PER_DAY=100000 `
      -e AICOSTOPS_GATEWAY_MAX_ACTIVE_STREAMS=128 `
      ai-costops-gateway:m16 | Out-Null

    $readyCode = -1
    for ($w = 0; $w -lt 36; $w++) {
        Start-Sleep 5
        try {
            $pr = Invoke-WebRequest -Uri ($probeBase + "/actuator/health/readiness") -SkipHttpErrorCheck -TimeoutSec 10
            $readyCode = $pr.StatusCode
        } catch { $readyCode = -1 }
        Write-Output ("[M16-B05] probe readiness poll=" + $w + " HTTP " + $readyCode + " capacity=" + $Capacity + " refill=" + $RefillPerSecond)
        if ($readyCode -eq 200) { break }
    }
    Write-Output ("[M16-B05] probe readiness: HTTP " + $readyCode)
    if ($readyCode -ne 200) { $failures += "B05: scratch probe not ready"; throw "probe not ready" }

    # Deterministically consume exactly <capacity>: sequential non-stream
    # requests with distinct keys. Sequential (not burst) removes scheduling
    # flake; slow refill (0.2/s) keeps the bucket from refilling mid-fill.
    $admitted = 0
    $admittedStatuses = @()
    for ($i = 0; $i -lt $Capacity; $i++) {
        $rr = Send-ProbeChat $probeBase ("m16-b05fill-" + $Suffix + "-" + $stamp + "-" + $i)
        $admittedStatuses += $rr.Status
        Write-Output ("[M16-B05] fill[$i] HTTP " + $rr.Status)
        if ($rr.Status -eq 200) { $admitted++ }
    }
    $opsHeld = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
    $tokensKey = "aicostops:v2:gateway:ratelimit:" + $CredentialId + ":tokens"
    $tokensVal = Redis-Get $tokensKey
    Write-Output ("[M16-B05] occupied: admitted=" + $admitted + "/" + $Capacity + " provider_ops=" + $opsHeld + " bucket_tokens=" + $tokensVal)
    if ($admitted -ne $Capacity) { $failures += "B05: fill admitted=$admitted want exactly capacity=$Capacity (statuses: $($admittedStatuses -join ','))" }
    if ($opsHeld -ne $Capacity) { $failures += "B05: provider_ops=$opsHeld want exactly capacity=$Capacity (duplicate or missing dispatch)" }
    # Occupancy never exceeds configured bound.
    if ($admitted -gt $Capacity) { $failures += "B05: occupancy admitted=$admitted exceeds capacity=$Capacity" }
    if ($opsHeld -gt $Capacity) { $failures += "B05: provider_ops=$opsHeld exceeds capacity=$Capacity" }

    # capacity+1 MUST be bounded 429 GATEWAY_RATE_LIMITED with zero new dispatch.
    $over = Send-ProbeChat $probeBase ("m16-b05over-" + $Suffix + "-" + $stamp)
    $opsOver = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
    $tokensOver = Redis-Get $tokensKey
    Write-Output ("[M16-B05] capacity+1: HTTP " + $over.Status + " provider_ops=" + $opsOver + " (want 429, ops=" + $Capacity + ") bucket_tokens=" + $tokensOver)
    Write-Output ("[M16-B05] over body: " + ($over.Body | Out-String).Substring(0, [Math]::Min(500, ($over.Body | Out-String).Length)))
    if ($over.Status -ne 429) { $failures += "B05: capacity+1 got $($over.Status) want bounded 429" }
    if ($over.Body -notmatch "GATEWAY_RATE_LIMITED") { $failures += "B05: capacity+1 body missing documented GATEWAY_RATE_LIMITED" }
    if ($opsOver -ne $Capacity) { $failures += "B05: rejected request dispatched Provider work (ops=$opsOver want $Capacity, rejected ops must be 0)" }

    # Financial invariants on the probe org.
    $budgetTotal = [decimal](Invoke-Root ("SELECT total_amount FROM budget WHERE org_id=" + $OrgId + " LIMIT 1;"))
    $held = [decimal](Invoke-Root ("SELECT COALESCE(SUM(reserved_amount),0) FROM budget_reservation WHERE org_id=" + $OrgId + " AND status IN ('ACTIVE','PENDING_HOLD');"))
    $reqRows = [long](Invoke-Root ("SELECT COUNT(*) FROM gateway_request WHERE org_id=" + $OrgId + ";"))
    Write-Output ("[M16-B05] financial: held=" + $held + "/" + $budgetTotal + " requests=" + $reqRows + " provider_ops=" + $opsOver)
    if ($held -gt $budgetTotal) { $failures += "B05: overspend held=$held > total=$budgetTotal" }
    if ($opsOver -gt ($Capacity + 1)) { $failures += "B05: duplicate dispatch ops=$opsOver" }

    # Release/converge: token refill restores capacity. Poll fresh work until
    # admitted 200 (bounded wait, never a fixed sleep as proof).
    $freshStatus = -1
    $freshBody = ""
    for ($w = 0; $w -lt 30; $w++) {
        Start-Sleep 2
        $fr = Send-ProbeChat $probeBase ("m16-b05fresh-" + $Suffix + "-" + $stamp + "-" + $w)
        $freshStatus = $fr.Status
        $freshBody = $fr.Body
        $tok = Redis-Get $tokensKey
        Write-Output ("[M16-B05] recovery poll=" + $w + " HTTP " + $freshStatus + " bucket_tokens=" + $tok)
        if ($freshStatus -eq 200) { break }
        if ($freshStatus -ge 500 -or $freshStatus -eq -1) { break }
    }
    Write-Output ("[M16-B05] fresh after recovery: HTTP " + $freshStatus)
    if ($freshStatus -ne 200) { $failures += "B05: fresh work after recovery got $freshStatus want 200" }

    $opsFinal = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
    # ops_final = capacity fills + exactly 1 recovery admit. Recovery polls
    # that hit 429 are bounded rejections (zero dispatch); polls that would
    # admit beyond the single fresh request must not exist: the loop breaks
    # on the first 200, so exactly one recovery dispatch is legal.
    $recoveryAdmits = $opsFinal - $opsOver
    Write-Output ("[M16-B05] final: capacity=" + $Capacity + " admitted=" + $admitted + " rejected_over=1 recovery_admits=" + $recoveryAdmits + " ops_final=" + $opsFinal)
    if ($recoveryAdmits -ne 1) { $failures += "B05: recovery admits=$recoveryAdmits want exactly 1 (loop must break on first 200)" }
    Write-Output ("[M16-B05] saturated_resource=rate-limiter capacity=" + $Capacity + " refill_per_sec=" + $RefillPerSecond + " occupancy=" + $admitted + " rejected=1 code=429/GATEWAY_RATE_LIMITED rejected_ops=0 recovery=" + $freshStatus)
} finally {
    docker rm -f $probeName 2>$null | Out-Null
    try { Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null } catch { }
}

if ($failures.Count -gt 0) {
    Write-Output ("[M16-B05-RED] M16_B05_FAIL: " + ($failures -join "; "))
    exit 1
}
Write-Output "[M16-B05] M16_B05_PASS"
exit 0
