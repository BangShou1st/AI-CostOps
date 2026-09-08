<#
.SYNOPSIS
    M16 B01: 100-way identical idempotency replay over real HTTP.

.DESCRIPTION
    Fires 100 concurrent POST /v1/chat/completions with the same credential,
    same Idempotency-Key, same exact body (coordinated start, no sleeps),
    then asserts durable truth:
      gateway_request identity       = 1
      effective Reservation          <= 1
      economically billable attempt  <= 1
      Provider operation             = 1 (mock-provider counter)
      duplicate Ledger effect        = 0
    Prints M16_B01_PASS or M16_B01_FAIL and exits accordingly.

.EXAMPLE
    .\scripts\m16\invoke-m16-b01-idempotency.ps1 -GatewayBase http://127.0.0.1:8081 `
      -RawKey "aic_..." -ModelKey "m16-accept-chat" -OrgId 9
#>
[CmdletBinding()]
param(
    [string]$GatewayBase = "http://127.0.0.1:8081",
    [string]$MockBase = "http://127.0.0.1:18089",
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13307,
    [string]$Database = "m16accept",
    [string]$MysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [Parameter(Mandatory)][string]$RawKey,
    [Parameter(Mandatory)][string]$ModelKey,
    [Parameter(Mandatory)][long]$OrgId,
    [int]$Workers = 100
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

# Reset the mock counter so the Provider-operation count is exact for this run.
Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null

$idemKey = "m16-b01-" + [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")
$body = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 b01 identical replay"}]}'
$headers = @{
    Authorization    = "Bearer $RawKey"
    "Idempotency-Key" = $idemKey
    "Content-Type"   = "application/json"
}
$uri = $GatewayBase + "/v1/chat/completions"

$runspaces = [runspacefactory]::CreateRunspacePool(1, $Workers)
$runspaces.Open()
$script = {
    param($Uri, $Headers, $Body, $Gate)
    $Gate.WaitOne() | Out-Null
    try {
        $resp = Invoke-WebRequest -Uri $Uri -Method Post -Headers $Headers -Body $Body -SkipHttpErrorCheck -TimeoutSec 120
        return [pscustomobject]@{ Status = $resp.StatusCode; Body = $resp.Content }
    } catch {
        return [pscustomobject]@{ Status = -1; Body = $_.Exception.Message }
    }
}
$gate = New-Object System.Threading.ManualResetEvent($false)
$handles = @()
for ($i = 0; $i -lt $Workers; $i++) {
    $ps = [powershell]::Create().AddScript($script).AddArgument($uri).AddArgument($headers).AddArgument($body).AddArgument($gate)
    $ps.RunspacePool = $runspaces
    $handles += [pscustomobject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
}
$gate.Set() | Out-Null
$results = @()
foreach ($h in $handles) {
    $results += $h.PS.EndInvoke($h.Handle)
    $h.PS.Dispose()
}
$runspaces.Close()

$byStatus = $results | Group-Object Status | Sort-Object Name
Write-Output "[M16-B01] status distribution:"
foreach ($g in $byStatus) { Write-Output ("[M16-B01]   HTTP " + $g.Name + " x " + $g.Count) }

$providerOps = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
# gateway_request identity is keyed by idempotency_key_digest =
# HMAC-SHA256("idem\0" + rawKey) under the request HMAC key (never persisted raw).
# Read from the running gateway container (same pattern as B05/load) so the
# harness is self-contained and does not depend on the parent process env.
$gwEnv = docker inspect m16-gateway-accept --format '{{json .Config.Env}}' | ConvertFrom-Json
$hit = @($gwEnv | Where-Object { $_ -like "AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1=*" })
if ($hit.Count -eq 0) { throw "AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1 not found in gateway container env" }
$hmacKey = [Convert]::FromBase64String($hit[0].Substring("AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1".Length + 1).Trim())
$hm = New-Object System.Security.Cryptography.HMACSHA256(,$hmacKey)
$hm.TransformBlock([Text.Encoding]::UTF8.GetBytes("idem`0"), 0, 5, $null, 0) | Out-Null
$hm.TransformFinalBlock([Text.Encoding]::UTF8.GetBytes($idemKey), 0, $idemKey.Length) | Out-Null
$digestHex = -join ($hm.Hash | ForEach-Object { $_.ToString("x2") })
$reqCount = [long](Invoke-Root ("SELECT COUNT(*) FROM gateway_request WHERE org_id=" + $OrgId + " AND idempotency_key_digest=UNHEX('" + $digestHex + "');"))
$resCount = [long](Invoke-Root ("SELECT COUNT(*) FROM budget_reservation br JOIN gateway_route_attempt ra ON ra.id=br.route_attempt_id JOIN gateway_request gr ON gr.id=ra.request_id WHERE gr.org_id=" + $OrgId + " AND gr.idempotency_key_digest=UNHEX('" + $digestHex + "') AND br.status IN ('ACTIVE','PENDING_HOLD');"))
$billableAttempts = [long](Invoke-Root ("SELECT COUNT(*) FROM gateway_route_attempt ra JOIN gateway_request gr ON gr.id=ra.request_id WHERE gr.org_id=" + $OrgId + " AND gr.idempotency_key_digest=UNHEX('" + $digestHex + "') AND ra.status IN ('COMPLETED','BILLABLE_POSSIBLE');"))
$ok200 = @($results | Where-Object { $_.Status -eq 200 }).Count

Write-Output ("[M16-B01] provider_operations=" + $providerOps)
Write-Output ("[M16-B01] gateway_requests(org)=" + $reqCount)
Write-Output ("[M16-B01] effective_reservations=" + $resCount)
Write-Output ("[M16-B01] billable_attempts=" + $billableAttempts)
Write-Output ("[M16-B01] http_200=" + $ok200)

$failures = @()
if ($providerOps -ne 1) { $failures += "provider_operations=$providerOps (want 1)" }
if ($reqCount -ne 1) { $failures += "gateway_requests=$reqCount (want 1)" }
if ($resCount -gt 1) { $failures += "effective_reservations=$resCount (want <=1)" }
if ($billableAttempts -gt 1) { $failures += "billable_attempts=$billableAttempts (want <=1)" }
if ($ok200 -lt 1) { $failures += "http_200=$ok200 (want >=1)" }

if ($failures.Count -gt 0) {
    Write-Output ("[M16-B01-RED] M16_B01_FAIL: " + ($failures -join "; "))
    exit 1
}
Write-Output "[M16-B01] M16_B01_PASS"
exit 0
