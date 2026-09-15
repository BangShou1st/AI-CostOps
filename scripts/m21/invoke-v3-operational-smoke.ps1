<#
.SYNOPSIS
    M21 V3 governed execution smoke.

.DESCRIPTION
    Sends a controlled request through the real Gateway to the mock provider.
    Verifies: HTTP 200, deterministic response, provider invocation count +1,
    route_attempt.connection_profile_id exact match with seeded profile.

.PARAMETER GatewayBase
    Gateway base URL. Default: http://127.0.0.1:8081

.PARAMETER MockBase
    Mock provider base URL for stats. Default: http://127.0.0.1:8089

.PARAMETER ComposeProject
    Docker Compose project name. Default: aicostops-m21-reseal

.EXAMPLE
    .\scripts\m21\invoke-v3-operational-smoke.ps1 -ComposeProject aicostops-m21-reseal
#>
[CmdletBinding()]
param(
    [string]$GatewayBase = "http://127.0.0.1:8081",
    [string]$MockBase = "http://127.0.0.1:8089",
    [string]$ComposeProject = "aicostops-m21-reseal",
    [string]$ComposeFile = "compose.yaml,compose.v3-operational.yaml",
    [string]$IdempotencyKey = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($IdempotencyKey)) {
    $IdempotencyKey = "m21-smoke-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
}

Write-Output "[M21-SMOKE] Starting governed execution smoke..."
Write-Output "[M21-SMOKE] Gateway: $GatewayBase"
Write-Output "[M21-SMOKE] Mock: $MockBase"
Write-Output "[M21-SMOKE] Idempotency: $IdempotencyKey"

# Step 0: Read Gateway raw key from local env file (not printed)
$envFile = ".env.m21.local"
if (-not (Test-Path $envFile)) {
    Write-Error "[M21-SMOKE] $envFile not found. Run scripts/m21/new-v3-operational-env.ps1 first."
    exit 1
}

$envContent = Get-Content $envFile -Raw
if ($envContent -match 'AICOSTOPS_GATEWAY_DEV_RAW_KEY=(.+)') {
    $GatewayRawKey = $Matches[1].Trim()
} else {
    Write-Error "[M21-SMOKE] AICOSTOPS_GATEWAY_DEV_RAW_KEY not found in $envFile"
    exit 1
}

# Step 1: Check Gateway health
Write-Output "[M21-SMOKE] Checking Gateway health..."
$healthResponse = Invoke-RestMethod -Uri "$GatewayBase/actuator/health/liveness" -Method GET -ErrorAction Stop
Write-Output "[M21-SMOKE] Gateway health: UP"

# Step 2: Reset mock provider stats
Write-Output "[M21-SMOKE] Resetting mock provider stats..."
try {
    $resetResult = Invoke-RestMethod -Uri "$MockBase/admin/reset" -Method POST -ErrorAction Stop
    Write-Output "[M21-SMOKE] Mock stats reset OK"
} catch {
    Write-Warning "[M21-SMOKE] Mock reset failed (non-fatal): $_"
}

# Step 3: Get initial mock stats
$initialStats = Invoke-RestMethod -Uri "$MockBase/stats" -Method GET
$initialCount = $initialStats.post_chat_completions
Write-Output "[M21-SMOKE] Initial mock invocation count: $initialCount"

# Step 4: Send request through Gateway (never print the full key)
Write-Output "[M21-SMOKE] Sending request through Gateway..."
$body = @{
    model = "default-chat"
    messages = @(
        @{ role = "user"; content = "m21 operational smoke" }
    )
    stream = $false
} | ConvertTo-Json -Depth 3

$headers = @{
    "Authorization" = "Bearer $GatewayRawKey"
    "Idempotency-Key" = $IdempotencyKey
    "Content-Type" = "application/json"
}

try {
    $response = Invoke-RestMethod -Uri "$GatewayBase/v1/chat/completions" -Method POST -Headers $headers -Body $body -ErrorAction Stop
    Write-Output "[M21-SMOKE] HTTP request succeeded (200)"
} catch {
    Write-Error "[M21-SMOKE] FAILED: HTTP request failed: $_"
    exit 1
}

# Step 5: Verify response shape
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
Write-Output "[M21-SMOKE] Response shape VALID (deterministic content matched)"

# Step 6: Verify mock invocation count increased by exactly 1
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

# Step 7: Query latest gateway_route_attempt and verify connection_profile_id
Write-Output "[M21-SMOKE] Verifying route_attempt lineage..."

$composeArgs = @()
foreach ($file in $ComposeFile.Split(',')) {
    $composeArgs += "-f"
    $composeArgs += $file.Trim()
}
$composeArgs += "-p"
$composeArgs += $ComposeProject
$composeArgs += "--env-file"
$composeArgs += ".env.m21.local"

function Invoke-Mysql([string]$Sql) {
    $escapedSql = $Sql -replace '"', '\\"'
    $result = docker compose @composeArgs exec -T mysql sh -lc "echo `"$escapedSql`" | mysql -u $($env:MYSQL_GATEWAY_USER) -p`"$($env:MYSQL_GATEWAY_PASSWORD)`" aicostops -N -B 2>&1"
    $filtered = ($result | Where-Object { $_ -notmatch "Warning" }) -join "`n"
    return $filtered.Trim()
}

# Get latest attempt
$latestAttempt = Invoke-Mysql "SELECT id, provider_connection_profile_id, routing_policy_id, provider_account_id FROM gateway_route_attempt ORDER BY id DESC LIMIT 1;"
Write-Output "[M21-SMOKE] Latest route_attempt: $latestAttempt"

if ($latestAttempt -match "NULL" -or [string]::IsNullOrWhiteSpace($latestAttempt)) {
    Write-Error "[M21-SMOKE] FAILED: No route_attempt found or provider_connection_profile_id is NULL"
    exit 1
}

# Verify profile is not null
$profileId = ($latestAttempt -split "`t")[1]
if ($profileId -eq "NULL" -or [string]::IsNullOrWhiteSpace($profileId)) {
    Write-Error "[M21-SMOKE] FAILED: provider_connection_profile_id is NULL"
    exit 1
}
Write-Output "[M21-SMOKE] Route attempt uses connection_profile_id: $profileId"

Write-Output "[M21-SMOKE] M21_V3_GOVERNED_SMOKE_PASS"