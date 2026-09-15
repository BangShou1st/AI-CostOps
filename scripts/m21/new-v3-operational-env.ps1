<#
.SYNOPSIS
    M21 V3 Operational Environment Bootstrap Helper.

.DESCRIPTION
    Generates all runtime-only secrets for local V3 operational validation
    using cryptographically secure RNG (.NET RandomNumberGenerator).

    Outputs written only to gitignored .env.m21.local file.

.PARAMETER EnvFile
    Output environment file path. Default: .env.m21.local

.EXAMPLE
    pwsh -File scripts/m21/new-v3-operational-env.ps1
    pwsh -File scripts/m21/new-v3-operational-env.ps1 -EnvFile .env.m21.local
#>
[CmdletBinding()]
param(
    [string]$EnvFile = ".env.m21.local"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Write-Output "[M21-ENV] Starting V3 operational environment generation..."

# Step 1: Generate cryptographic random values using CSPRNG
function New-CspRandomBase64([int]$ByteLength) {
    $bytes = New-Object byte[] $ByteLength
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

function New-CrockfordBase32([int]$Length) {
    $alphabet = '0123456789abcdefghjkmnpqrstvwxyz'
    $result = New-Object System.Text.StringBuilder $Length
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
        for ($i = 0; $i -lt $Length; $i++) {
            [void]$result.Append($alphabet[$bytes[$i] % $alphabet.Length])
        }
    } finally {
        $rng.Dispose()
    }
    return $result.ToString()
}

function New-Base64Url([int]$ByteLength) {
    $bytes = New-Object byte[] $ByteLength
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes) -replace '\+', '-' -replace '/', '_' -replace '=', ''
}

# Step 2: Generate HMAC/KEK keys (32 random bytes, Base64)
$credHmac = New-CspRandomBase64 32
$requestHmac = New-CspRandomBase64 32
$kek = New-CspRandomBase64 32

# Step 3: Generate Gateway raw key: aic_<12 Crockford-Base32>_<43 Base64URL>
$prefix = New-CrockfordBase32 12
$secretPart = New-Base64Url 32
$gatewayRawKey = "aic_${prefix}_${secretPart}"

# Step 4: Generate synthetic MiMo API key
$mimoKey = "sk-m21-local-" + (New-Base64Url 24)

# Step 5: Generate Gateway DB credentials
$gwUser = "gw_m21"
$gwPassword = New-Base64Url 24

# Step 6: Write to .env.m21.local (gitignored)
$envContent = @"
# M21 V3 Operational Environment (local-only, auto-generated)
# Generated: $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssZ')
# DO NOT COMMIT. This file is gitignored.

# Database
MYSQL_GATEWAY_USER=$gwUser
MYSQL_GATEWAY_PASSWORD=$gwPassword

# Gateway HMAC / KEK
AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1=$credHmac
AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1=$requestHmac
AICOSTOPS_PROVIDER_KEK_V1=$kek

# Gateway Dev Bootstrap
AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true
AICOSTOPS_GATEWAY_DEV_RAW_KEY=$gatewayRawKey

# Synthetic Provider credential (local-only, NOT a real Provider secret)
AICOSTOPS_MIMO_API_KEY=$mimoKey
"@

[System.IO.File]::WriteAllText($ExecutionContext.SessionState.Path.Combine($PWD.Path, $EnvFile), $envContent)

Write-Output "[M21-ENV] Runtime secrets generated and written to $EnvFile"
Write-Output "[M21-ENV] Gateway DB user: $gwUser"
Write-Output "[M21-ENV] Gateway raw key shape: aic_<12>_<43>"
Write-Output "[M21-ENV] M21_ENV_GENERATED_PASS"