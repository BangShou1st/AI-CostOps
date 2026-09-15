<#
.SYNOPSIS
    M21 V3 Operational Environment — idempotent self-contained bootstrap.

.DESCRIPTION
    Generates all runtime-only secrets for local V3 operational validation
    using cryptographically secure RNG (.NET RandomNumberGenerator).

    Reads .env.example as base template, overrides sensitive values.

    Idempotent: safe to run multiple times. Each run overwrites the file.

.EXAMPLE
    pwsh -File scripts/m21/bootstrap-v3-operational.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Write-Output "[M21] Self-contained bootstrap starting..."

# Step 1: Generate .env.m21.local
Write-Output "[M21] Generating runtime environment..."
& $PSScriptRoot/new-v3-operational-env.ps1 -EnvFile .env.m21.local
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Step 2: Source the env file into current process
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $repoRoot ".env.m21.local"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^(\w+)=(.*)$') {
            $name = $Matches[1]
            $value = $Matches[2]
            [Environment]::SetEnvironmentVariable($name, $value)
        }
    }
    Write-Output "[M21] Environment variables loaded into current process."
}

Write-Output "[M21] Bootstrap complete. All runtime secrets generated in .env.m21.local."
Write-Output "[M21] Next: start Compose topology, provision Gateway DB, seed route."