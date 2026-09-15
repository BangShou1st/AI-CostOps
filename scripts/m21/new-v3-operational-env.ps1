<#
.SYNOPSIS
    M21 V3 Operational Environment Bootstrap Helper.

.DESCRIPTION
    Generates a complete local V3 operational environment file by reading
    .env.example as a base template and overriding sensitive/localized values
    with cryptographically secure RNG (.NET RandomNumberGenerator).

    Output: .env.m21.local (gitignored)
    Includes ALL root compose required vars + Gateway operational vars.

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

# Resolve repo root: PSScriptRoot is <repo>/scripts/m21/, go up two levels
$repoRoot = if ($PSScriptRoot) {
    Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
} else {
    # Fallback: use git root
    $gitRoot = git rev-parse --show-toplevel 2>$null
    if ($gitRoot) { $gitRoot } else { $PWD.Path }
}

$exampleFile = Join-Path $repoRoot ".env.example"

if (-not (Test-Path $exampleFile)) {
    Write-Error "[M21-ENV] .env.example not found at $exampleFile"
    exit 1
}

# Step 1: Read .env.example as base template
$template = Get-Content $exampleFile -Raw

# Step 2: CSPRNG helpers
function New-CspRandomBase64([int]$ByteLength) {
    $bytes = New-Object byte[] $ByteLength
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
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
    } finally { $rng.Dispose() }
    return $result.ToString()
}

function New-Base64Url([int]$ByteLength) {
    $bytes = New-Object byte[] $ByteLength
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes) -replace '\+', '-' -replace '/', '_' -replace '=', ''
}

# Step 3: Generate secrets
$credHmac = New-CspRandomBase64 32
$requestHmac = New-CspRandomBase64 32
$kek = New-CspRandomBase64 32

# Gateway raw key: aic_<12 Crockford-Base32>_<43 Base64URL>
$prefix = New-CrockfordBase32 12
$secretPart = New-Base64Url 32
$gatewayRawKey = "aic_${prefix}_${secretPart}"

# Validate shape against GatewayApiKeyParser regex
if ($gatewayRawKey -notmatch '^aic_[0-9a-hjkmnp-tv-z]{12}_[A-Za-z0-9_-]{43}$') {
    Write-Error "[M21-ENV] Generated Gateway raw key does not match parser regex"
    exit 1
}

$mimoKey = "sk-m21-local-" + (New-Base64Url 24)
$gwUser = "gw_m21"
$gwPassword = New-Base64Url 24

# Step 4: Generate root compose passwords
$mysqlPassword = New-Base64Url 24
$mysqlRootPassword = New-Base64Url 32
$redisPassword = New-Base64Url 24
$minioRootPassword = New-Base64Url 24
$jwtSigningKey = New-Base64Url 48
$devBootstrapPassword = New-Base64Url 24

# Step 5: Override template values
$output = $template

# Root compose variables
$output = $output -replace '(?m)^MYSQL_DATABASE=.*$', "MYSQL_DATABASE=aicostops"
$output = $output -replace '(?m)^MYSQL_USER=.*$', "MYSQL_USER=aicostops"
$output = $output -replace '(?m)^MYSQL_PASSWORD=.*$', "MYSQL_PASSWORD=$mysqlPassword"
$output = $output -replace '(?m)^MYSQL_ROOT_PASSWORD=.*$', "MYSQL_ROOT_PASSWORD=$mysqlRootPassword"
$output = $output -replace '(?m)^REDIS_PASSWORD=.*$', "REDIS_PASSWORD=$redisPassword"
$output = $output -replace '(?m)^MINIO_ROOT_USER=.*$', "MINIO_ROOT_USER=aicostops"
$output = $output -replace '(?m)^MINIO_ROOT_PASSWORD=.*$', "MINIO_ROOT_PASSWORD=$minioRootPassword"
$output = $output -replace '(?m)^MINIO_BUCKET=.*$', "MINIO_BUCKET=aicostops-evidence"
$output = $output -replace '(?m)^AICOSTOPS_JWT_SIGNING_KEY=.*$', "AICOSTOPS_JWT_SIGNING_KEY=$jwtSigningKey"

# Gateway variables (uncomment and set)
$output = $output -replace '(?m)^#\s*AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1=.*$', "AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1=$credHmac"
$output = $output -replace '(?m)^#\s*AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1=.*$', "AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1=$requestHmac"
$output = $output -replace '(?m)^#\s*AICOSTOPS_PROVIDER_KEK_V1=.*$', "AICOSTOPS_PROVIDER_KEK_V1=$kek"
$output = $output -replace '(?m)^#\s*AICOSTOPS_GATEWAY_DEV_RAW_KEY=.*$', "AICOSTOPS_GATEWAY_DEV_RAW_KEY=$gatewayRawKey"
$output = $output -replace '(?m)^#\s*AICOSTOPS_MIMO_API_KEY=.*$', "AICOSTOPS_MIMO_API_KEY=$mimoKey"

# Ensure Gateway dev bootstrap enabled
$output = $output -replace '(?m)^AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=.*$', "AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true"

# Step 6: Append M21-specific section
$m21Section = @"

# ============================================================
# M21 V3 Operational Variables (auto-generated, gitignored)
# Generated: $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssZ')
# ============================================================
MYSQL_GATEWAY_USER=$gwUser
MYSQL_GATEWAY_PASSWORD=$gwPassword
AICOSTOPS_DEV_BOOTSTRAP_PASSWORD=$devBootstrapPassword
"@

$output = $output.TrimEnd() + "`n" + $m21Section

# Step 7: Write output to repo root
$outputPath = Join-Path $repoRoot $EnvFile
[System.IO.File]::WriteAllText($outputPath, $output)

# Step 8: Verify
$written = Get-Content $outputPath -Raw
$requiredVars = @(
    'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD',
    'REDIS_PASSWORD', 'MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD', 'MINIO_BUCKET',
    'AICOSTOPS_JWT_SIGNING_KEY', 'MYSQL_GATEWAY_USER', 'MYSQL_GATEWAY_PASSWORD',
    'AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1', 'AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1',
    'AICOSTOPS_PROVIDER_KEK_V1', 'AICOSTOPS_GATEWAY_DEV_RAW_KEY', 'AICOSTOPS_MIMO_API_KEY'
)

$missing = @()
foreach ($var in $requiredVars) {
    if ($written -notmatch "(?m)^$var=.+$") {
        $missing += $var
    }
}

if ($missing.Count -gt 0) {
    Write-Error "[M21-ENV] Missing required variables: $($missing -join ', ')"
    exit 1
}

Write-Output "[M21-ENV] Runtime secrets generated and written to $outputPath"
Write-Output "[M21-ENV] Repo root: $repoRoot"
Write-Output "[M21-ENV] Gateway DB user: $gwUser"
Write-Output "[M21-ENV] Gateway raw key shape: aic_<12>_<43>"
Write-Output "[M21-ENV] CSPRNG: .NET RandomNumberGenerator"
Write-Output "[M21-ENV] All required variables present: $($requiredVars.Count)"
Write-Output "[M21-ENV] M21_ENV_GENERATED_PASS"