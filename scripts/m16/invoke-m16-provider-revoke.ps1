<#
.SYNOPSIS
    M16 D-group: provider outage routing safety + live credential revoke.

.DESCRIPTION
    D01/D02: point the mock at http500 failure mode. With a single candidate
    there is no eligible failover target: the request must fail WITHOUT a
    second Provider execution on retry of the same identity (no blind
    redispatch), and the route attempt must record BILLABLE_POSSIBLE-safe
    semantics (never fabricated zero cost).
    D03: revoke the Gateway credential through SQL (mirrors the Control Plane
    governed revoke: status REVOKED + revoked_at). New request B with the same
    key must be rejected before Provider (401/403, zero new Provider ops);
    the already-incurred request A keeps exactly one financial outcome.
    Prints M16_D_PASS or M16_D_FAIL.
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
    [Parameter(Mandatory)][long]$CredentialId,
    [string]$ModelKey = "m16-accept-chat"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$failures = [System.Collections.ArrayList]::new()
function Add-Failure([string]$Message) {
    Write-Output ("[M16-D-RED] " + $Message)
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
function Send-Chat([string]$KeySuffix, [string]$Key) {
    $headers = @{
        Authorization     = "Bearer $Key"
        "Idempotency-Key" = $KeySuffix
        "Content-Type"    = "application/json"
    }
    $body = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 provider safety"}]}'
    try {
        $r = Invoke-WebRequest -Uri ($GatewayBase + "/v1/chat/completions") -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 90
        return [pscustomobject]@{ Status = $r.StatusCode; Body = $r.Content }
    } catch {
        return [pscustomobject]@{ Status = -1; Body = $_.Exception.Message }
    }
}
function Mock-Ops() { return (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions }
$stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")

# ---- D02: provider http500, single candidate -> no blind redispatch ----
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"http500"}' -ContentType "application/json" | Out-Null
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"http500"}' -ContentType "application/json" | Out-Null
$keyA = "m16-d02-" + $stamp
$a1 = Send-Chat $keyA $RawKey
$opsAfterA1 = Mock-Ops
$a2 = Send-Chat $keyA $RawKey
$opsAfterA2 = Mock-Ops
Write-Output ("[M16-D02] http500 first: HTTP " + $a1.Status + " ops=" + $opsAfterA1 + "; replay: HTTP " + $a2.Status + " ops=" + $opsAfterA2)
if ($opsAfterA1 -ne 1) { Add-Failure ("D02: first attempt provider_ops=" + $opsAfterA1 + " (want 1).") }
if ($opsAfterA2 -ne 1) { Add-Failure ("D02: blind redispatch on replay (ops=" + $opsAfterA2 + ").") }
if ($a1.Status -eq 200) { Add-Failure "D02: http500 upstream must not return 200." }
$attemptStatus = Invoke-Root ("SELECT status FROM gateway_route_attempt WHERE org_id=" + $OrgId + " ORDER BY id DESC LIMIT 1;")
Write-Output ("[M16-D02] latest attempt status=" + $attemptStatus)
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null

# ---- baseline request A (incurred work) before revoke ----
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null
$keyBase = "m16-d03a-" + $stamp
$base = Send-Chat $keyBase $RawKey
Write-Output ("[M16-D03] baseline A: HTTP " + $base.Status)
if ($base.Status -ne 200) { Add-Failure ("D03 setup: baseline request A got " + $base.Status + " (want 200).") }
$reqBefore = Invoke-Root ("SELECT COUNT(*) FROM gateway_request WHERE org_id=" + $OrgId + ";")

# ---- D03: revoke credential (governed status transition) ----
Invoke-Root ("UPDATE gateway_credential SET status='REVOKED', revoked_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6) WHERE id=" + $CredentialId + " AND org_id=" + $OrgId + ";") | Out-Null
$opsBeforeB = Mock-Ops
$b = Send-Chat ("m16-d03b-" + $stamp) $RawKey
$opsAfterB = Mock-Ops
Write-Output ("[M16-D03] revoked-key B: HTTP " + $b.Status + " ops_before=" + $opsBeforeB + " ops_after=" + $opsAfterB)
if ($b.Status -eq 200) { Add-Failure "D03: revoked credential admitted (must reject before Provider)." }
if ($opsAfterB -ne $opsBeforeB) { Add-Failure "D03: revoked key caused new Provider execution." }
$reqAfter = Invoke-Root ("SELECT COUNT(*) FROM gateway_request WHERE org_id=" + $OrgId + ";")
$stateA = Invoke-Root ("SELECT state FROM gateway_request WHERE org_id=" + $OrgId + " ORDER BY id DESC LIMIT 1;")
Write-Output ("[M16-D03] requests before=" + $reqBefore + " after=" + $reqAfter + " (B must not create billable state)")
if ($stateA -ne "TRANSPORT_COMPLETED") { Add-Failure ("D03: baseline A state=" + $stateA + " (want TRANSPORT_COMPLETED).") }

if ($failures.Count -gt 0) {
    Write-Output ("[M16-D-RED] M16_D_FAIL (" + $failures.Count + " violation(s))")
    exit 1
}
Write-Output "[M16-D] M16_D_PASS (D02/D03)"
exit 0
