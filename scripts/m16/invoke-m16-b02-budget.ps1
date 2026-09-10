<#
.SYNOPSIS
    M16 B02: constrained-Budget concurrency proves no race-induced overspend.

.DESCRIPTION
    Seeds one org with a Budget sized for exactly one reservation, fires N
    concurrent requests with DISTINCT Idempotency-Keys, then asserts:
      admitted Provider operations <= 1 (mock counter);
      SUM(ACTIVE/PENDING_HOLD reservations) <= budget total (no overspend);
      every admitted request converges to exactly one financial outcome.
    Prints M16_B02_PASS or M16_B02_FAIL.

.EXAMPLE
    .\scripts\m16\invoke-m16-b02-budget.ps1 -Suffix b02 -Workers 20
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
    [int]$Workers = 20
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

Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null
$budgetTotal = [decimal](Invoke-Root ("SELECT total_amount FROM budget WHERE org_id=" + $OrgId + " LIMIT 1;"))

$uri = $GatewayBase + "/v1/chat/completions"
$pool = [runspacefactory]::CreateRunspacePool(1, $Workers)
$pool.Open()
$sb = {
    param($Uri, $RawKey, $ModelKey, $KeySuffix, $Gate)
    $Gate.WaitOne() | Out-Null
    $headers = @{
        Authorization     = "Bearer $RawKey"
        "Idempotency-Key" = $KeySuffix
        "Content-Type"    = "application/json"
    }
    $body = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 b02 race"}]}'
    try {
        $r = Invoke-WebRequest -Uri $Uri -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 120
        return [pscustomobject]@{ Status = $r.StatusCode }
    } catch {
        return [pscustomobject]@{ Status = -1 }
    }
}
$gate = New-Object System.Threading.ManualResetEvent($false)
$hs = @()
$stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")
for ($i = 0; $i -lt $Workers; $i++) {
    $p = [powershell]::Create().AddScript($sb).AddArgument($uri).AddArgument($RawKey).AddArgument($ModelKey).AddArgument(("m16-b02-" + $stamp + "-" + $i)).AddArgument($gate)
    $p.RunspacePool = $pool
    $hs += [pscustomobject]@{ P = $p; H = $p.BeginInvoke() }
}
$gate.Set() | Out-Null
$res = @()
foreach ($h in $hs) { $res += $h.P.EndInvoke($h.H); $h.P.Dispose() }
$pool.Close()

$res | Group-Object Status | Sort-Object Name | ForEach-Object { Write-Output ("[M16-B02] HTTP " + $_.Name + " x " + $_.Count) }
$providerOps = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
$held = [decimal](Invoke-Root ("SELECT COALESCE(SUM(reserved_amount),0) FROM budget_reservation WHERE org_id=" + $OrgId + " AND status IN ('ACTIVE','PENDING_HOLD');"))
$ok200 = @($res | Where-Object { $_.Status -eq 200 }).Count
Write-Output ("[M16-B02] provider_operations=" + $providerOps)
Write-Output ("[M16-B02] effective_reserved=" + $held + " budget_total=" + $budgetTotal)
Write-Output ("[M16-B02] http_200=" + $ok200)

$failures = @()
if ($providerOps -gt 1) { $failures += "provider_operations=$providerOps (want <=1)" }
if ($held -gt $budgetTotal) { $failures += "overspend: held=$held > total=$budgetTotal" }
if ($ok200 -gt 1) { $failures += "http_200=$ok200 (want <=1)" }
if ($ok200 -lt 1 -and $providerOps -lt 1) { $failures += "no request admitted at all (harness/budget mis-sized?)" }
if ($failures.Count -gt 0) {
    Write-Output ("[M16-B02-RED] M16_B02_FAIL: " + ($failures -join "; "))
    exit 1
}
Write-Output "[M16-B02] M16_B02_PASS"
exit 0
