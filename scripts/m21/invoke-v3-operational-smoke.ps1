<#
.SYNOPSIS
    M21 V3 governed execution smoke.
#>
[CmdletBinding()]
param(
    [string]$GatewayBase = "http://127.0.0.1:8081",
    [string]$MockBase = "http://127.0.0.1:8089",
    [string]$ComposeProject = "aicostops-m21-final-r3",
    [string]$ComposeFile = "compose.yaml,compose.v3-operational.yaml",
    [string]$IdempotencyKey = "",
    [string]$StateFile = ".m21-operational-state.json"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Self-import env
$helperPath = Join-Path $PSScriptRoot "import-v3-operational-env.ps1"
if (Test-Path $helperPath) {
    . $helperPath
    Import-M21OperationalEnv
}

# Resolve repo root
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

if ([string]::IsNullOrWhiteSpace($IdempotencyKey)) {
    $IdempotencyKey = "m21-smoke-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
}

Write-Output "[M21-SMOKE] Starting governed execution smoke..."
Write-Output "[M21-SMOKE] Gateway: $GatewayBase"
Write-Output "[M21-SMOKE] Mock: $MockBase"
Write-Output "[M21-SMOKE] Idempotency: $IdempotencyKey"

# Step 0: Read Gateway raw key from local env file (not printed)
$envFile = Join-Path $repoRoot ".env.m21.local"
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

# Step 0b: Read expected profile id from seed state file
$statePath = Join-Path $repoRoot $StateFile
if (-not (Test-Path $statePath)) {
    Write-Error "[M21-SMOKE] State file not found. Run scripts/m21/seed-v3-operational.ps1 first."
    exit 1
}
$state = Get-Content $statePath -Raw | ConvertFrom-Json
$expectedProfileId = $state.connection_profile_id
Write-Output "[M21-SMOKE] Expected profile from seed: $expectedProfileId"

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
    $response = Invoke-RestMethod -Uri "$GatewayBase/v1/chat/completions" -Method POST -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -ErrorAction Stop
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
Start-Sleep -Seconds 2
$finalStats = Invoke-RestMethod -Uri "$MockBase/stats" -Method GET
$finalCount = $finalStats.post_chat_completions
$delta = $finalCount - $initialCount

Write-Output "[M21-SMOKE] Final mock invocation count: $finalCount (delta: $delta)"

if ($delta -ne 1) {
    Write-Error "[M21-SMOKE] FAILED: Expected invocation delta 1, got $delta"
    exit 1
}
Write-Output "[M21-SMOKE] Mock invocation count VERIFIED (+1)"

# Step 7: Query route_attempt bound to THIS request via idempotency key
Write-Output "[M21-SMOKE] Verifying route_attempt lineage for request $IdempotencyKey..."

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
    $env:MYSQL_PWD = $env:MYSQL_GATEWAY_PASSWORD
    try {
        $result = docker compose @composeArgs exec -T mysql sh -lc "mysql -u gw_m21 aicostops -e `"$Sql`" 2>&1"
        return ($result | Out-String).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

# Step 7a: Find gateway_request by idempotency key digest
$idemDigest = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($IdempotencyKey))).ToLower()
$requestResult = Invoke-Mysql "SELECT id, public_request_id FROM gateway_request WHERE idempotency_key_digest=UNHEX('$idemDigest') AND org_id=$($state.org_id);"

if ([string]::IsNullOrWhiteSpace($requestResult)) {
    Write-Error "[M21-SMOKE] FAILED: No gateway_request found for idempotency key digest"
    exit 1
}

$requestId = ($requestResult -split "`t")[0]
Write-Output "[M21-SMOKE] Found request: $requestId"

# Step 7b: Find route_attempt for this request
$attemptResult = Invoke-Mysql "SELECT id, provider_connection_profile_id, routing_policy_id, provider_account_id, provider_model_id FROM gateway_route_attempt WHERE request_id=$requestId AND org_id=$($state.org_id) ORDER BY attempt_no DESC LIMIT 1;"

if ([string]::IsNullOrWhiteSpace($attemptResult)) {
    Write-Error "[M21-SMOKE] FAILED: No route_attempt found for request $requestId"
    exit 1
}

$attemptFields = $attemptResult -split "`t"
$attemptProfileId = $attemptFields[1]
$attemptPolicyId = $attemptFields[2]
$attemptAccountId = $attemptFields[3]
$attemptModelId = $attemptFields[4]

Write-Output "[M21-SMOKE] Route attempt profile: $attemptProfileId"
Write-Output "[M21-SMOKE] Route attempt policy: $attemptPolicyId"
Write-Output "[M21-SMOKE] Route attempt account: $attemptAccountId"

# Step 7c: EXACT profile equality assertion
if ($attemptProfileId -ne $expectedProfileId) {
    Write-Error "[M21-SMOKE] FAILED: Profile mismatch! Expected $expectedProfileId, got $attemptProfileId"
    exit 1
}
Write-Output "[M21-SMOKE] PROFILE_LINEAGE_EXACT_MATCH_PASS (expected=$expectedProfileId, actual=$attemptProfileId)"

# Step 7d: Verify lineage fields match seed state
if ($attemptPolicyId -ne $state.routing_policy_id) {
    Write-Warning "[M21-SMOKE] Policy mismatch: expected $($state.routing_policy_id), got $attemptPolicyId"
}
if ($attemptAccountId -ne $state.provider_account_id) {
    Write-Warning "[M21-SMOKE] Account mismatch: expected $($state.provider_account_id), got $attemptAccountId"
}
if ($attemptModelId -ne $state.provider_model_id) {
    Write-Warning "[M21-SMOKE] Model mismatch: expected $($state.provider_model_id), got $attemptModelId"
}

# Step 8: Verify provider_catalog legacy URL is NOT used for dispatch
$catalogUrl = Invoke-Mysql "SELECT base_url FROM provider_catalog WHERE provider_code='MIMO' AND status='ACTIVE' LIMIT 1;"
if (-not [string]::IsNullOrWhiteSpace($catalogUrl) -and $catalogUrl -eq $state.mock_base_url) {
    Write-Warning "[M21-SMOKE] Catalog base_url matches mock URL — legacy URL may have authority"
} else {
    Write-Output "[M21-SMOKE] Catalog legacy base_url differs from mock endpoint — ACTIVE profile is authority"
}

Write-Output "[M21-SMOKE] M21_V3_GOVERNED_SMOKE_PASS"