<#
.SYNOPSIS
    M21 V3 Gateway privilege verification matrix.
#>
[CmdletBinding()]
param(
    [string]$ComposeProject = "aicostops-m21-final-r3",
    [string]$ComposeFile = "compose.yaml,compose.v3-operational.yaml",
    [string]$Database = "aicostops",
    [string]$GatewayUser = "gw_m21"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Self-import env
$helperPath = Join-Path $PSScriptRoot "import-v3-operational-env.ps1"
if (Test-Path $helperPath) {
    . $helperPath
    Import-M21OperationalEnv
}

$failures = [System.Collections.ArrayList]::new()
$passed = [System.Collections.ArrayList]::new()

function Add-Failure([string]$Message) {
    Write-Output "[M21-PRIVILEGE-RED] $Message"
    $failures.Add($Message) | Out-Null
}

function Add-Pass([string]$Message) {
    Write-Output "[M21-PRIVILEGE] PASS: $Message"
    $passed.Add($Message) | Out-Null
}

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
    $env:MYSQL_PWD = $env:MYSQL_ROOT_PASSWORD
    try {
        $result = docker compose @composeArgs exec -T mysql sh -lc "mysql -u root $Database -e `"$Sql`" 2>&1"
        return ($result | Out-String).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Invoke-Gateway([string]$Sql) {
    $env:MYSQL_PWD = $env:MYSQL_GATEWAY_PASSWORD
    try {
        $result = docker compose @composeArgs exec -T mysql sh -lc "mysql -u $GatewayUser $Database -e `"$Sql`" 2>&1"
        return ($result | Out-String).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

Write-Output "[M21-PRIVILEGE] Verifying Gateway DB privileges..."

# === Phase 1: SHOW GRANTS inspection ===
$grants = Invoke-Root "SHOW GRANTS FOR '$GatewayUser'@'%';"

if ($grants -match "GRANT OPTION") {
    Add-Failure "Gateway identity must not hold GRANT OPTION."
} else {
    Add-Pass "No GRANT OPTION."
}

if ($grants -match "(?i)\bALL\s+PRIVILEGES\b") {
    Add-Failure "Gateway identity must not hold ALL PRIVILEGES."
} else {
    Add-Pass "No ALL PRIVILEGES."
}

if ($grants -match "(?i)\bDELETE\b") {
    Add-Failure "Gateway identity must not hold DELETE."
} else {
    Add-Pass "No DELETE privilege."
}

if ($grants -match "(?i)\bDROP\b") {
    Add-Failure "Gateway identity must not hold DROP."
} else {
    Add-Pass "No DROP privilege."
}

# === Phase 2: Positive SELECT matrix ===
$runtimeSelects = @(
    @{ Table = "billing_period"; Sql = "SELECT COUNT(*) FROM billing_period;" },
    @{ Table = "budget"; Sql = "SELECT COUNT(*) FROM budget;" },
    @{ Table = "organization"; Sql = "SELECT COUNT(*) FROM organization;" },
    @{ Table = "project"; Sql = "SELECT COUNT(*) FROM project;" },
    @{ Table = "model_catalog"; Sql = "SELECT COUNT(*) FROM model_catalog;" },
    @{ Table = "provider_account"; Sql = "SELECT COUNT(*) FROM provider_account;" },
    @{ Table = "provider_model"; Sql = "SELECT COUNT(*) FROM provider_model;" },
    @{ Table = "provider_connection_profile"; Sql = "SELECT COUNT(*) FROM provider_connection_profile;" },
    @{ Table = "pricing_version"; Sql = "SELECT COUNT(*) FROM pricing_version;" },
    @{ Table = "pricing_rate"; Sql = "SELECT COUNT(*) FROM pricing_rate;" },
    @{ Table = "routing_policy"; Sql = "SELECT COUNT(*) FROM routing_policy;" },
    @{ Table = "routing_policy_candidate"; Sql = "SELECT COUNT(*) FROM routing_policy_candidate;" },
    @{ Table = "gateway_credential"; Sql = "SELECT COUNT(*) FROM gateway_credential;" },
    @{ Table = "gateway_credential_model"; Sql = "SELECT COUNT(*) FROM gateway_credential_model;" },
    @{ Table = "service_identity"; Sql = "SELECT COUNT(*) FROM service_identity;" },
    @{ Table = "provider_credential"; Sql = "SELECT COUNT(*) FROM provider_credential;" },
    @{ Table = "provider_catalog"; Sql = "SELECT COUNT(*) FROM provider_catalog;" },
    @{ Table = "ledger_posting"; Sql = "SELECT COUNT(*) FROM ledger_posting;" },
    @{ Table = "ledger_entry"; Sql = "SELECT COUNT(*) FROM ledger_entry;" }
)

$selectOk = $true
foreach ($case in $runtimeSelects) {
    $out = Invoke-Gateway $case.Sql
    if ($out -match "ERROR") {
        Add-Failure "Runtime SELECT on $($case.Table) failed: $out"
        $selectOk = $false
    }
}
if ($selectOk) {
    Add-Pass "All runtime SELECTs succeed ($($runtimeSelects.Count) tables)."
}

# === Phase 3: Positive FOR UPDATE (locking reads) ===
$lockCases = @(
    @{ Label = "budget"; Sql = "START TRANSACTION; SELECT id FROM budget WHERE id=999999 FOR UPDATE; ROLLBACK;" },
    @{ Label = "billing_period"; Sql = "START TRANSACTION; SELECT id FROM billing_period WHERE id=999999 FOR UPDATE; ROLLBACK;" },
    @{ Label = "gateway_request"; Sql = "START TRANSACTION; SELECT id FROM gateway_request LIMIT 1 FOR UPDATE; ROLLBACK;" },
    @{ Label = "budget_reservation"; Sql = "START TRANSACTION; SELECT id FROM budget_reservation WHERE id=999999 FOR UPDATE; ROLLBACK;" }
)
$lockOk = $true
foreach ($case in $lockCases) {
    $out = Invoke-Gateway $case.Sql
    if ($out -match "ERROR") {
        Add-Failure "Locking read on $($case.Label) failed: $out"
        $lockOk = $false
    }
}
if ($lockOk) {
    Add-Pass "All SELECT ... FOR UPDATE succeed ($($lockCases.Count) cases)."
}

# === Phase 4: Positive Gateway-owned write (rolled back) ===
$writeSql = "START TRANSACTION; " +
    "INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,service_identity_id,project_id,financial_scope_type,financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,request_hmac_version,state,created_at,validated_at,updated_at) " +
    "VALUES (1,'m21priv00000000000000000000000000001',1,'SERVICE',1,1,'PROJECT',1,1,'CHAT_COMPLETIONS',UNHEX(SHA2('m21-priv-k',256)),UNHEX(SHA2('m21-priv-fp',256)),1,'VALIDATED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); " +
    "UPDATE gateway_request SET state='RESERVED' WHERE public_request_id='m21priv00000000000000000000000000001'; " +
    "ROLLBACK;"
$out = Invoke-Gateway $writeSql
if ($out -match "ERROR") {
    Add-Failure "Gateway-owned INSERT+UPDATE failed: $out"
} else {
    Add-Pass "Gateway-owned INSERT+UPDATE (rolled back)."
}

# === Phase 5: Negative matrix (MySQL must deny with ERROR 1142) ===
$negative = @(
    @{ Label = "budget UPDATE"; Sql = "UPDATE budget SET actual_amount=1 WHERE id=999999;" },
    @{ Label = "billing_period close"; Sql = "UPDATE billing_period SET status='CLOSED' WHERE id=999999;" },
    @{ Label = "ledger_posting INSERT"; Sql = "INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,billing_period_id,status,created_at) VALUES (1,'m21-priv-evil','MANUAL',1,1,'DRAFT',UTC_TIMESTAMP(6));" },
    @{ Label = "ledger_entry INSERT"; Sql = "INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,currency,created_at) VALUES (1,1,0,'DEBIT','1.00','USD',UTC_TIMESTAMP(6));" },
    @{ Label = "provider_credential UPDATE"; Sql = "UPDATE provider_credential SET status='REVOKED' WHERE id=999999;" },
    @{ Label = "provider_catalog UPDATE"; Sql = "UPDATE provider_catalog SET status='INACTIVE' WHERE id=999999;" },
    @{ Label = "gateway_usage_fact DELETE"; Sql = "DELETE FROM gateway_usage_fact WHERE id=999999;" },
    @{ Label = "gateway_request DELETE"; Sql = "DELETE FROM gateway_request WHERE id=999999;" },
    @{ Label = "DDL CREATE TABLE"; Sql = "CREATE TABLE m21_priv_evil(id INT);" },
    @{ Label = "DDL DROP TABLE"; Sql = "DROP TABLE IF EXISTS m21_priv_evil;" },
    @{ Label = "GRANT OPTION attempt"; Sql = "GRANT SELECT ON $Database.budget TO '$GatewayUser'@'%';" }
)

foreach ($case in $negative) {
    $out = Invoke-Gateway $case.Sql
    if ($out -match "ERROR 1142") {
        Add-Pass "Denied: $($case.Label)."
    } else {
        Add-Failure "Forbidden statement NOT denied [$($case.Label)]: $out"
    }
}

# === Summary ===
if ($failures.Count -gt 0) {
    Write-Output "`n[M21-PRIVILEGE-RED] M21_PRIVILEGE_RED ($($failures.Count) violation(s))"
    foreach ($f in $failures) { Write-Output "  - $f" }
    exit 1
}

Write-Output "`n[M21-PRIVILEGE] M21_GATEWAY_PRIVILEGE_GREEN: least-privilege enforced by MySQL."
Write-Output "[M21-PRIVILEGE] Total checks passed: $($passed.Count)"
exit 0