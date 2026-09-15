<#
.SYNOPSIS
    M21 V3 operational seed — creates V24+ endpoint authority state.
#>
[CmdletBinding()]
param(
    [string]$ComposeProject = "aicostops-m21-final-r3",
    [string]$MockBaseUrl = "http://mock-provider:8089/v1",
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

Write-Output "[M21-SEED] Starting V3 operational seed..."

# Resolve repo root for state file
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$statePath = Join-Path $repoRoot $StateFile

# Build docker compose exec command prefix
$composeArgs = @()
$composeFile = "compose.yaml,compose.v3-operational.yaml"
foreach ($file in $composeFile.Split(',')) {
    $composeArgs += "-f"
    $composeArgs += $file.Trim()
}
$composeArgs += "-p"
$composeArgs += $ComposeProject
$composeArgs += "--env-file"
$composeArgs += ".env.m21.local"

function Invoke-Mysql([string]$Sql) {
    $env:MYSQL_PWD = $env:MYSQL_ROOT_PASSWORD
    try {
        $result = docker compose @composeArgs exec -T mysql sh -lc "mysql -u root aicostops -N -B -e `"$Sql`" 2>&1"
        $filtered = ($result | Where-Object { $_ -notmatch "Warning" }) -join "`n"
        return $filtered.Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

# Step 1: Find existing provider_account (created by DevGatewayBootstrap)
$acctId = Invoke-Mysql "SELECT id FROM provider_account WHERE provider_code='MIMO' LIMIT 1;"
if ([string]::IsNullOrEmpty($acctId)) {
    Write-Error "[M21-SEED] No MIMO provider_account found. Ensure DevGatewayBootstrap ran with AICOSTOPS_MIMO_API_KEY."
    exit 1
}
Write-Output "[M21-SEED] Provider Account: $acctId (MIMO)"

# Step 2: Find org_id from provider_account
$orgId = Invoke-Mysql "SELECT org_id FROM provider_account WHERE id=$acctId;"
Write-Output "[M21-SEED] Organization: $orgId"

# Step 3: Find provider_model_id for this account
$providerModelId = Invoke-Mysql "SELECT id FROM provider_model WHERE provider_account_id=$acctId AND status='ACTIVE' AND routing_eligible=TRUE LIMIT 1;"
if ([string]::IsNullOrEmpty($providerModelId)) {
    Write-Error "[M21-SEED] No eligible provider_model found for MIMO account."
    exit 1
}

# Step 4: Find pricing_version_id
$pricingVersionId = Invoke-Mysql "SELECT id FROM pricing_version WHERE org_id=$orgId AND provider_account_id=$acctId AND status='ACTIVE' LIMIT 1;"
if ([string]::IsNullOrEmpty($pricingVersionId)) {
    Write-Error "[M21-SEED] No ACTIVE pricing_version found for MIMO account."
    exit 1
}

# Step 5: Create provider_connection_profile if missing (V24+ endpoint authority)
$existingProfile = Invoke-Mysql "SELECT id FROM provider_connection_profile WHERE provider_account_id=$acctId AND status='ACTIVE' LIMIT 1;"
if ([string]::IsNullOrEmpty($existingProfile)) {
    $insertSql = @"
INSERT INTO provider_connection_profile(
    org_id, provider_account_id, version, connection_kind, protocol_code,
    base_url, completion_path, models_path, auth_type, auth_header_name, network_policy,
    connect_timeout_ms, response_timeout_ms, status, created_at, activated_at
) VALUES(
    $orgId, $acctId, 1, 'CUSTOM', 'OPENAI_CHAT_COMPLETIONS',
    '$MockBaseUrl', '/chat/completions', '/models', 'API_KEY_HEADER', 'X-API-Key', 'DIRECT_ONLY',
    5000, 60000, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
SELECT LAST_INSERT_ID();
"@
    $profileId = Invoke-Mysql $insertSql | Out-String
    $profileId = $profileId.Trim()
    Write-Output "[M21-SEED] Created Connection Profile: $profileId (endpoint: $MockBaseUrl, auth: API_KEY_HEADER, header: X-API-Key)"
} else {
    $profileId = $existingProfile.Trim()
    # Ensure auth_type is API_KEY_HEADER (not NONE) so credential lookup triggers
    $currentAuth = Invoke-Mysql "SELECT auth_type FROM provider_connection_profile WHERE id=$profileId;"
    if ($currentAuth -ne "API_KEY_HEADER") {
        Invoke-Mysql "UPDATE provider_connection_profile SET auth_type='API_KEY_HEADER', auth_header_name='X-API-Key' WHERE id=$profileId;" | Out-Null
        Write-Output "[M21-SEED] Updated Connection Profile $profileId auth_type: $currentAuth -> API_KEY_HEADER"
    } else {
        Write-Output "[M21-SEED] Connection Profile already exists: $profileId"
    }
}

# Step 6: Ensure provider_credential exists
$credCount = Invoke-Mysql "SELECT COUNT(*) FROM provider_credential WHERE provider_account_id=$acctId AND status='ACTIVE';"
if ($credCount -eq "0") {
    Write-Error "[M21-SEED] No ACTIVE provider_credential found. DevGatewayBootstrap should have created one with AICOSTOPS_MIMO_API_KEY. Gateway dispatch will fail without a decryptable credential."
    exit 1
}
Write-Output "[M21-SEED] Provider Credential: $credCount ACTIVE credential(s) found"

# Step 7: Verify routing chain exists
$policyId = Invoke-Mysql "SELECT id FROM routing_policy WHERE org_id=$orgId AND status='ACTIVE' LIMIT 1;"
if ([string]::IsNullOrEmpty($policyId)) {
    Write-Error "[M21-SEED] No ACTIVE routing_policy found for org $orgId."
    exit 1
}
$candidateCount = Invoke-Mysql "SELECT COUNT(*) FROM routing_policy_candidate WHERE routing_policy_id=$policyId AND status='ACTIVE';"
if ($candidateCount -eq "0") {
    Write-Error "[M21-SEED] No ACTIVE routing_policy_candidate found for policy $policyId."
    exit 1
}
$modelId = Invoke-Mysql "SELECT model_id FROM routing_policy WHERE id=$policyId;"
$pricingCount = Invoke-Mysql "SELECT COUNT(*) FROM pricing_version WHERE org_id=$orgId AND provider_account_id=$acctId AND status='ACTIVE';"
if ($pricingCount -eq "0") {
    Write-Error "[M21-SEED] No ACTIVE pricing_version found."
    exit 1
}

Write-Output "[M21-SEED] Routing Policy: $policyId ($candidateCount candidate(s))"
Write-Output "[M21-SEED] Model: $modelId"
Write-Output "[M21-SEED] Pricing: $pricingCount ACTIVE version(s)"

Write-Output "[M21-SEED] M21_SEED_PASS"

# Output machine-readable state
$summary = @{
    org_id = [long]$orgId
    provider_account_id = [long]$acctId
    provider_model_id = [long]$providerModelId
    connection_profile_id = [long]$profileId
    routing_policy_id = [long]$policyId
    pricing_version_id = [long]$pricingVersionId
    mock_base_url = $MockBaseUrl
    auth_type = "API_KEY_HEADER"
    auth_header_name = "X-API-Key"
} | ConvertTo-Json -Depth 3

Write-Output "`n[M21-SEED] State:"
Write-Output $summary

# Write state file
[System.IO.File]::WriteAllText($statePath, $summary)
Write-Output "[M21-SEED] State written to $statePath"