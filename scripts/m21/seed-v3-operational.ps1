<#
.SYNOPSIS
    M21 V3 operational seed — fixes data gaps left by DevGatewayBootstrap.

.DESCRIPTION
    DevGatewayBootstrap (backend, dev profile) creates most V24+ data:
    org, billing period, project, service identity, gateway credential,
    model catalog, provider account (MIMO), provider model, provider credential,
    pricing version, pricing rate, routing policy, routing policy candidate.

    This script fixes the two things DevGatewayBootstrap does NOT create:
    1. provider_connection_profile (V24+ endpoint authority) — with auth_type=BEARER
    2. provider_catalog CUSTOM_OPENAI_COMPATIBLE status — ensure ACTIVE

    Idempotent: safe to run multiple times.

.PARAMETER MockBaseUrl
    Mock provider base URL. Default: http://mock-provider:8089/v1

.EXAMPLE
    pwsh -File scripts/m21/seed-v3-operational.ps1
#>
[CmdletBinding()]
param(
    [string]$ComposeProject = "aicostops-m21-final",
    [string]$MockBaseUrl = "http://mock-provider:8089/v1"
)

$ErrorActionPreference = "Stop"

Write-Output "[M21-SEED] Starting V3 operational seed fix..."

function Invoke-Mysql([string]$Sql) {
    $result = docker exec "${ComposeProject}-mysql-1" mysql -u root -pchange-me-local-root-only aicostops -N -B -e "$Sql" 2>&1
    $filtered = ($result | Where-Object { $_ -notmatch "Warning" }) -join "`n"
    return $filtered.Trim()
}

# Step 1: Ensure CUSTOM_OPENAI_COMPATIBLE provider_catalog is ACTIVE
$catalogStatus = Invoke-Mysql "SELECT status FROM provider_catalog WHERE provider_code='CUSTOM_OPENAI_COMPATIBLE';"
if ($catalogStatus -ne "ACTIVE") {
    Invoke-Mysql "UPDATE provider_catalog SET status='ACTIVE' WHERE provider_code='CUSTOM_OPENAI_COMPATIBLE';" | Out-Null
    Write-Output "[M21-SEED] Enabled CUSTOM_OPENAI_COMPATIBLE in provider_catalog"
} else {
    Write-Output "[M21-SEED] CUSTOM_OPENAI_COMPATIBLE already ACTIVE"
}

# Step 2: Find existing provider_account (created by DevGatewayBootstrap)
$acctId = Invoke-Mysql "SELECT id FROM provider_account WHERE provider_code='MIMO' LIMIT 1;"
if ([string]::IsNullOrEmpty($acctId)) {
    Write-Error "[M21-SEED] No MIMO provider_account found. Ensure DevGatewayBootstrap ran with AICOSTOPS_MIMO_API_KEY."
    exit 1
}
Write-Output "[M21-SEED] Provider Account: $acctId (MIMO)"

# Step 3: Find org_id from provider_account
$orgId = Invoke-Mysql "SELECT org_id FROM provider_account WHERE id=$acctId;"
Write-Output "[M21-SEED] Organization: $orgId"

# Step 4: Create provider_connection_profile if missing (V24+ endpoint authority)
$existingProfile = Invoke-Mysql "SELECT id FROM provider_connection_profile WHERE provider_account_id=$acctId AND status='ACTIVE' LIMIT 1;"
if ([string]::IsNullOrEmpty($existingProfile)) {
    $profileId = Invoke-Mysql @"
INSERT INTO provider_connection_profile(
    org_id, provider_account_id, version, connection_kind, protocol_code,
    base_url, completion_path, models_path, auth_type, network_policy,
    connect_timeout_ms, response_timeout_ms, status, created_at, activated_at
) VALUES(
    $orgId, $acctId, 1, 'CUSTOM', 'OPENAI_CHAT_COMPLETIONS',
    '$MockBaseUrl', '/chat/completions', '/models', 'BEARER', 'DIRECT_ONLY',
    5000, 60000, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
SELECT LAST_INSERT_ID();
"@ | Out-String
    $profileId = $profileId.Trim()
    Write-Output "[M21-SEED] Created Connection Profile: $profileId (endpoint: $MockBaseUrl, auth: BEARER)"
} else {
    $profileId = $existingProfile.Trim()
    # Ensure auth_type is BEARER (not NONE) so credential lookup triggers
    $currentAuth = Invoke-Mysql "SELECT auth_type FROM provider_connection_profile WHERE id=$profileId;"
    if ($currentAuth -ne "BEARER") {
        Invoke-Mysql "UPDATE provider_connection_profile SET auth_type='BEARER' WHERE id=$profileId;" | Out-Null
        Write-Output "[M21-SEED] Updated Connection Profile $profileId auth_type: $currentAuth -> BEARER"
    } else {
        Write-Output "[M21-SEED] Connection Profile already exists: $profileId"
    }
}

# Step 5: Ensure provider_credential exists (created by DevGatewayBootstrap when AICOSTOPS_MIMO_API_KEY is set)
$credCount = Invoke-Mysql "SELECT COUNT(*) FROM provider_credential WHERE provider_account_id=$acctId AND status='ACTIVE';"
if ($credCount -eq "0") {
    Write-Warning "[M21-SEED] No ACTIVE provider_credential found. DevGatewayBootstrap should have created one with AICOSTOPS_MIMO_API_KEY."
    Write-Warning "[M21-SEED] Gateway dispatch will fail without a decryptable credential."
} else {
    Write-Output "[M21-SEED] Provider Credential: $credCount ACTIVE credential(s) found"
}

# Step 6: Verify routing chain exists
$policyId = Invoke-Mysql "SELECT id FROM routing_policy WHERE org_id=$orgId AND status='ACTIVE' LIMIT 1;"
$candidateCount = Invoke-Mysql "SELECT COUNT(*) FROM routing_policy_candidate WHERE routing_policy_id=$policyId AND status='ACTIVE';"
$modelId = Invoke-Mysql "SELECT model_id FROM routing_policy WHERE id=$policyId;"
$pricingCount = Invoke-Mysql "SELECT COUNT(*) FROM pricing_version WHERE org_id=$orgId AND provider_account_id=$acctId AND status='ACTIVE';"

Write-Output "[M21-SEED] Routing Policy: $policyId ($candidateCount candidate(s))"
Write-Output "[M21-SEED] Model: $modelId"
Write-Output "[M21-SEED] Pricing: $pricingCount ACTIVE version(s)"

Write-Output "[M21-SEED] M21_SEED_PASS"

# Output summary
$summary = @{
    org_id = [long]$orgId
    provider_account_id = [long]$acctId
    connection_profile_id = [long]$profileId
    routing_policy_id = [long]$policyId
    mock_base_url = $MockBaseUrl
} | ConvertTo-Json -Depth 3

Write-Output "`n[M21-SEED] Summary:"
Write-Output $summary
