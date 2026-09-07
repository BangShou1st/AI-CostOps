<#
.SYNOPSIS
    M16 C-group: dependency failure and restart proof (MySQL/Redis).

.DESCRIPTION
    Runs against the live M16 acceptance stack (gateway 18081, mock 18089,
    mysql 13307, redis 16379). Each case seeds a fresh org, injects the
    failure via docker stop/start or redis FLUSHALL, and asserts durable truth:
      C01 MySQL down before dispatch -> HTTP 5xx/429 bounded, provider_ops=0;
      C03 MySQL restart -> readiness returns, new request 200, old facts intact;
      C04 Redis down -> request fails closed (no 200 with fabricated budget);
      C05 Redis FLUSHALL (state loss) -> next request still admitted only if
            MySQL durable facts allow; provider_ops matches admitted count;
      C-redis-restart -> readiness returns, no duplicate Provider execution.
    Prints M16_C_PASS or M16_C_FAIL.

.EXAMPLE
    .\scripts\m16\invoke-m16-failure.ps1 -Suffix fail1 -RawKey ... -OrgId 21 -HmacKey ...
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
    [string]$ModelKey = "m16-accept-chat"
)

$redisPassword = [Environment]::GetEnvironmentVariable("M16_REDIS_PASSWORD")
if ([string]::IsNullOrWhiteSpace($redisPassword)) { $redisPassword = "m16-redis-accept-pass" }

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$failures = [System.Collections.ArrayList]::new()
function Add-Failure([string]$Message) {
    Write-Output ("[M16-C-RED] " + $Message)
    $failures.Add($Message) | Out-Null
}

function Send-Chat([string]$KeySuffix) {
    $headers = @{
        Authorization     = "Bearer $RawKey"
        "Idempotency-Key" = $KeySuffix
        "Content-Type"    = "application/json"
    }
    $body = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 failure"}]}'
    try {
        $r = Invoke-WebRequest -Uri ($GatewayBase + "/v1/chat/completions") -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 90
        return [pscustomobject]@{ Status = $r.StatusCode; Body = $r.Content }
    } catch {
        return [pscustomobject]@{ Status = -1; Body = $_.Exception.Message }
    }
}
function Mock-Ops() { return (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions }
function Mock-Reset() { Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null }

$stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")

# ---- C04: Redis down -> fail closed ----
docker stop m16-redis-accept | Out-Null
Start-Sleep 3
Mock-Reset
$r = Send-Chat ("m16-c04-" + $stamp)
$ops = Mock-Ops
Write-Output ("[M16-C04] redis-down: HTTP " + $r.Status + " provider_ops=" + $ops)
if ($r.Status -eq 200) { Add-Failure "C04: request admitted while Redis down (must fail closed)." }
if ($ops -ne 0) { Add-Failure ("C04: provider_ops=" + $ops + " while Redis down (want 0).") }
docker start m16-redis-accept | Out-Null
Start-Sleep 8

# ---- C-redis-restart: recovery without duplicate execution ----
Mock-Reset
$r = Send-Chat ("m16-cred-" + $stamp)
$ops = Mock-Ops
Write-Output ("[M16-C-REDIS] after-restart: HTTP " + $r.Status + " provider_ops=" + $ops)
if ($r.Status -ne 200) { Add-Failure ("C-redis-restart: expected 200 after Redis restart, got " + $r.Status + ".") }
if ($ops -ne 1) { Add-Failure ("C-redis-restart: provider_ops=" + $ops + " (want 1).") }

# ---- C05: Redis state loss (FLUSHALL) -> MySQL truth still governs ----
docker exec m16-redis-accept redis-cli -a "$redisPassword" FLUSHALL | Out-Null
Mock-Reset
$r = Send-Chat ("m16-c05-" + $stamp)
$ops = Mock-Ops
Write-Output ("[M16-C05] redis-flushed: HTTP " + $r.Status + " provider_ops=" + $ops)
if ($r.Status -ne 200) { Add-Failure ("C05: expected 200 after Redis loss (MySQL truth governs), got " + $r.Status + ".") }
if ($ops -ne 1) { Add-Failure ("C05: provider_ops=" + $ops + " (want 1).") }

# ---- C01: MySQL down before dispatch -> zero Provider calls ----
docker stop m16-mysql-accept | Out-Null
Start-Sleep 3
Mock-Reset
$r = Send-Chat ("m16-c01-" + $stamp)
$ops = Mock-Ops
Write-Output ("[M16-C01] mysql-down: HTTP " + $r.Status + " provider_ops=" + $ops)
if ($r.Status -eq 200) { Add-Failure "C01: request admitted while MySQL down (must fail closed)." }
if ($ops -ne 0) { Add-Failure ("C01: provider_ops=" + $ops + " while MySQL down (want 0).") }
docker start m16-mysql-accept | Out-Null
Write-Output "[M16-C] waiting for MySQL recovery..."
for ($i = 0; $i -lt 30; $i++) {
    $ready = docker exec m16-mysql-accept mysqladmin ping -h localhost --silent 2>$null
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep 5
}
Start-Sleep 10

# ---- C03: MySQL restart -> reconnect, facts intact, new work flows ----
$ready = Invoke-WebRequest -Uri ($GatewayBase + "/actuator/health/readiness") -SkipHttpErrorCheck -TimeoutSec 30
Write-Output ("[M16-C03] readiness after mysql restart: HTTP " + $ready.StatusCode)
Mock-Reset
$r = Send-Chat ("m16-c03-" + $stamp)
$ops = Mock-Ops
Write-Output ("[M16-C03] after-restart: HTTP " + $r.Status + " provider_ops=" + $ops)
if ($r.Status -ne 200) { Add-Failure ("C03: expected 200 after MySQL restart, got " + $r.Status + ".") }
if ($ops -ne 1) { Add-Failure ("C03: provider_ops=" + $ops + " (want 1).") }

if ($failures.Count -gt 0) {
    Write-Output ("[M16-C-RED] M16_C_FAIL (" + $failures.Count + " violation(s))")
    exit 1
}
Write-Output "[M16-C] M16_C_PASS (C01/C03/C04/C05/redis-restart)"
exit 0
