<#
.SYNOPSIS
Restore a MySQL dump produced by backup-mysql.ps1 into an ISOLATED restore-drill
Compose project. It never touches the developer's normal project volumes; the
target project name must be an explicit parameter.

The dump's SHA-256 sidecar is verified before any command runs. The database
password is passed as a container environment variable and is never printed.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceDump,
    [Parameter(Mandatory = $true)]
    [string]$ProjectName,
    [string]$EnvFile = ".env",
    [switch]$ForceExplicitPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string]$Name) { Write-Output "[RESTORE-MYSQL] $Name" }
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Get-EnvValue([string]$Name) {
    $escaped = [regex]::Escape($Name)
    $line = Get-Content -LiteralPath $EnvFile |
        Where-Object { $_ -match "^$escaped=" } |
        Select-Object -First 1
    if (-not $line) { throw "Missing $Name in $EnvFile" }
    return (($line -split "=", 2)[1]).Trim()
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$localBackups = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".local-backups"))
$dumpPath = [System.IO.Path]::GetFullPath($SourceDump)
if (-not $ForceExplicitPath) {
    Assert-True $dumpPath.StartsWith($localBackups) `
        "Refusing to restore from outside $localBackups (use -ForceExplicitPath to override)."
}
Assert-True (Test-Path -LiteralPath $dumpPath) "Source dump not found: $dumpPath"

Write-Stage "Verify SHA-256 sidecar"
$sidecar = "$dumpPath.sha256"
Assert-True (Test-Path -LiteralPath $sidecar) "Missing SHA-256 sidecar for $dumpPath"
$expectedHash = (Get-Content -LiteralPath $sidecar | Select-Object -First 1).Trim().Split(" ")[0]
$actualHash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-True ($actualHash -eq $expectedHash.ToLowerInvariant()) `
    "SHA-256 mismatch for $dumpPath (expected $expectedHash, got $actualHash)"
Write-Output "[PASS] SHA-256 verified: $actualHash"

Write-Stage "Read environment"
$user = Get-EnvValue "MYSQL_USER"
$database = Get-EnvValue "MYSQL_DATABASE"
$password = Get-EnvValue "MYSQL_PASSWORD"

Write-Stage "Load dump into isolated project $ProjectName (password via env, never printed)"
$started = Get-Date
$containerId = (& docker compose --env-file $EnvFile -p $ProjectName ps -q mysql 2>$null).Trim()
Assert-True (-not [string]::IsNullOrWhiteSpace($containerId)) "No mysql container for project $ProjectName"
& docker cp $dumpPath "${containerId}:/tmp/aicostops-restore-dump.sql"
Assert-True ($LASTEXITCODE -eq 0) "docker cp of the dump into the container failed"
& docker compose --env-file $EnvFile -p $ProjectName exec -T `
    -e "MYSQL_PWD=$password" `
    mysql sh -c "mysql -h 127.0.0.1 -P 3306 --protocol=TCP --user=$user --database=$database --default-character-set=utf8mb4 < /tmp/aicostops-restore-dump.sql && echo LOAD_OK"
$exitCode = $LASTEXITCODE
& docker compose --env-file $EnvFile -p $ProjectName exec -T mysql rm -f /tmp/aicostops-restore-dump.sql | Out-Null
$elapsed = (Get-Date) - $started
Assert-True ($exitCode -eq 0) "mysql load exited with code $exitCode"

Write-Stage "Verify restored schema is readable"
$tableCount = (
    & docker compose --env-file $EnvFile -p $ProjectName exec -T `
        -e "MYSQL_PWD=$password" `
        mysql mysql --host="127.0.0.1" --protocol=TCP --user="$user" --database="$database" -Nse `
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$database';"
).Trim()
$count = 0
Assert-True ([int]::TryParse($tableCount, [ref]$count)) "Could not parse restored table count: '$tableCount'"
Assert-True ($count -gt 0) "Restored database has no tables"

Write-Output "[PASS] Restore into $ProjectName completed: $count tables, $([math]::Round($elapsed.TotalSeconds, 3))s"