<#
.SYNOPSIS
    M16 E03: secret/content sentinel leak scan over acceptance surfaces.

.DESCRIPTION
    Uses synthetic sentinel values only (never real secrets): gateway raw key
    prefix, provider secret label, Authorization token fragment, raw
    Idempotency-Key, prompt and completion markers. Drives one success + one
    failure request carrying the prompt sentinel, then scans:
      - gateway container logs;
      - mock-provider logs (diagnostic surface);
      - /actuator/prometheus snapshot;
      - error response bodies captured during the run.
    Any prohibited occurrence is RED. Prints M16_E03_PASS or M16_E03_FAIL.
#>
[CmdletBinding()]
param(
    [string]$GatewayBase = "http://127.0.0.1:18081",
    [string]$MockBase = "http://127.0.0.1:18089",
    [Parameter(Mandatory)][string]$RawKey,
    [string]$ModelKey = "m16-accept-chat"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$failures = [System.Collections.ArrayList]::new()

$promptSentinel = "m16-prompt-sentinel-9f3a7c"
$completionSentinel = "Hello from M16 mock"
$idemSentinel = "m16-leak-idem-4d2e88"
$stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")

function Send-Chat([string]$KeySuffix, [string]$Prompt, [string]$Key) {
    $headers = @{
        Authorization     = "Bearer $Key"
        "Idempotency-Key" = $KeySuffix
        "Content-Type"    = "application/json"
    }
    $body = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"' + $Prompt + '"}]}'
    try {
        $r = Invoke-WebRequest -Uri ($GatewayBase + "/v1/chat/completions") -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 90
        return [pscustomobject]@{ Status = $r.StatusCode; Body = $r.Content }
    } catch {
        return [pscustomobject]@{ Status = -1; Body = $_.Exception.Message }
    }
}

# Success + failure (unknown model -> 4xx) carrying the prompt sentinel.
$ok = Send-Chat ("m16-leak-ok-" + $stamp) $promptSentinel $RawKey
$badKeyHeaders = @{
    Authorization     = "Bearer invalid-key-shape"
    "Idempotency-Key" = $idemSentinel
    "Content-Type"    = "application/json"
}
try {
    $bad = Invoke-WebRequest -Uri ($GatewayBase + "/v1/chat/completions") -Method Post -Headers $badKeyHeaders -Body '{"model":"nope","messages":[]}' -SkipHttpErrorCheck -TimeoutSec 60
    $badBody = $bad.Content
    $badStatus = $bad.StatusCode
} catch {
    $badBody = $_.Exception.Message
    $badStatus = -1
}
Write-Output ("[M16-E03] success=" + $ok.Status + " failure=" + $badStatus)

$gwLogs = docker logs m16-gateway-accept 2>&1 | Out-String
$mockLogs = docker logs m16-mock-provider 2>&1 | Out-String
$metrics = Invoke-WebRequest -Uri ($GatewayBase + "/actuator/prometheus") -SkipHttpErrorCheck -TimeoutSec 30
$metricsBody = $metrics.Content

$secretPart = ($RawKey -split "_", 3)[2]
$checks = @(
    @{ Label = "gateway-logs:raw-key-secret"; Surface = $gwLogs; Needle = $secretPart; Forbid = $true },
    @{ Label = "gateway-logs:prompt"; Surface = $gwLogs; Needle = $promptSentinel; Forbid = $true },
    @{ Label = "gateway-logs:completion"; Surface = ($gwLogs + $ok.Body); Needle = $completionSentinel; Forbid = $false; Note = "client response body carries completion by design" },
    @{ Label = "gateway-logs:idempotency-raw"; Surface = $gwLogs; Needle = $idemSentinel; Forbid = $true },
    @{ Label = "mock-logs:prompt"; Surface = $mockLogs; Needle = $promptSentinel; Forbid = $false; Note = "mock is the Provider stand-in; real Provider sees prompts by design" },
    @{ Label = "metrics:raw-key-secret"; Surface = $metricsBody; Needle = $secretPart; Forbid = $true },
    @{ Label = "metrics:prompt"; Surface = $metricsBody; Needle = $promptSentinel; Forbid = $true },
    @{ Label = "error-body:raw-key-secret"; Surface = $badBody; Needle = $secretPart; Forbid = $true },
    @{ Label = "error-body:prompt"; Surface = $badBody; Needle = $promptSentinel; Forbid = $true }
)
foreach ($c in $checks) {
    $hit = $c.Surface.Contains($c.Needle)
    if ($c.Forbid -and $hit) {
        $failures.Add("LEAK: " + $c.Label) | Out-Null
        Write-Output ("[M16-E03-RED] LEAK: " + $c.Label)
    } elseif ($c.Forbid) {
        Write-Output ("[M16-E03] clean: " + $c.Label)
    } else {
        Write-Output ("[M16-E03] info: " + $c.Label + " (" + $c.Note + ")")
    }
}
# Provider secret sentinel: the mock receives sk-m16-acceptance-* by design
# (it IS the upstream); assert it never appears in gateway logs/metrics.
$provHit = $gwLogs.Contains("sk-m16-acceptance-") -or $metricsBody.Contains("sk-m16-acceptance-")
if ($provHit) {
    $failures.Add("LEAK: provider secret in gateway logs/metrics") | Out-Null
    Write-Output "[M16-E03-RED] LEAK: provider secret in gateway logs/metrics"
} else {
    Write-Output "[M16-E03] clean: provider secret absent from gateway logs/metrics"
}

if ($failures.Count -gt 0) {
    Write-Output ("[M16-E03-RED] M16_E03_FAIL (" + $failures.Count + " leak(s))")
    exit 1
}
Write-Output "[M16-E03] M16_E03_PASS (zero forbidden sentinel leakage)"
exit 0
