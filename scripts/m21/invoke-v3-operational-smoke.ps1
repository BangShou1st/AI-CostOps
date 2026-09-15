<#
.SYNOPSIS
    M21 V3 governed execution smoke.

.DESCRIPTION
    Sends a controlled request through the real Gateway to the mock provider.
    Verifies: HTTP 200, deterministic response, provider invocation count +1.

.PARAMETER GatewayBase
    Gateway base URL. Default: http://127.0.0.1:8081

.PARAMETER MockBase
    Mock provider base URL for stats. Default: http://127.0.0.1:8089

.PARAMETER GatewayRawKey
    Gateway raw key for authentication.

.PARAMETER ModelKey
    Model key for the request.

.EXAMPLE
    .\scripts\m21\invoke-v3-operational-smoke.ps1 -GatewayRawKey "aic_..." -ModelKey "m21-operational-chat"
#>
[CmdletBinding()]
param(
    [string]$GatewayBase = "http://127.0.0.1:8081",
    [string]$MockBase = "http://127.0.0.1:8089",
    [Parameter(Mandatory)][string]$GatewayRawKey,
    [Parameter(Mandatory)][string]$ModelKey,
    [string]$IdempotencyKey = "m21-smoke-$(Get-Random)"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Write-Output "[M21-SMOKE] Starting governed execution smoke..."
Write-Output "[M21-SMOKE] Gateway: $GatewayBase"
Write-Output "[M21-SMOKE] Mock: $MockBase"
Write-Output "[M21-SMOKE] Model: $ModelKey"

# Step 1: Reset mock provider stats
Write-Output "[M21-SMOKE] Resetting mock provider stats..."
try {
    $resetResult = Invoke-RestMethod -Uri "$MockBase/admin/reset" -Method POST -ErrorAction Stop
    Write-Output "[M21-SMOKE] Mock stats reset: $($resetResult | ConvertTo-Json)"
} catch {
    Write-Warning "[M21-SMOKE] Mock reset failed (non-fatal): $_"
}

# Step 2: Get initial mock stats
$initialStats = Invoke-RestMethod -Uri "$MockBase/stats" -Method GET
$initialCount = $initialStats.post_chat_completions
Write-Output "[M21-SMOKE] Initial mock invocation count: $initialCount"

# Step 3: Send request through Gateway
Write-Output "[M21-SMOKE] Sending request through Gateway..."
$body = @{
    model = $ModelKey
    messages = @(
        @{ role = "user"; content = "m21 operational smoke" }
    )
} | ConvertTo-Json -Depth 3

$headers = @{
    "Authorization" = "Bearer $GatewayRawKey"
    "Idempotency-Key" = $IdempotencyKey
    "Content-Type" = "application/json"
}

try {
    $response = Invoke-RestMethod -Uri "$GatewayBase/v1/chat/completions" -Method POST -Headers $headers -Body $body -ErrorAction Stop
    Write-Output "[M21-SMOKE] HTTP request succeeded"
} catch {
    Write-Error "[M21-SMOKE] FAILED: HTTP request failed: $_"
    exit 1
}

# Step 4: Verify response shape
Write-Output "[M21-SMOKE] Response: $($response | ConvertTo-Json -Depth 5)"

if (-not $response.id) {
    Write-Error "[M21-SMOKE] FAILED: Response missing id"
    exit 1
}
if (-not $response.choices) {
    Write-Error "[M21-SMOKE] FAILED: Response missing choices"
    exit 1
}
if ($response.choices.Count -eq 0) {
    Write-Error "[M21-SMOKE] FAILED: Response has empty choices"
    exit 1
}
if ($response.choices[0].message.content -ne "Hello from M16 mock") {
    Write-Error "[M21-SMOKE] FAILED: Unexpected response content: $($response.choices[0].message.content)"
    exit 1
}
Write-Output "[M21-SMOKE] Response shape VALID"

# Step 5: Verify mock invocation count increased by 1
Start-Sleep -Seconds 1
$finalStats = Invoke-RestMethod -Uri "$MockBase/stats" -Method GET
$finalCount = $finalStats.post_chat_completions
$delta = $finalCount - $initialCount

Write-Output "[M21-SMOKE] Final mock invocation count: $finalCount (delta: $delta)"

if ($delta -ne 1) {
    Write-Error "[M21-SMOKE] FAILED: Expected invocation delta 1, got $delta"
    exit 1
}
Write-Output "[M21-SMOKE] Mock invocation count VERIFIED (+1)"

Write-Output "[M21-SMOKE] M21_SMOKE_PASS"
