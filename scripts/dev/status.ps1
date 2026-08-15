# Shows the local-dev infrastructure status (MySQL, Redis, MinIO).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Push-Location $root
try {
    docker compose -f compose.yaml -f compose.dev.yaml ps
}
finally {
    Pop-Location
}