<#
.SYNOPSIS
    M16 Browser R5 fixture: lightweight precondition-only setup.
.DESCRIPTION
    Creates the R5 org, OPEN billing period, other-org, M16MOCK provider
    account, and ACTIVE provider credential. Does NOT create Project, Budget,
    Service Identity, Gateway Credential, Pricing Version, or Routing Policy
    — those belong to Browser R5.

    Users must be registered through the governed registration API (not
    direct DB insert). Role assignment may use DB setup AFTER registration.

    Prints JSON summary with org/period/other-org IDs.
.EXAMPLE
    .\scripts\m16\seed-m16-browser-r5-fixture.ps1 -Suffix "r5-20260909120000"
#>
[CmdletBinding()]
param(
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13307,
    [string]$Database = "m16accept",
    [string]$MysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$Suffix = "r5",
    [string]$MockBaseUrl = "http://m16-mock-provider:8089/v1"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$rootPassword = [Environment]::GetEnvironmentVariable("MYSQL_M16_ROOT_PASSWORD")
if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw "MYSQL_M16_ROOT_PASSWORD is not set." }
$kekBase64 = [Environment]::GetEnvironmentVariable("AICOSTOPS_PROVIDER_KEK_V1")
if ([string]::IsNullOrWhiteSpace($kekBase64)) { throw "AICOSTOPS_PROVIDER_KEK_V1 is not set." }

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

# --- Random synthetic provider secret ---
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
function New-Base64Url([int]$Bytes) {
    $b = New-Object byte[] $Bytes
    $rng.GetBytes($b)
    return ([Convert]::ToBase64String($b)).Replace("+", "-").Replace("/", "_").TrimEnd("=")
}
$providerSecret = "sk-m16-acceptance-" + (New-Base64Url 12)

# --- Create R5 org ---
$orgSlug = "m16-uat-browser-" + $Suffix
$orgId = (Invoke-Root ("INSERT INTO organization(name,slug,status,created_at,updated_at) VALUES ('M16 Browser R5','" + $orgSlug + "','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
Write-Output "R5 org: id=$orgId slug=$orgSlug"

# --- OPEN billing period ---
$periodId = (Invoke-Root ("INSERT INTO billing_period(org_id,period_start,period_end,status,close_generation,version,created_at,updated_at) VALUES (" + $orgId + ",DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY),DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 90 DAY),'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
Write-Output "R5 period: id=$periodId status=OPEN"

# --- Other-org for cross-org checks ---
$otherSlug = "m16-uat-browser-" + $Suffix + "-other"
$otherOrgId = (Invoke-Root ("INSERT INTO organization(name,slug,status,created_at,updated_at) VALUES ('M16 Browser R5 Other','" + $otherSlug + "','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
$otherPeriodId = (Invoke-Root ("INSERT INTO billing_period(org_id,period_start,period_end,status,close_generation,version,created_at,updated_at) VALUES (" + $otherOrgId + ",DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY),DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 90 DAY),'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
Write-Output "R5 other-org: id=$otherOrgId slug=$otherSlug period=$otherPeriodId"

# --- Global catalog prerequisites (idempotent) ---
$modelId = (Invoke-Root "SELECT id FROM model_catalog WHERE model_key='m16-accept-chat';").Trim()
if (-not $modelId) {
    $modelId = (Invoke-Root "INSERT INTO model_catalog(model_key,name,status,capabilities_json,default_max_output_tokens,max_output_tokens,created_at,updated_at) VALUES ('m16-accept-chat','M16 Acceptance Model','ACTIVE',JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),8192,131072,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();").Trim()
}
$providerCode = "M16MOCK"
$hasCatalog = (Invoke-Root ("SELECT provider_code FROM provider_catalog WHERE provider_code='" + $providerCode + "';")).Trim()
if (-not $hasCatalog) {
    Invoke-Root ("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,capabilities_json,created_at,updated_at) VALUES ('" + $providerCode + "','M16 Mock','MIMO','" + $MockBaseUrl + "','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));") | Out-Null
} else {
    Invoke-Root ("UPDATE provider_catalog SET base_url='" + $MockBaseUrl + "', updated_at=UTC_TIMESTAMP(6) WHERE provider_code='" + $providerCode + "';") | Out-Null
}
$pmId = (Invoke-Root ("SELECT id FROM provider_model WHERE provider_code='" + $providerCode + "' AND model_id=" + $modelId + " AND provider_model_name='m16-mock-chat';")).Trim()
if (-not $pmId) {
    $pmId = (Invoke-Root ("INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,routing_eligible,capabilities_json,created_at,updated_at) VALUES ('" + $providerCode + "'," + $modelId + ",'m16-mock-chat','ACTIVE',TRUE,JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
}
Write-Output "Global catalog: model_id=$modelId provider_model_id=$pmId"

# --- M16MOCK provider account in R5 org ---
$acctId = (Invoke-Root ("INSERT INTO provider_account(org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at) VALUES (" + $orgId + ",'" + $providerCode + "','M16 Mock Account','m16-acct-" + $Suffix + "','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID();")).Trim()
Write-Output "R5 provider account: id=$acctId"

# --- ACTIVE provider credential (encrypted with acceptance KEK) ---
$kek = [Convert]::FromBase64String($kekBase64.Trim())
$nonce = New-Object byte[] 12
$rng.GetBytes($nonce)
$aes = [System.Security.Cryptography.AesGcm]::new($kek)
$plain = [Text.Encoding]::UTF8.GetBytes($providerSecret)
$aad = [Text.Encoding]::UTF8.GetBytes("aicostops:v2:provider-credential:v1`0" + $orgId + "`0" + $acctId + "`0API_KEY`01")
$ct = New-Object byte[] $plain.Length
$tag = New-Object byte[] 16
$aes.Encrypt($nonce, $plain, $ct, $tag, $aad)
$cipherHex = -join (($ct + $tag) | ForEach-Object { $_.ToString("x2") })
$nonceHex = -join ($nonce | ForEach-Object { $_.ToString("x2") })
Invoke-Root ("INSERT INTO provider_credential(org_id,provider_account_id,credential_type,ciphertext,nonce,encryption_key_version,safe_label,status,created_at) VALUES (" + $orgId + "," + $acctId + ",'API_KEY',UNHEX('" + $cipherHex + "'),UNHEX('" + $nonceHex + "'),1,'m16-r5-accept','ACTIVE',UTC_TIMESTAMP(6));") | Out-Null
Write-Output "R5 provider credential: ACTIVE (encrypted)"

# --- JSON summary ---
[ordered]@{
    org_id       = $orgId
    org_slug     = $orgSlug
    period_id    = $periodId
    other_org_id = $otherOrgId
    other_slug   = $otherSlug
    other_period_id = $otherPeriodId
    acct_id      = $acctId
    provider_code = $providerCode
} | ConvertTo-Json -Compress
