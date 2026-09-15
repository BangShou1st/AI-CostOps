<#
.SYNOPSIS
    Shared helper: locate repo root for M21 operational scripts.

.DESCRIPTION
    Resolves the repository root from any script location.
    All M21 scripts dot-source this for consistent path resolution.

.OUTPUTS
    PSCustomObject with:
    - RepoRoot: absolute path to repository root
    - EnvFile: path to .env.m21.local
    - StateFile: path to .m21-operational-state.json
    - ExampleFile: path to .env.example
#>
function Get-M21RepoPaths {
    [CmdletBinding()]
    param()

    # PSScriptRoot is <repo>/scripts/m21/ for all M21 scripts
    # Go up two levels to reach <repo>/
    $repoRoot = if ($PSScriptRoot) {
        Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    } else {
        # Fallback: use git root
        $gitRoot = git rev-parse --show-toplevel 2>$null
        if ($gitRoot) { $gitRoot } else { $PWD.Path }
    }

    return @{
        RepoRoot   = $repoRoot
        EnvFile    = Join-Path $repoRoot ".env.m21.local"
        StateFile  = Join-Path $repoRoot ".m21-operational-state.json"
        ExampleFile = Join-Path $repoRoot ".env.example"
    }
}

<#
.SYNOPSIS
    Imports .env.m21.local into current process environment.

.DESCRIPTION
    Reads KEY=VALUE pairs from the M21 local env file and sets them
    as environment variables in the current process. Fail-fast if
    critical variables are missing.
#>
function Import-M21OperationalEnv {
    [CmdletBinding()]
    param(
        [hashtable]$Paths = (Get-M21RepoPaths)
    )

    $envFile = $Paths.EnvFile
    if (-not (Test-Path $envFile)) {
        Write-Error "[M21] .env.m21.local not found at $envFile. Run: pwsh -File scripts/m21/new-v3-operational-env.ps1"
        exit 1
    }

    $content = Get-Content $envFile -Raw
    $varsSet = 0

    foreach ($line -in $content -split "`n") {
        $line = $line.Trim()
        if ($line -match '^(\w+)=(.+)$') {
            $name = $Matches[1]
            $value = $Matches[2].Trim()
            [Environment]::SetEnvironmentVariable($name, $value)
            $varsSet++
        }
    }

    Write-Output "[M21] Imported $varsSet variables from .env.m21.local"

    # Verify critical variables exist
    $criticalVars = @('MYSQL_ROOT_PASSWORD', 'MYSQL_GATEWAY_PASSWORD', 'AICOSTOPS_GATEWAY_DEV_RAW_KEY')
    $missing = @()
    foreach ($var in $criticalVars) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($var))) {
            $missing += $var
        }
    }

    if ($missing.Count -gt 0) {
        Write-Error "[M21] Missing critical variables: $($missing -join ', ')"
        exit 1
    }
}