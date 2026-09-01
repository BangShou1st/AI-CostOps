<#
.SYNOPSIS
Restore a local Evidence backup (produced by backup-evidence.ps1) into the
ISOLATED restore-drill project's MinIO bucket using a disposable official
MinIO Client container. The ordinary developer Evidence bucket is never touched.

Every restored object is hash-verified against the backup manifest before this
script reports success.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceDir,
    [Parameter(Mandatory = $true)]
    [string]$ProjectName,
    [string]$EnvFile = ".env",
    [string]$McImage = "minio/mc",
    [switch]$ForceExplicitPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string]$Name) { Write-Output "[RESTORE-EVIDENCE] $Name" }
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
$srcDir = [System.IO.Path]::GetFullPath($SourceDir)
if (-not $ForceExplicitPath) {
    Assert-True $srcDir.StartsWith((Join-Path $localBackups "evidence")) `
        "Refusing to restore Evidence from outside .local-backups/evidence (use -ForceExplicitPath to override)."
}
Assert-True (Test-Path -LiteralPath $srcDir) "Source backup dir not found: $srcDir"

$manifestPath = Join-Path $srcDir "backup-manifest.json"
Assert-True (Test-Path -LiteralPath $manifestPath) "Missing backup-manifest.json in $srcDir"
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$bucket = [string]$manifest.bucket
$expectedFiles = @($manifest.files)

Write-Stage "Read environment"
$accessKey = Get-EnvValue "MINIO_ROOT_USER"
$secretKey = Get-EnvValue "MINIO_ROOT_PASSWORD"

Write-Stage "Resolve the project network from the MySQL container"
$mysqlId = (& docker compose --env-file $EnvFile -p $ProjectName ps -q mysql 2>$null).Trim()
Assert-True (-not [string]::IsNullOrWhiteSpace($mysqlId)) "No mysql container for project $ProjectName"
$networkJson = (& docker inspect -f '{{json .NetworkSettings.Networks}}' $mysqlId)
$networkName = (($networkJson | ConvertFrom-Json).PSObject.Properties.Name | Select-Object -First 1)
Assert-True (-not [string]::IsNullOrWhiteSpace($networkName)) "Could not resolve the project network name"

Write-Stage "Stage evidence objects (exclude backup-manifest.json) and mirror into isolated bucket '$bucket'"
$started = Get-Date
# The backup directory also contains backup-manifest.json; that must never be
# mirrored into the restored bucket (it would make the object count diverge
# from the manifest and pollute the Evidence store). Mirror only the object
# tree from a staging copy.
$stageDir = Join-Path $srcDir "_stage_mirror"
New-Item -ItemType Directory -Path $stageDir -Force | Out-Null
Get-ChildItem -LiteralPath $srcDir -Force |
    Where-Object { $_.Name -ne "backup-manifest.json" -and $_.Name -ne "_stage_mirror" } |
    Copy-Item -Destination $stageDir -Recurse -Force
& docker run --rm `
    --network $networkName `
    -e "MC_HOST_drill=http://$accessKey`:$secretKey@minio:9000" `
    $McImage mb --ignore-existing "drill/$bucket"
Assert-True ($LASTEXITCODE -eq 0) "mc mb failed for bucket $bucket"
& docker run --rm `
    --network $networkName `
    -v "${stageDir}:/backup" `
    -e "MC_HOST_drill=http://$accessKey`:$secretKey@minio:9000" `
    $McImage mirror --overwrite --remove /backup "drill/$bucket"
$exitCode = $LASTEXITCODE
Remove-Item -LiteralPath $stageDir -Recurse -Force
$elapsed = (Get-Date) - $started
Assert-True ($exitCode -eq 0) "mc mirror (restore) exited with code $exitCode"

Write-Stage "Hash-verify every restored object"
try {
    # List the bucket through the disposable client to get object names, then
    # download each object and compare its SHA-256 to the manifest entry.
    $listing = & docker run --rm `
        --network $networkName `
        -v "${srcDir}:/verify" `
        -e "MC_HOST_drill=http://$accessKey`:$secretKey@minio:9000" `
        $McImage ls --recursive --json "drill/$bucket"
    Assert-True ($LASTEXITCODE -eq 0) "mc ls failed on the restored bucket"
    $objects = @()
    foreach ($line in $listing) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $entry = $line | ConvertFrom-Json
        if ($entry.type -eq "file") {
            $objects += [string]$entry.key
        }
    }
    Assert-True ($objects.Count -eq $expectedFiles.Count) `
        "Restored object count $($objects.Count) != manifest count $($expectedFiles.Count)"

    $downloadDir = Join-Path $srcDir "_verify_download"
    New-Item -ItemType Directory -Path $downloadDir -Force | Out-Null
    # mc cp of the whole bucket keeps relative paths.
    & docker run --rm `
        --network $networkName `
        -v "${downloadDir}:/dl" `
        -e "MC_HOST_drill=http://$accessKey`:$secretKey@minio:9000" `
        $McImage cp --recursive "drill/$bucket" /dl/
    Assert-True ($LASTEXITCODE -eq 0) "mc cp failed during hash verification"
    $verifiedCount = 0
    foreach ($entry in $expectedFiles) {
        # mc cp preserves the bucket name as a prefix (bucket/objectKey), so the
        # downloaded copy lives under <downloadDir>/<bucket>/<relative-path>.
        $expectedRelative = ([string]$entry.path).Replace("\", "/")
        $localCopy = Join-Path (Join-Path $downloadDir $bucket) $expectedRelative
        Assert-True (Test-Path -LiteralPath $localCopy) "Missing restored object $expectedRelative"
        $actual = (Get-FileHash -LiteralPath $localCopy -Algorithm SHA256).Hash.ToLowerInvariant()
        Assert-True ($actual -eq [string]$entry.sha256) `
            "SHA-256 mismatch for restored object $expectedRelative"
        $verifiedCount += 1
    }
    Write-Output "[PASS] Restored Evidence bucket '$bucket': $verifiedCount/$($expectedFiles.Count) objects hash-verified"
    Remove-Item -LiteralPath $downloadDir -Recurse -Force
} finally {
    # no-op placeholder keeps the try/finally shape stable
}