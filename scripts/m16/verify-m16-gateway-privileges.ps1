<#
.SYNOPSIS
    M16 Task 4 gate: prove the Gateway runtime DB identity is least-privilege
    enforced by MySQL itself (not just Java architecture assertions).

.DESCRIPTION
    Connects to the isolated M16 acceptance MySQL twice:
      - as the DBA/root identity to read SHOW GRANTS;
      - as the Gateway runtime identity to attempt a positive/negative matrix.

    Positive (must succeed):
      SELECT on budget / billing_period / ledger_posting;
      SELECT ... FOR UPDATE on budget, billing_period, gateway_request
      (locking reads the Gateway mappers require);
      INSERT + UPDATE on gateway_request (Gateway-owned runtime write).

    Negative (must be denied by MySQL with ERROR 1142):
      UPDATE budget actual, billing_period close, INSERT ledger_posting /
      ledger_entry / gateway_settlement, UPDATE provider_credential,
      DELETE gateway_usage_fact, DDL CREATE/DROP.

    Nothing is committed: the positive INSERT/UPDATE runs inside a rolled-back
    transaction. Forbidden statements are rejected by the server before any
    effect. Synthetic fingerprint values only; no secrets are printed.

    Exits non-zero with M16_PRIVILEGE_RED on any violation.

.EXAMPLE
    .\scripts\m16\verify-m16-gateway-privileges.ps1 `
      -MysqlHost 127.0.0.1 -MysqlPort 13307 -Database m16accept `
      -GatewayUser gw_accept
    # Passwords via MYSQL_M16_ROOT_PASSWORD / MYSQL_M16_GATEWAY_PASSWORD env.
#>
[CmdletBinding()]
param(
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13307,
    [string]$Database = "m16accept",
    [string]$GatewayUser = "gw_accept",
    [string]$MysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$failures = [System.Collections.ArrayList]::new()
function Add-Failure([string]$Message) {
    Write-Output "[M16-PRIVILEGE-RED] $Message"
    $failures.Add($Message) | Out-Null
}

$rootPassword = [Environment]::GetEnvironmentVariable("MYSQL_M16_ROOT_PASSWORD")
$gatewayPassword = [Environment]::GetEnvironmentVariable("MYSQL_M16_GATEWAY_PASSWORD")
if ([string]::IsNullOrWhiteSpace($rootPassword)) {
    Add-Failure "MYSQL_M16_ROOT_PASSWORD is not set."
}
if ([string]::IsNullOrWhiteSpace($gatewayPassword)) {
    Add-Failure "MYSQL_M16_GATEWAY_PASSWORD is not set."
}
if ($failures.Count -gt 0) {
    exit 1
}

function Invoke-Root([string]$Sql) {
    $env:MYSQL_PWD = $rootPassword
    try {
        return (& $MysqlBin -h $MysqlHost -P $MysqlPort -u root $Database -e $Sql 2>&1 | Out-String)
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Invoke-Gateway([string]$Sql) {
    $env:MYSQL_PWD = $gatewayPassword
    try {
        return (& $MysqlBin -h $MysqlHost -P $MysqlPort -u $GatewayUser $Database -e $Sql 2>&1 | Out-String)
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

# 0. Grants must show the least-privilege shape: no GRANT OPTION, no DELETE.
$grants = Invoke-Root "SHOW GRANTS FOR '$GatewayUser'@'%';"
if ($grants -match "GRANT OPTION") {
    Add-Failure "gateway identity must not hold GRANT OPTION."
} else {
    Write-Output "[M16-PRIVILEGE] PASS: no GRANT OPTION."
}
if ($grants -match "(?i)\bDELETE\b") {
    Add-Failure "gateway identity must not hold DELETE."
} else {
    Write-Output "[M16-PRIVILEGE] PASS: no DELETE privilege."
}
$ownedTables = @("budget_reservation", "gateway_request", "gateway_route_attempt",
    "gateway_usage_fact", "gateway_usage_dimension")
foreach ($table in $ownedTables) {
    if ($grants -notmatch [regex]::Escape($table)) {
        Add-Failure ("expected Gateway-owned write grant on " + $table + ".")
    }
}
if ($failures.Count -eq 0) {
    Write-Output "[M16-PRIVILEGE] PASS: Gateway-owned write grants present (5 tables)."
}

# 1. Positive reads.
$readOk = $true
foreach ($table in @("budget", "billing_period", "ledger_posting")) {
    $out = Invoke-Gateway "SELECT COUNT(*) AS t FROM $table;"
    if ($out -match "ERROR") {
        Add-Failure ("positive SELECT on " + $table + ": " + $out)
        $readOk = $false
    }
}
if ($readOk) {
    Write-Output "[M16-PRIVILEGE] PASS: positive SELECT on budget/billing_period/ledger_posting."
}

# 2. Positive locking reads (mapper-required FOR UPDATE).
$lockCases = @(
    @{ Label = "budget"; Sql = "START TRANSACTION; SELECT id FROM budget WHERE id=999999 FOR UPDATE; ROLLBACK;" },
    @{ Label = "billing_period"; Sql = "START TRANSACTION; SELECT id FROM billing_period WHERE id=999999 FOR UPDATE; ROLLBACK;" },
    @{ Label = "gateway_request"; Sql = "START TRANSACTION; SELECT id FROM gateway_request LIMIT 1 FOR UPDATE; ROLLBACK;" }
)
$lockOk = $true
foreach ($case in $lockCases) {
    $out = Invoke-Gateway $case.Sql
    if ($out -match "ERROR") {
        Add-Failure ("positive locking read on " + $case.Label + ": " + $out)
        $lockOk = $false
    }
}
if ($lockOk) {
    Write-Output "[M16-PRIVILEGE] PASS: positive SELECT ... FOR UPDATE on budget/billing_period/gateway_request."
}

# 3. Positive Gateway-owned write inside a rolled-back transaction.
$writeSql = "START TRANSACTION; " +
    "INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,service_identity_id,project_id,financial_scope_type,financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,request_hmac_version,state,created_at,validated_at,updated_at) " +
    "VALUES (1,'m16priv00000000000000000000000000001',1,'SERVICE',1,1,'PROJECT',1,1,'CHAT_COMPLETIONS',UNHEX(SHA2('m16-priv-k',256)),UNHEX(SHA2('m16-priv-fp',256)),1,'VALIDATED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); " +
    "UPDATE gateway_request SET state='RESERVED' WHERE public_request_id='m16priv00000000000000000000000000001'; " +
    "ROLLBACK;"
$out = Invoke-Gateway $writeSql
if ($out -match "ERROR") {
    Add-Failure ("positive gateway_request INSERT+UPDATE: " + $out)
} else {
    Write-Output "[M16-PRIVILEGE] PASS: positive gateway_request INSERT+UPDATE (rolled back)."
}

# 4. Negative matrix: every forbidden mutation must be denied with ERROR 1142.
$negative = @(
    @{ Label = "budget UPDATE"; Sql = "UPDATE budget SET actual_amount=1 WHERE id=999999;" },
    @{ Label = "billing_period close"; Sql = "UPDATE billing_period SET status='CLOSED' WHERE id=999999;" },
    @{ Label = "ledger_posting INSERT"; Sql = "INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,billing_period_id,status,created_at) VALUES (1,'m16-priv-evil','MANUAL',1,1,'DRAFT',UTC_TIMESTAMP(6));" },
    @{ Label = "ledger_entry INSERT"; Sql = "INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,currency,created_at) VALUES (1,1,0,'DEBIT','1.00','USD',UTC_TIMESTAMP(6));" },
    @{ Label = "gateway_settlement INSERT"; Sql = "INSERT INTO gateway_settlement(org_id,settlement_key,request_id,status,created_at) VALUES (1,'m16-priv-evil',1,'PENDING',UTC_TIMESTAMP(6));" },
    @{ Label = "provider_credential UPDATE"; Sql = "UPDATE provider_credential SET status='REVOKED' WHERE id=999999;" },
    @{ Label = "gateway_usage_fact DELETE"; Sql = "DELETE FROM gateway_usage_fact WHERE id=999999;" },
    @{ Label = "DDL CREATE"; Sql = "CREATE TABLE m16_priv_evil(id INT);" },
    @{ Label = "DDL DROP"; Sql = "DROP TABLE IF EXISTS m16_priv_evil;" }
)
foreach ($case in $negative) {
    $out = Invoke-Gateway $case.Sql
    if ($out -match "ERROR 1142") {
        Write-Output ("[M16-PRIVILEGE] PASS: denied: " + $case.Label + ".")
    } else {
        Add-Failure ("forbidden statement was NOT denied [" + $case.Label + "]: " + $out)
    }
}

if ($failures.Count -gt 0) {
    Write-Output ("[M16-PRIVILEGE-RED] M16_PRIVILEGE_RED (" + $failures.Count + " violation(s))")
    exit 1
}
Write-Output "[M16-PRIVILEGE] M16_PRIVILEGE_GREEN: least privilege enforced by MySQL."
exit 0
