<#
.SYNOPSIS
    M11 Gateway Edge smoke: liveness/readiness, bounded chat completions,
    same-idempotency replay without a second Provider dispatch, SSE through
    [DONE], and the request status API. Never prints secrets.

.DESCRIPTION
    Runs against a running local Gateway (native dev process, default
    http://localhost:8081). Secrets come from the environment (or ./env file)
    and are never printed. The Provider-facing path requires a real MiMo key:
    if AICOSTOPS_MIMO_API_KEY is absent the Provider path is recorded as
    BLOCKED, never as PASS.

.EXAMPLE
    .\scripts\smoke-m11-gateway.ps1 -EnvFile ".env.m11"
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$EnvFile = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string]$Name) {
    Write-Output "[SMOKE] $Name"
}

function Read-Env([string]$Name) {
    if ($EnvFile -and (Test-Path -LiteralPath $EnvFile)) {
        $escaped = [regex]::Escape($Name)
        $line = Get-Content -LiteralPath $EnvFile |
            Where-Object { $_ -match "^$escaped=" } |
            Select-Object -First 1
        if ($line) {
            return (($line -split "=", 2)[1]).Trim()
        }
    }
    return [Environment]::GetEnvironmentVariable($Name)
}

function Invoke-Gateway([string]$Method, [string]$Path, [hashtable]$Body = $null) {
    $rawKey = Read-Env "AICOSTOPS_GATEWAY_DEV_RAW_KEY"
    if (-not $rawKey) {
        throw "AICOSTOPS_GATEWAY_DEV_RAW_KEY is required for the smoke run"
    }
    $headers = @{
        Authorization = "Bearer $rawKey"
    }
    $uri = "$BaseUrl$Path"
    if ($null -eq $Body) {
        return Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -SkipHttpErrorCheck
    }
    $headers["Content-Type"] = "application/json"
    return Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -Body ($Body | ConvertTo-Json -Compress) -SkipHttpErrorCheck
}

Write-Stage "starting M11 Gateway smoke against $BaseUrl"
$failures = @()
$blockedProvider = $false
$hasMiMoKey = -not [string]::IsNullOrWhiteSpace((Read-Env "AICOSTOPS_MIMO_API_KEY"))
$result = [ordered]@{}

# 1. Liveness / readiness (actuator returns a byte[] body)
$liveness = Invoke-WebRequest -Uri "$BaseUrl/actuator/health/liveness" -SkipHttpErrorCheck
$ready = Invoke-WebRequest -Uri "$BaseUrl/actuator/health/readiness" -SkipHttpErrorCheck
$livenessOk = $liveness.StatusCode -eq 200 -and ([Text.Encoding]::UTF8.GetString($liveness.Content) -match '"UP"')
$readyOk = $ready.StatusCode -eq 200 -and ([Text.Encoding]::UTF8.GetString($ready.Content) -match '"UP"')
$result["liveness"] = if ($livenessOk) { "PASS" } else { "FAIL" }
$result["readiness"] = if ($readyOk) { "PASS" } else { "FAIL" }
if (-not $livenessOk) { $failures += "liveness" }
if (-not $readyOk) { $failures += "readiness" }

$idemKey = "smoke-" + [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")
$nonStreamingBody = [ordered]@{
    model    = (Read-Env "AICOSTOPS_GATEWAY_SMOKE_MODEL")
    messages = @(@{ role = "user"; content = "hi" })
}
if ([string]::IsNullOrWhiteSpace($nonStreamingBody.model)) {
    $nonStreamingBody.model = "default-chat"
}

if (-not $hasMiMoKey) {
    Write-Stage "AICOSTOPS_MIMO_API_KEY is not set; Provider path is BLOCKED"
    $blockedProvider = $true
    $result["nonStreamingChat"] = "BLOCKED"
    $result["idempotencyReplay"] = "BLOCKED"
    $result["sseStream"] = "BLOCKED"
    $result["requestStatus"] = "BLOCKED"
} else {
    # 2. Non-streaming chat completion
    $post = Invoke-WebRequest -Uri "$BaseUrl/v1/chat/completions" -Method Post `
        -Headers @{ Authorization = "Bearer $(Read-Env AICOSTOPS_GATEWAY_DEV_RAW_KEY)"; "Content-Type" = "application/json"; "Idempotency-Key" = $idemKey } `
        -Body ($nonStreamingBody | ConvertTo-Json -Compress) -SkipHttpErrorCheck
    $requestId = $post.Headers["X-AI-CostOps-Request-Id"]
    $nonStreamingOk = $post.StatusCode -eq 200 -and $post.Content -match '"chat.completion"'
    $result["nonStreamingChat"] = if ($nonStreamingOk) { "PASS" } else { "FAIL (HTTP $($post.StatusCode))" }
    if (-not $nonStreamingOk) { $failures += "nonStreamingChat" }

    # 3. Same idempotency replay: must not dispatch a second Provider call.
    $replay = Invoke-WebRequest -Uri "$BaseUrl/v1/chat/completions" -Method Post `
        -Headers @{ Authorization = "Bearer $(Read-Env AICOSTOPS_GATEWAY_DEV_RAW_KEY)"; "Content-Type" = "application/json"; "Idempotency-Key" = $idemKey } `
        -Body ($nonStreamingBody | ConvertTo-Json -Compress) -SkipHttpErrorCheck
    $replayOk = $replay.StatusCode -eq 409 -and $replay.Content -match "GATEWAY_RESPONSE_NOT_RETAINED"
    $result["idempotencyReplay"] = if ($replayOk) { "PASS" } else { "FAIL (HTTP $($replay.StatusCode))" }
    if (-not $replayOk) { $failures += "idempotencyReplay" }

    # 4. SSE stream through [DONE]
    $streamBody = @{
        model    = $nonStreamingBody.model
        messages = $nonStreamingBody.messages
        stream   = $true
    }
    $sse = Invoke-WebRequest -Uri "$BaseUrl/v1/chat/completions" -Method Post `
        -Headers @{ Authorization = "Bearer $(Read-Env AICOSTOPS_GATEWAY_DEV_RAW_KEY)"; "Content-Type" = "application/json"; "Idempotency-Key" = "stream-$idemKey" } `
        -Body ($streamBody | ConvertTo-Json -Compress) -SkipHttpErrorCheck
    $sseOk = $sse.StatusCode -eq 200 -and $sse.Content -match "\[DONE\]"
    $result["sseStream"] = if ($sseOk) { "PASS" } else { "FAIL (HTTP $($sse.StatusCode))" }
    if (-not $sseOk) { $failures += "sseStream" }

    # 5. Request status API for the non-streaming request.
    if ($requestId) {
        $status = Invoke-WebRequest -Uri "$BaseUrl/v1/gateway/requests/$requestId" -Method Get `
            -Headers @{ Authorization = "Bearer $(Read-Env AICOSTOPS_GATEWAY_DEV_RAW_KEY)" } -SkipHttpErrorCheck
        $statusOk = $status.StatusCode -eq 200 -and $status.Content -match "TRANSPORT_COMPLETED"
        $result["requestStatus"] = if ($statusOk) { "PASS" } else { "FAIL (HTTP $($status.StatusCode))" }
        if (-not $statusOk) { $failures += "requestStatus" }
    } else {
        $result["requestStatus"] = "FAIL (no request id)"
        $failures += "requestStatus"
    }
}

Write-Stage "results"
foreach ($entry in $result.GetEnumerator()) {
    Write-Output ("[SMOKE] {0} = {1}" -f $entry.Key, $entry.Value)
}

if ($blockedProvider) {
    Write-Output "[SMOKE] BLOCKED: missing external MiMo credential (AICOSTOPS_MIMO_API_KEY not set)"
}
if ($failures.Count -gt 0) {
    Write-Output "[SMOKE] FAILED checks: $($failures -join ', ')"
    exit 1
}
Write-Stage "smoke completed"
exit 0