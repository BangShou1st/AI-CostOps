<#
.SYNOPSIS
Mirror the configured Evidence bucket from the running MinIO into
.local-backups/evidence/ using a disposable official MinIO Client container.
Only the Evidence bucket is read; nothing is modified in the source stack.

SHA-256 per file is computed by backup-evidence (not by the mirror itself) and
written to a manifest so restore-evidence can prove byte-for-byte equality.
#>
[CmdletBinding()]
param(
    [string]$EnvFile = ".env",
    [string]$ProjectName = "ai-costops",
    [string]$BackupRoot = ".local-backups",
    [string]$OutDir = "",
    [string]$McImage = "minio/mc",
    [switch]$ForceExplicitPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string]$Name) { Write-Output "[BACKUP-EVIDENCE] $Name" }
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
$evidenceRoot = [System.IO.Path]::GetFullPath((Join-Path $localBackups "evidence"))
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $evidenceRoot (Get-Date -Format "yyyyMMdd-HHmmss")
}
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
if (-not $ForceExplicitPath) {
    $isUnderRoot = $OutDir.StartsWith($evidenceRoot + [System.IO.Path]::DirectorySeparatorChar) -or
        $OutDir -eq $evidenceRoot
    Assert-True $isUnderRoot `
        "Refusing to write an Evidence backup outside $evidenceRoot (use -ForceExplicitPath to override)."
}

Write-Stage "Read environment"
$bucket = Get-EnvValue "MINIO_BUCKET"
$accessKey = Get-EnvValue "MINIO_ROOT_USER"
$secretKey = Get-EnvValue "MINIO_ROOT_PASSWORD"
Assert-True (-not [string]::IsNullOrWhiteSpace($secretKey)) "MINIO_ROOT_PASSWORD is empty"

Write-Stage "Resolve the project network from the MySQL container"
$mysqlId = (& docker compose --env-file $EnvFile -p $ProjectName ps -q mysql 2>$null).Trim()
Assert-True (-not [string]::IsNullOrWhiteSpace($mysqlId)) "No mysql container for project $ProjectName"
$networkJson = (& docker inspect -f '{{json .NetworkSettings.Networks}}' $mysqlId)
$networks = $networkJson | ConvertFrom-Json
$networkName = ($networks.PSObject.Properties.Name | Select-Object -First 1)
Assert-True (-not [string]::IsNullOrWhiteSpace($networkName)) "Could not resolve the project network name"

Write-Stage "Create output directory"
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

Write-Stage "Mirror bucket '$bucket' from the source stack (disposable mc container)"
$started = Get-Date
# Credentials travel only through the MC_HOST env of the disposable container.
& docker run --rm `
    --network $networkName `
    -v "${OutDir}:/backup" `
    -e "MC_HOST_source=http://$accessKey`:$secretKey@minio:9000" `
    $McImage mirror --overwrite --remove "source/$bucket" /backup 
$exitCode = $LASTEXITCODE
$elapsed = (Get-Date) - $started
Assert-True ($exitCode -eq 0) "mc mirror exited with code $exitCode"

Write-Stage "Compute per-file SHA-256 manifest"
$files = Get-ChildItem -LiteralPath $OutDir -File -Recurse
$entries = @()
foreach ($file in $files) {
    $relative = $file.FullName.Substring($OutDir.Length).TrimStart("\", "/")
    $entries += [ordered]@{
        path = $relative
        bytes = $file.Length
        sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$manifest = [ordered]@{
    tool = "mc mirror"
    bucket = $bucket
    createdAtUtc = $started.ToUniversalTime().ToString("o")
    elapsedSeconds = [math]::Round($elapsed.TotalSeconds, 3)
    fileCount = $entries.Count
    files = $entries
} | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText(
    (Join-Path $OutDir "backup-manifest.json"),
    $manifest,
    [System.Text.UTF8Encoding]::new($false))

Write-Output "[PASS] Evidence bucket mirrored to $OutDir ($($entries.Count) files, $([math]::Round($elapsed.TotalSeconds, 3))s)"