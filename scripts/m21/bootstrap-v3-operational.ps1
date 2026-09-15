<#
.SYNOPSIS
    M21 V3 Operational Environment — idempotent self-contained bootstrap.

.DESCRIPTION
    Generates .env.m21.local (gitignored) in the repository root.

    NOTE: This script only generates the env file. It does NOT persistently
    modify the calling shell's environment. Each downstream script
    (provision, seed, smoke, verify) imports .env.m21.local itself.

.EXAMPLE
    pwsh -File scripts/m21/bootstrap-v3-operational.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Write-Output "[M21] Self-contained bootstrap starting..."

# Resolve repo root
$repoRoot = if ($PSScriptRoot) {
    Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
} else {
    $gitRoot = git rev-parse --show-toplevel 2>$null
    if ($gitRoot) { $gitRoot } else { $PWD.Path }
}

# Step 1: Generate .env.m21.local
Write-Output "[M21] Generating runtime environment..."
& "$repoRoot/scripts/m21/new-v3-operational-env.ps1" -EnvFile ".env.m21.local"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Step 2: Verify env file exists
$envFile = Join-Path $repoRoot ".env.m21.local"
if (Test-Path $envFile) {
    $content = Get-Content $envFile -Raw
    $varCount = ([regex]::Matches($content, '(?m)^\w+=')).Count
    Write-Output "[M21] Generated .env.m21.local with $varCount variables"
} else {
    Write-Error "[M21] .env.m21.local was not created"
    exit 1
}

Write-Output "[M21] Bootstrap complete. Each M21 script auto-imports .env.m21.local."
Write-Output "[M21] Next steps: docker compose up, then run provision/seed/smoke scripts."
Write-Output "[M21] Example:"
Write-Output "  pwsh -File scripts/m21/provision-gateway-db.ps1 -ComposeProject aicostops-m21-final-r3"
Write-Output "  pwsh -File scripts/m21/seed-v3-operational.ps1 -ComposeProject aicostops-m21-final-r3"
Write-Output "  pwsh -File scripts/m21/invoke-v3-operational-smoke.ps1 -ComposeProject aicostops-m21-final-r3"