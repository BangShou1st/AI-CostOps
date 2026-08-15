# Starts only the local-dev infrastructure (MySQL, Redis, MinIO) and makes
# sure the containerized frontend/backend from a previous Full Integration
# run are stopped. Never builds anything, never prunes and never touches
# volumes. Safe to run repeatedly.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

if (-not (Test-Path (Join-Path $root '.env'))) {
    throw "缺少 .env：请先执行 Copy-Item .env.example .env"
}

Push-Location $root
try {
    # Leave Full Integration Mode: stop (not remove) the app containers.
    docker compose -f compose.yaml -f compose.dev.yaml stop backend frontend
    docker compose -f compose.yaml -f compose.dev.yaml up -d mysql redis minio
    docker compose -f compose.yaml -f compose.dev.yaml ps
}
finally {
    Pop-Location
}