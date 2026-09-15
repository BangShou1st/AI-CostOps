<#
.SYNOPSIS
    M21 Gateway DB least-privilege provisioning.

.DESCRIPTION
    Creates a dedicated Gateway DB user with frozen least-privilege grants.
    Self-imports .env.m21.local (no manual env required).
#>
[CmdletBinding()]
param(
    [string]$ComposeProject = "aicostops-m21-final-r3",
    [string]$ComposeFile = "compose.yaml,compose.v3-operational.yaml",
    [string]$GatewayUser = "gw_m21",
    [string]$Database = "aicostops",
    [string]$GatewayPassword = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Self-import env (dot-source shared helper)
$helperPath = Join-Path $PSScriptRoot "import-v3-operational-env.ps1"
if (Test-Path $helperPath) {
    . $helperPath
    Import-M21OperationalEnv
} else {
    Write-Warning "[M21-DB] Helper not found; relying on existing env"
}

# Resolve Gateway password
if ([string]::IsNullOrWhiteSpace($GatewayPassword)) {
    $GatewayPassword = $env:MYSQL_GATEWAY_PASSWORD
}
if ([string]::IsNullOrWhiteSpace($GatewayPassword)) {
    Write-Error "[M21-DB] Gateway password not available. Run scripts/m21/new-v3-operational-env.ps1 first."
    exit 1
}

# Resolve root password
$rootPassword = $env:MYSQL_ROOT_PASSWORD
if ([string]::IsNullOrWhiteSpace($rootPassword)) {
    Write-Error "[M21-DB] MYSQL_ROOT_PASSWORD not available. Run scripts/m21/new-v3-operational-env.ps1 first."
    exit 1
}

Write-Output "[M21-DB] Provisioning Gateway DB identity: $GatewayUser"

# Build docker compose exec command prefix
$composeArgs = @()
foreach ($file in $ComposeFile.Split(',')) {
    $composeArgs += "-f"
    $composeArgs += $file.Trim()
}
$composeArgs += "-p"
$composeArgs += $ComposeProject
$composeArgs += "--env-file"
$composeArgs += ".env.m21.local"

function Invoke-Root([string]$Sql) {
    $env:MYSQL_PWD = $rootPassword
    try {
        $result = docker compose @composeArgs exec -T mysql sh -lc "mysql -u root $Database -e `"$Sql`" 2>&1"
        return ($result | Out-String).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Invoke-Gateway([string]$Sql) {
    $env:MYSQL_PWD = $GatewayPassword
    try {
        $result = docker compose @composeArgs exec -T mysql sh -lc "mysql -u $GatewayUser $Database -e `"$Sql`" 2>&1"
        return ($result | Out-String).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

# Step 1: Create user if not exists
Write-Output "[M21-DB] Creating user $GatewayUser..."
$createSql = "CREATE USER IF NOT EXISTS '$GatewayUser'@'%' IDENTIFIED BY '$GatewayPassword';"
$createResult = Invoke-Root -Sql $createSql
if ($LASTEXITCODE -ne 0) {
    Write-Error "[M21-DB] FAILED to create user: $createResult"
    exit 1
}
Write-Output "[M21-DB] User $GatewayUser created/verified."

# Step 2: Apply least-privilege grants
Write-Output "[M21-DB] Applying least-privilege grants..."

$grants = @(
    "GRANT SELECT ON $Database.billing_period TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.budget TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.ledger_posting TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.ledger_entry TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.organization TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.organization_member TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.project TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.team TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.cost_center TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.model_catalog TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.provider_account TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.provider_model TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.provider_catalog TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.provider_connection_profile TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.provider_credential TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.pricing_version TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.pricing_rate TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.routing_policy TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.routing_policy_candidate TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.gateway_credential TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.gateway_credential_model TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.service_identity TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.gateway_settlement TO '$GatewayUser'@'%';"

    "GRANT SELECT, INSERT, UPDATE ON $Database.gateway_request TO '$GatewayUser'@'%';"
    "GRANT SELECT, INSERT, UPDATE ON $Database.gateway_route_attempt TO '$GatewayUser'@'%';"
    "GRANT SELECT, INSERT, UPDATE ON $Database.gateway_usage_fact TO '$GatewayUser'@'%';"
    "GRANT SELECT, INSERT, UPDATE ON $Database.gateway_usage_dimension TO '$GatewayUser'@'%';"
    "GRANT SELECT, INSERT, UPDATE ON $Database.budget_reservation TO '$GatewayUser'@'%';"
)

foreach ($grant in $grants) {
    $result = Invoke-Root -Sql $grant
    if ($LASTEXITCODE -ne 0) {
        Write-Error "[M21-DB] FAILED to apply grant: $grant - $result"
        exit 1
    }
}

Write-Output "[M21-DB] All grants applied."

# Step 3: Verify grants
Write-Output "[M21-DB] Verifying grants..."
$showGrants = Invoke-Root -Sql "SHOW GRANTS FOR '$GatewayUser'@'%';"
if ($showGrants -match "GRANT OPTION") {
    Write-Error "[M21-DB] FAILED: Gateway user has GRANT OPTION"
    exit 1
}
if ($showGrants -match "(?i)\bDELETE\b") {
    Write-Error "[M21-DB] FAILED: Gateway user has DELETE privilege"
    exit 1
}
if ($showGrants -match "(?i)\bDROP\b") {
    Write-Error "[M21-DB] FAILED: Gateway user has DROP privilege"
    exit 1
}
Write-Output "[M21-DB] Privilege verification PASSED."
Write-Output "[M21-DB] Gateway DB identity: $GatewayUser (least-privilege)"
Write-Output "[M21-DB] M21_GATEWAY_DB_PROVISION_PASS"