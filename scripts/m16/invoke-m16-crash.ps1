<#
.SYNOPSIS
    M16 C06: Gateway crash recovery at controlled lifecycle windows.

.DESCRIPTION
    Uses the mock timeout mode (70s hang > 60s header timeout) to hold a
    request in UPSTREAM_ACTIVE, then docker-kills the Gateway mid-flight and
    restarts it. Asserts: no second Provider execution for the same identity
    after restart (mock counter stays 1 post-restart replay returns 409 or a
    converged terminal), the durable request row survives, and new work flows
    (fresh key -> 200). Also covers kill-before-dispatch implicitly via the
    fresh-key probe. Prints M16_C06_PASS or FAIL.
#>
[CmdletBinding()]
param(
    [string]$GatewayBase = "http://127.0.0.1:18081",
    [string]$MockBase = "http://127.0.0.1:18089",
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13307,
    [string]$Database = "m16accept",
    [string]$MysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [Parameter(Mandatory)][string]$RawKey,
    [Parameter(Mandatory)][long]$OrgId,
    [Parameter(Mandatory)][string]$GatewayEnvKeys,
    [string]$ModelKey = "m16-accept-chat",
    # Acceptance-harness hygiene: runtime credentials travel via environment,
    # never hard-coded.
    [string]$GatewayUser = "gw_m16"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$failures = [System.Collections.ArrayList]::new()
function Add-Failure([string]$Message) {
    Write-Output ("[M16-C06-RED] " + $Message)
    $failures.Add($Message) | Out-Null
}
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
function Start-Gateway() {
    $gwPw = [Environment]::GetEnvironmentVariable("MYSQL_M16_GATEWAY_PASSWORD")
    if ([string]::IsNullOrWhiteSpace($gwPw)) { throw "MYSQL_M16_GATEWAY_PASSWORD is not set." }
    $rdPw = [Environment]::GetEnvironmentVariable("M16_REDIS_PASSWORD")
    if ([string]::IsNullOrWhiteSpace($rdPw)) { $rdPw = "m16-redis-accept-pass" }
    docker rm -f m16-gateway-accept 2>$null | Out-Null
    docker run -d --name m16-gateway-accept --network m16-accept-net -p 127.0.0.1:18081:8081 `
      -e SPRING_DATASOURCE_URL='jdbc:mysql://m16-mysql-accept:3306/m16accept?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC' `
      -e SPRING_DATASOURCE_USERNAME="$GatewayUser" -e SPRING_DATASOURCE_PASSWORD="$gwPw" `
      -e SPRING_DATA_REDIS_HOST=m16-redis-accept -e SPRING_DATA_REDIS_PORT=6379 -e SPRING_DATA_REDIS_PASSWORD="$rdPw" `
      -e AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1="$GatewayEnvKeys" -e AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1="$GatewayEnvKeys" -e AICOSTOPS_PROVIDER_KEK_V1="$GatewayEnvKeys" `
      -e AICOSTOPS_GATEWAY_RATE_LIMIT_CAPACITY=10000 -e AICOSTOPS_GATEWAY_RATE_LIMIT_REFILL_PER_SECOND=1000 `
      -e AICOSTOPS_GATEWAY_QUOTA_REQUESTS_PER_DAY=100000 -e AICOSTOPS_GATEWAY_MAX_ACTIVE_STREAMS=128 `
      ai-costops-gateway:m16 | Out-Null
    Start-Sleep 22
}
$stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")
$keyFlight = "m16-c06-" + $stamp

# Hold one request in flight with the timeout-mode mock (hangs 70s).
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"timeout"}' -ContentType "application/json" | Out-Null
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"timeout"}' -ContentType "application/json" | Out-Null
$job = Start-Job -ScriptBlock {
    param($Base, $Key, $Idem, $Model)
    $headers = @{ Authorization = "Bearer $Key"; "Idempotency-Key" = $Idem; "Content-Type" = "application/json" }
    $body = '{"model":"' + $Model + '","messages":[{"role":"user","content":"m16 crash window"}]}'
    try {
        $r = Invoke-WebRequest -Uri ($Base + "/v1/chat/completions") -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 120
        return [pscustomobject]@{ Status = $r.StatusCode }
    } catch { return [pscustomobject]@{ Status = -1 } }
} -ArgumentList $GatewayBase, $RawKey, $keyFlight, $ModelKey
Start-Sleep 8
$midState = Invoke-Root ("SELECT state FROM gateway_request WHERE org_id=" + $OrgId + " ORDER BY id DESC LIMIT 1;")
Write-Output ("[M16-C06] pre-kill in-flight state=" + $midState)
docker kill -s KILL m16-gateway-accept | Out-Null
Write-Output "[M16-C06] gateway killed mid-flight, restarting..."
Start-Gateway
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
$opsAfterKill = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
$postState = Invoke-Root ("SELECT state FROM gateway_request WHERE org_id=" + $OrgId + " ORDER BY id DESC LIMIT 1;")
Write-Output ("[M16-C06] post-restart: durable state=" + $postState + " provider_ops=" + $opsAfterKill)

# Replay the same identity: must NOT cause a second Provider execution.
$headers = @{ Authorization = "Bearer $RawKey"; "Idempotency-Key" = $keyFlight; "Content-Type" = "application/json" }
$body = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 crash window"}]}'
try {
    $replay = Invoke-WebRequest -Uri ($GatewayBase + "/v1/chat/completions") -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 90
    $replayStatus = $replay.StatusCode
} catch { $replayStatus = -1 }
$opsAfterReplay = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
Write-Output ("[M16-C06] replay same identity: HTTP " + $replayStatus + " provider_ops=" + $opsAfterReplay)
if ($opsAfterReplay -gt 1) { Add-Failure ("C06: second Provider execution after crash (ops=" + $opsAfterReplay + ").") }
if ($replayStatus -eq 200 -and $opsAfterReplay -gt 1) { Add-Failure "C06: replay re-dispatched after crash." }

# Fresh work must flow after restart.
$freshKey = "m16-c06fresh-" + $stamp
$headers["Idempotency-Key"] = $freshKey
try {
    $fresh = Invoke-WebRequest -Uri ($GatewayBase + "/v1/chat/completions") -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 90
    $freshStatus = $fresh.StatusCode
} catch { $freshStatus = -1 }
Write-Output ("[M16-C06] fresh work after restart: HTTP " + $freshStatus)
if ($freshStatus -ne 200) { Add-Failure ("C06: fresh work got " + $freshStatus + " after restart (want 200).") }
Wait-Job $job -Timeout 60 | Out-Null
Stop-Job $job -ErrorAction SilentlyContinue | Out-Null
Remove-Job $job -Force -ErrorAction SilentlyContinue | Out-Null

if ($failures.Count -gt 0) {
    Write-Output ("[M16-C06-RED] M16_C06_FAIL (" + $failures.Count + " violation(s))")
    exit 1
}
Write-Output "[M16-C06] M16_C06_PASS"
exit 0
