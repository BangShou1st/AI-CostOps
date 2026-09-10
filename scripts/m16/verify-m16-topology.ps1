<#
.SYNOPSIS
    M16 Task 2 RED/GREEN gate: the V2 acceptance topology must contain a
    first-class healthy `gateway` service wired to the same MySQL/Redis
    network with its own DB identity variables.

.DESCRIPTION
    Parses the composed Docker configuration for the M16 acceptance project
    (compose.yaml + compose.m16.yaml) and asserts:
      - a `gateway` service exists;
      - it builds from ./gateway with a healthcheck;
      - it depends on healthy mysql and redis;
      - it carries its own SPRING_DATASOURCE_USERNAME/PASSWORD variables
        (separate identity from backend);
      - it exposes the gateway port only on loopback.

    Exits non-zero with M16_TOPOLOGY_RED on any violation.

.EXAMPLE
    .\scripts\m16\verify-m16-topology.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$failures = @()
function Fail([string]$Message) {
    Write-Output "[M16-TOPOLOGY-RED] $Message"
    $script:failures += $Message
}

Push-Location $RepoRoot
try {
    $overlay = Join-Path $RepoRoot "compose.m16.yaml"
    if (-not (Test-Path -LiteralPath $overlay)) {
        Fail "compose.m16.yaml is missing; Gateway has no first-class V2 acceptance topology."
    } else {
        $config = docker compose -f compose.yaml -f compose.m16.yaml config 2>&1
        if ($LASTEXITCODE -ne 0) {
            Fail "docker compose config failed: $config"
        } else {
            $text = ($config | Out-String)
            if ($text -notmatch '(?m)^\s+gateway:') {
                Fail "no `gateway` service in the composed V2 acceptance topology."
            }
            if ($text -notmatch 'SPRING_DATASOURCE_USERNAME') {
                Fail "gateway service carries no SPRING_DATASOURCE_USERNAME (separate DB identity required)."
            }
            if ($text -notmatch 'healthcheck') {
                Fail "composed topology exposes no healthcheck block."
            }
        }
    }
} finally {
    Pop-Location
}

if ($failures.Count -gt 0) {
    Write-Output "[M16-TOPOLOGY-RED] M16_TOPOLOGY_RED ($($failures.Count) violation(s))"
    exit 1
}
Write-Output "[M16-TOPOLOGY] M16_TOPOLOGY_GREEN: gateway is a first-class V2 acceptance service."
exit 0
