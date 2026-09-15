<#
.SYNOPSIS
    M21 Gateway DB least-privilege provisioning.

.DESCRIPTION
    Creates a dedicated Gateway DB user with frozen least-privilege grants.
    Idempotent: safe to run multiple times.

    Uses container-side MySQL execution to avoid host-side env expansion.

.PARAMETER ComposeProject
    Docker Compose project name.

.PARAMETER GatewayUser
    Gateway DB username. Default: gw_m21

.PARAMETER Database
    Database name. Default: aicostops

.EXAMPLE
    .\scripts\m21\provision-gateway-db.ps1
    .\scripts\m21\provision-gateway-db.ps1 -GatewayUser gw_m21 -Database aicostops
#>
[CmdletBinding()]
param(
    [string]$ComposeProject = "aicostops-m21-final",
    [string]$ComposeFile = "compose.yaml,compose.v3-operational.yaml",
    [string]$GatewayUser = "gw_m21",
    [string]$Database = "aicostops",
    [string]$GatewayPassword = "change-me-m21-gw-only"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

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
$composeArgs += ".env"

function Invoke-Mysql([string]$User, [string]$Password, [string]$Sql) {
    $env:MYSQL_PWD = $Password
    try {
        $result = docker compose @composeArgs exec -T mysql sh -lc "mysql -u $User -N -B -e `"$Sql`" $Database 2>&1"
        return ($result | Out-String).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Invoke-Root([string]$Sql) {
    return Invoke-Mysql -User "root" -Password $env:MYSQL_ROOT_PASSWORD -Sql $Sql
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

# Step 2: Apply least-privilege grants (frozen contract from M16)
Write-Output "[M21-DB] Applying least-privilege grants..."

# SELECT on read tables
$grants = @(
    "GRANT SELECT ON $Database.billing_period TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.budget TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.ledger_posting TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.ledger_entry TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.organization TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.project TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.model_catalog TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.provider_account TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.provider_model TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.provider_connection_profile TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.pricing_version TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.pricing_rate TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.routing_policy TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.routing_policy_candidate TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.gateway_credential TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.gateway_credential_model TO '$GatewayUser'@'%';"
    "GRANT SELECT ON $Database.service_identity TO '$GatewayUser'@'%';"

    # Gateway-owned write tables
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
Write-Output "[M21-DB] Privilege verification PASSED."
Write-Output "[M21-DB] Gateway DB identity: $GatewayUser (least-privilege)"
Write-Output "[M21-DB] M21_GATEWAY_DB_PROVISION_PASS"
