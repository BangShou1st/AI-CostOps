<#
.SYNOPSIS
Logical MySQL backup of the running app database via mysqldump inside the
running MySQL container, written under .local-backups/mysql/ with a SHA-256
sidecar. The database password is passed as a container environment variable
and is never printed.

This script only READS from the running stack; it never stops or removes any
container, network or volume.
#>
[CmdletBinding()]
param(
    [string]$EnvFile = ".env",
    [string]$ProjectName = "ai-costops",
    [string]$BackupRoot = ".local-backups",
    [string]$OutDir = "",
    [switch]$ForceExplicitPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string]$Name) { Write-Output "[BACKUP-MYSQL] $Name" }
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
$localBackups = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $BackupRoot))
$mysqlRoot = [System.IO.Path]::GetFullPath((Join-Path $localBackups "mysql"))

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $mysqlRoot (Get-Date -Format "yyyyMMdd-HHmmss")
}
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
if (-not $ForceExplicitPath) {
    $isUnderRoot = $OutDir.StartsWith($mysqlRoot + [System.IO.Path]::DirectorySeparatorChar) -or
        $OutDir -eq $mysqlRoot
    Assert-True $isUnderRoot `
        "Refusing to write a MySQL backup outside $mysqlRoot (use -ForceExplicitPath to override)."
}

Write-Stage "Read environment"
$user = Get-EnvValue "MYSQL_USER"
$database = Get-EnvValue "MYSQL_DATABASE"
$password = Get-EnvValue "MYSQL_PASSWORD"
Assert-True (-not [string]::IsNullOrWhiteSpace($password)) "MYSQL_PASSWORD is empty"

Write-Stage "Create output directory"
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

Write-Stage "Dump $database from project $ProjectName via mysqldump (password via env, never printed)"
$dumpPath = Join-Path $OutDir "dump.sql"
$errorLine = Join-Path $OutDir "mysqldump.stderr"
$started = Get-Date
# Dump to a file inside the container and docker cp it out: piping mysqldump
# stdout through PowerShell would re-encode the byte stream and corrupt UTF-8
# SQL. The password travels only through the MYSQL_PWD env var of the exec.
$containerId = (& docker compose --env-file $EnvFile -p $ProjectName ps -q mysql 2>$null).Trim()
Assert-True (-not [string]::IsNullOrWhiteSpace($containerId)) "No mysql container for project $ProjectName"
& docker compose --env-file $EnvFile -p $ProjectName exec -T `
    -e "MYSQL_PWD=$password" `
    mysql sh -c "mysqldump --single-transaction --routines --triggers --skip-lock-tables --no-tablespaces -u$user $database > /tmp/aicostops-backup-dump.sql && echo DUMP_OK"
$exitCode = $LASTEXITCODE
Assert-True ($exitCode -eq 0) "mysqldump exited with code $exitCode"
& docker cp "${containerId}:/tmp/aicostops-backup-dump.sql" $dumpPath
Assert-True ($LASTEXITCODE -eq 0) "docker cp of the dump failed"
& docker compose --env-file $EnvFile -p $ProjectName exec -T mysql rm -f /tmp/aicostops-backup-dump.sql | Out-Null
$elapsed = (Get-Date) - $started
$dumpBytes = (Get-Item -LiteralPath $dumpPath).Length
Assert-True ($dumpBytes -gt 0) "mysqldump produced an empty file"

$hash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText(
    "$dumpPath.sha256",
    "$hash  $([System.IO.Path]::GetFileName($dumpPath))`n",
    [System.Text.UTF8Encoding]::new($false))

$manifest = [ordered]@{
    tool = "mysqldump"
    project = $ProjectName
    database = $database
    createdAtUtc = $started.ToUniversalTime().ToString("o")
    elapsedSeconds = [math]::Round($elapsed.TotalSeconds, 3)
    dumpBytes = $dumpBytes
    sha256 = $hash
} | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText(
    (Join-Path $OutDir "backup.json"),
    $manifest,
    [System.Text.UTF8Encoding]::new($false))

Write-Output "[PASS] MySQL backup written to $OutDir (sha256 $hash)"
Write-Output "[INFO] dump bytes: $dumpBytes, elapsed: $([math]::Round($elapsed.TotalSeconds, 3))s"
Remove-Item -LiteralPath $errorLine -Force -ErrorAction SilentlyContinue