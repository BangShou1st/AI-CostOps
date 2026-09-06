<#
.SYNOPSIS
    M16 acceptance seed: synthetic governed data for exactly one Gateway
    request lifecycle (org, OPEN period, project, service identity, Gateway
    credential, MiMo-compatible mock route, ACTIVE provider credential,
    pricing version, Budget).

.DESCRIPTION
    Mirrors gateway GatewayTestFixture.seed over real MySQL through the DBA
    identity, pointed at the mock-provider base URL. All values are synthetic
    (suffix-tagged); the Gateway raw key and provider secret are random per
    run. Prints a JSON summary (ids + raw key + prefix) for the harness.
    Never prints secrets except the synthetic Gateway raw key, which the
    harness needs to call the API (acceptance-only credential).

.EXAMPLE
    .\scripts\m16\seed-m16-acceptance.ps1 -Suffix "b01" -MockBaseUrl "http://mock-provider:8089/v1"
#>
[CmdletBinding()]
param(
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13307,
    [string]$Database = "m16accept",
    [string]$MysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$Suffix = "run",
    [string]$MockBaseUrl = "http://mock-provider:8089/v1",
    [string]$BudgetTotal = "100.00000000"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$rootPassword = [Environment]::GetEnvironmentVariable("MYSQL_M16_ROOT_PASSWORD")
if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw "MYSQL_M16_ROOT_PASSWORD is not set." }
$kekBase64 = [Environment]::GetEnvironmentVariable("AICOSTOPS_PROVIDER_KEK_V1")
$hmacBase64 = [Environment]::GetEnvironmentVariable("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1")
if ([string]::IsNullOrWhiteSpace($kekBase64)) { throw "AICOSTOPS_PROVIDER_KEK_V1 is not set." }
if ([string]::IsNullOrWhiteSpace($hmacBase64)) { throw "AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1 is not set." }

function Invoke-Root([string]$Sql) {
    $env:MYSQL_PWD = $rootPassword
    try {
        $out = & $MysqlBin -h $MysqlHost -P $MysqlPort -u root -N -B $Database -e $Sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw "MySQL failed: $out" }
        if ($null -eq $out) { return "" }
        return (($out | Out-String)).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

# --- Random synthetic secrets (acceptance-only) ---
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
function New-Base64Url([int]$Bytes) {
    $b = New-Object byte[] $Bytes
    $rng.GetBytes($b)
    return ([Convert]::ToBase64String($b)).Replace("+", "-").Replace("/", "_").TrimEnd("=")
}
# Gateway key shape is frozen lowercase: aic_<12 [0-9a-hjkmnp-tv-z]>_<43 base64url>.
$crockford = "0123456789abcdefghjkmnpqrstvwxyz"
$prefixChars = -join (1..12 | ForEach-Object { $crockford[(Get-Random -Maximum 32)] })
$secretPart = New-Base64Url 32
$rawKey = "aic_${prefixChars}_${secretPart}"
$providerSecret = "sk-m16-acceptance-" + (New-Base64Url 12)

# HMAC-SHA256(secret) -> secret_digest (mirrors GatewayAuthenticationManager).
$hmacKey = [Convert]::FromBase64String($hmacBase64.Trim())
$hm = New-Object System.Security.Cryptography.HMACSHA256(,$hmacKey)
$digest = $hm.ComputeHash([Text.Encoding]::UTF8.GetBytes($secretPart))
$digestHex = -join ($digest | ForEach-Object { $_.ToString("x2") })

# AES-256-GCM encrypt provider secret with Control-Plane AAD contract.
$kek = [Convert]::FromBase64String($kekBase64.Trim())
$nonce = New-Object byte[] 12
$rng.GetBytes($nonce)
$aes = [System.Security.Cryptography.AesGcm]::new($kek)
$plain = [Text.Encoding]::UTF8.GetBytes($providerSecret)

function New-Org([string]$Slug, [string]$Name) {
    $orgId = Invoke-Root ("INSERT INTO organization(name,slug,status,created_at,updated_at) VALUES ('" + $Name + "','" + $Slug + "','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")
    return $orgId.Trim()
}
$orgSlug = "m16-$Suffix"
$orgId = New-Org $orgSlug ("M16 " + $Suffix)
$periodId = (Invoke-Root ("INSERT INTO billing_period(org_id,period_start,period_end,status,close_generation,version,created_at,updated_at) VALUES (" + $orgId + ",DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY),DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY),'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
$projectId = (Invoke-Root ("INSERT INTO project(org_id,code,name,status,created_at,updated_at) VALUES (" + $orgId + ",'m16-proj-" + $Suffix + "','M16 Acceptance Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
$svcId = (Invoke-Root ("INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at) VALUES (" + $orgId + ",'m16-svc-" + $Suffix + "','M16 Acceptance Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()

# Global catalogs reused idempotently (mirror GatewayTestFixture).
$modelId = (Invoke-Root "SELECT id FROM model_catalog WHERE model_key='m16-accept-chat';").Trim()
if (-not $modelId) {
    $modelId = (Invoke-Root "INSERT INTO model_catalog(model_key,name,status,capabilities_json,default_max_output_tokens,max_output_tokens,created_at,updated_at) VALUES ('m16-accept-chat','M16 Acceptance Model','ACTIVE',JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),8192,131072,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();").Trim()
}
$providerCode = "M16MOCK"
$hasCatalog = (Invoke-Root ("SELECT provider_code FROM provider_catalog WHERE provider_code='" + $providerCode + "';")).Trim()
if (-not $hasCatalog) {
    Invoke-Root ("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,capabilities_json,created_at,updated_at) VALUES ('" + $providerCode + "','M16 Mock','MIMO','" + $MockBaseUrl + "','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));") | Out-Null
} else {
    # Global catalog row is shared across seeds: always point it at this run's
    # mock upstream so a stale base_url from an earlier seed cannot poison routing.
    Invoke-Root ("UPDATE provider_catalog SET base_url='" + $MockBaseUrl + "', updated_at=UTC_TIMESTAMP(6) WHERE provider_code='" + $providerCode + "';") | Out-Null
}
$pmId = (Invoke-Root ("SELECT id FROM provider_model WHERE provider_code='" + $providerCode + "' AND model_id=" + $modelId + " AND provider_model_name='m16-mock-chat';")).Trim()
if (-not $pmId) {
    $pmId = (Invoke-Root ("INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,routing_eligible,capabilities_json,created_at,updated_at) VALUES ('" + $providerCode + "'," + $modelId + ",'m16-mock-chat','ACTIVE',TRUE,JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
}
$acctId = (Invoke-Root ("INSERT INTO provider_account(org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at) VALUES (" + $orgId + ",'" + $providerCode + "','M16 Mock Account','m16-acct-" + $Suffix + "','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()

# Encrypt: AAD = aicostops:v2:provider-credential:v1\0org\0acct\0API_KEY\01.
$aad = [Text.Encoding]::UTF8.GetBytes("aicostops:v2:provider-credential:v1`0" + $orgId + "`0" + $acctId + "`0API_KEY`01")
$ct = New-Object byte[] $plain.Length
$tag = New-Object byte[] 16
$aes.Encrypt($nonce, $plain, $ct, $tag, $aad)
$cipherBytes = $ct + $tag
$cipherHex = -join ($cipherBytes | ForEach-Object { $_.ToString("x2") })
$nonceHex = -join ($nonce | ForEach-Object { $_.ToString("x2") })
Invoke-Root ("INSERT INTO provider_credential(org_id,provider_account_id,credential_type,ciphertext,nonce,encryption_key_version,safe_label,status,created_at) VALUES (" + $orgId + "," + $acctId + ",'API_KEY',UNHEX('" + $cipherHex + "'),UNHEX('" + $nonceHex + "'),1,'m16-accept','ACTIVE',UTC_TIMESTAMP(6));") | Out-Null

$pvId = (Invoke-Root ("INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,currency,effective_from,effective_to,status,created_at,activated_at) VALUES (" + $orgId + "," + $acctId + "," + $pmId + ",1,'USD',DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 DAY),DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
Invoke-Root ("INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price) VALUES (" + $orgId + "," + $pvId + ",'INPUT_TOKEN',1000000,'30.00000000'),(" + $orgId + "," + $pvId + ",'OUTPUT_TOKEN',1000000,'60.00000000');") | Out-Null

$policyId = (Invoke-Root ("INSERT INTO routing_policy(org_id,project_id,model_id,version,status,created_at,activated_at) VALUES (" + $orgId + ",NULL," + $modelId + ",1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
Invoke-Root ("INSERT INTO routing_policy_candidate(org_id,routing_policy_id,provider_account_id,provider_model_id,priority,status,created_at) VALUES (" + $orgId + "," + $policyId + "," + $acctId + "," + $pmId + ",0,'ACTIVE',UTC_TIMESTAMP(6));") | Out-Null

$credId = (Invoke-Root ("INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,service_identity_id,project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,status,created_at,updated_at) VALUES (" + $orgId + ",'" + $prefixChars + "',UNHEX('" + $digestHex + "'),1,'SERVICE'," + $svcId + "," + $projectId + ",'PROJECT'," + $projectId + ",'OPTIONAL','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
Invoke-Root ("INSERT INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at) VALUES (" + $credId + "," + $orgId + "," + $modelId + ",'ACTIVE',UTC_TIMESTAMP(6));") | Out-Null
$budgetId = (Invoke-Root ("INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,total_amount,actual_amount,committed_amount,status,version,created_at,updated_at) VALUES (" + $orgId + "," + $periodId + ",'PROJECT'," + $projectId + ",'USD','" + $BudgetTotal + "',0,0,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()

[ordered]@{
    org_id      = $orgId
    period_id   = $periodId
    project_id  = $projectId
    credential  = $credId
    budget_id   = $budgetId
    model_key   = "m16-accept-chat"
    prefix      = $prefixChars
    raw_key     = $rawKey
} | ConvertTo-Json -Compress
