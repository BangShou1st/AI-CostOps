# Syncs the project-external OpenCode Zen shared-library master into this repo's source snapshot.
#
# Direction is one-way: workspace master (E:\project\shared-libs\opencode-zen) -> repo snapshot
# (E:\project\AI-CostOps\shared-libs\opencode-zen). The external master is authoritative; this repo
# never writes back to it, so uncommitted master edits are never overwritten.
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspaceRoot = Split-Path -Parent $repoRoot
$sourceRoot = Join-Path $workspaceRoot 'shared-libs\opencode-zen'
$targetRoot = Join-Path $repoRoot 'shared-libs\opencode-zen'

if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot 'settings.gradle.kts'))) {
    throw "OpenCode Zen source build was not found at $sourceRoot"
}

function Copy-TreeFiles([string]$Source, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        throw "Required source directory was not found: $Source"
    }

    $sourcePath = (Resolve-Path -LiteralPath $Source).Path.TrimEnd('\')
    Get-ChildItem -LiteralPath $sourcePath -File -Recurse |
        Where-Object { $_.FullName -notmatch '[\\/](\.gradle|build)([\\/]|$)' } |
        ForEach-Object {
            $relative = $_.FullName.Substring($sourcePath.Length).TrimStart('\', '/')
            $destinationFile = Join-Path $Destination $relative
            $destinationDirectory = Split-Path -Parent $destinationFile
            New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
            Copy-Item -LiteralPath $_.FullName -Destination $destinationFile -Force
        }
}

New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
foreach ($file in @('settings.gradle.kts', 'build.gradle.kts', 'README.md', '.gitignore', 'gradlew', 'gradlew.bat')) {
    Copy-Item -LiteralPath (Join-Path $sourceRoot $file) -Destination $targetRoot -Force
}
Copy-TreeFiles (Join-Path $sourceRoot 'gradle') (Join-Path $targetRoot 'gradle')
Copy-TreeFiles (Join-Path $sourceRoot 'opencode-zen-spring-boot-autoconfigure') (Join-Path $targetRoot 'opencode-zen-spring-boot-autoconfigure')
Copy-TreeFiles (Join-Path $sourceRoot 'opencode-zen-spring-boot-starter') (Join-Path $targetRoot 'opencode-zen-spring-boot-starter')

"Synced OpenCode Zen sources from $sourceRoot into $targetRoot"
