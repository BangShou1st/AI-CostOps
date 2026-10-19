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
    # Root password comes from the mysql container's own environment
    # (MYSQL_ROOT_PASSWORD); host env does not propagate through `compose exec`.
    $result = docker compose @composeArgs exec -T mysql sh -c "mysql -u root -p`"`$MYSQL_ROOT_PASSWORD`" $Database -e `"$Sql`" 2>&1"
    return ($result | Out-String).Trim()
}

function Invoke-Gateway([string]$Sql) {
    # The Gateway password is the account's own secret (never root) and never
    # appears on the host command line: it is fed to the container on stdin and
    # consumed by `read`. PowerShell terminates piped stdin with CRLF, so the
    # trailing CR is stripped before it becomes MYSQL_PWD.
    $cmd = "read -r pw; MYSQL_PWD=`"`$(printf '%s' `"`$pw`" | tr -d '\r')`" mysql -u $GatewayUser $Database -e `"$Sql`" 2>&1"
    $result = $env:MYSQL_GATEWAY_PASSWORD | docker compose @composeArgs exec -T mysql sh -c $cmd
    return ($result | Out-String).Trim()
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

# MySQL 8.4 requires LOCK TABLES for locking reads (`SELECT ... FOR UPDATE`).
if ($grants -notmatch "(?i)\bLOCK\s+TABLES\b") {
    Add-Failure "Gateway identity must hold LOCK TABLES (required for SELECT ... FOR UPDATE on MySQL 8.4)."
} else {
    Add-Pass "LOCK TABLES present (locking reads enabled)."
}

# The Gateway must never be able to mutate budget / billing_period contents.
$forbiddenBudgetGrants = @()
foreach ($line in ($grants -split "`n")) {
    if ($line -match "(?i)\bbudget\b|\bbilling_period\b") {
        if ($line -match "(?i)\bUPDATE\b|\bDELETE\b|\bALL\b") { $forbiddenBudgetGrants += $line.Trim() }
    }
}
if ($forbiddenBudgetGrants.Count -gt 0) {
    Add-Failure ("Gateway identity must not hold UPDATE/DELETE/ALL on budget/billing_period: " + ($forbiddenBudgetGrants -join " | "))
} else {
    Add-Pass "No UPDATE/DELETE on budget/billing_period."
}

# AdvisorInferenceWorker has a deliberately narrow table-level grant set.
$expectedAdvisorPrivileges = @(
    @{ Table = "advisor_inference_job"; Privileges = @("SELECT", "UPDATE") }
    @{ Table = "advisor_inference_attempt"; Privileges = @("SELECT", "UPDATE") }
    @{ Table = "advisor_explanation"; Privileges = @("INSERT") }
    @{ Table = "advisor_profile"; Privileges = @("SELECT") }
    @{ Table = "advisor_evidence_snapshot"; Privileges = @("SELECT") }
)
$grantLines = @($grants -split "`n")
foreach ($case in $expectedAdvisorPrivileges) {
    $line = $grantLines | Where-Object { $_ -match "(?i)\b$($case.Table)\b" } | Select-Object -First 1
    $privilegeMatch = if ($line) { [regex]::Match($line, "(?i)^GRANT\s+(.+?)\s+ON\b") } else { $null }
    if (-not $privilegeMatch -or -not $privilegeMatch.Success) {
        Add-Failure "Missing table grant for $($case.Table)."
        continue
    }
    $actual = @($privilegeMatch.Groups[1].Value.Split(',') | ForEach-Object { $_.Trim().ToUpperInvariant() } | Sort-Object)
    $expected = @($case.Privileges | ForEach-Object { $_.ToUpperInvariant() } | Sort-Object)
    if (($actual -join ',') -ne ($expected -join ',')) {
        Add-Failure "Unexpected $($case.Table) privileges: $($actual -join ', ') (expected $($expected -join ', '))."
    } else {
        Add-Pass "$($case.Table) has only $($expected -join ', ') privilege(s)."
    }
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
    @{ Table = "advisor_inference_job"; Sql = "SELECT COUNT(*) FROM advisor_inference_job;" },
    @{ Table = "advisor_profile"; Sql = "SELECT COUNT(*) FROM advisor_profile;" },
    @{ Table = "advisor_evidence_snapshot"; Sql = "SELECT COUNT(*) FROM advisor_evidence_snapshot;" },
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
    @{ Label = "advisor_inference_job"; Sql = "START TRANSACTION; SELECT id FROM advisor_inference_job WHERE id=999999 FOR UPDATE SKIP LOCKED; ROLLBACK;" }
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

# Worker DML probes use impossible keys, so they exercise MySQL's privilege
# checks without changing any application rows. Explanation INSERT is proven
# by the expected FK rejection after the INSERT privilege check.
$advisorWriteChecks = @(
    @{ Label = "advisor_inference_job UPDATE"; Sql = "UPDATE advisor_inference_job SET status='PENDING' WHERE id=-1;" },
    @{ Label = "advisor_inference_attempt UPDATE"; Sql = "UPDATE advisor_inference_attempt SET status='FAILED',failure_code='PRIVILEGE_PROBE' WHERE org_id=-1 AND job_id=-1 AND attempt_no=-1 AND status IN ('PENDING','RUNNING');" }
)
foreach ($case in $advisorWriteChecks) {
    $out = Invoke-Gateway $case.Sql
    if ($out -match "ERROR") {
        Add-Failure "$($case.Label) failed: $out"
    } else {
        Add-Pass "$($case.Label) succeeds without matching rows."
    }
}
$explanationProbe = "INSERT INTO advisor_explanation(org_id,job_id,attempt_no,schema_version,summary,drivers_explanation,recommended_actions_json,warnings_json,fact_reference_ids_json,created_at) VALUES(-1,-1,1,1,'privilege probe','privilege probe',JSON_ARRAY(),JSON_ARRAY(),JSON_ARRAY(),UTC_TIMESTAMP(6));"
$explanationOut = Invoke-Gateway $explanationProbe
if ($explanationOut -match "ERROR 1452") {
    Add-Pass "advisor_explanation INSERT reaches the expected foreign-key fence."
} else {
    Add-Failure "advisor_explanation INSERT did not reach the expected foreign-key fence: $explanationOut"
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
