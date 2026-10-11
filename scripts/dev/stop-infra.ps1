# Stops this Compose project's infrastructure; containers and volumes remain.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

& node (Join-Path $root 'scripts/tasks.mjs') 'infra:down' @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
