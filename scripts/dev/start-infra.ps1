# Starts this Compose project's MySQL, Redis and MinIO through the shared runner.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

& node (Join-Path $root 'scripts/tasks.mjs') 'infra:up' @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
