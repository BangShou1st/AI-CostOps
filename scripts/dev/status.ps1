# Shows this Compose project's infrastructure through the shared runner.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

& node (Join-Path $root 'scripts/tasks.mjs') 'infra:status' @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
