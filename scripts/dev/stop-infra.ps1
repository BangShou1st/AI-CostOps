# Stops only the local-dev infrastructure (MySQL, Redis, MinIO).
# Uses `stop`, so containers and all data volumes are preserved.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Push-Location $root
try {
    docker compose -f compose.yaml -f compose.dev.yaml stop mysql redis minio
}
finally {
    Pop-Location
}