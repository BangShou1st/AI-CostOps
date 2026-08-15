# Starts only the local-dev infrastructure (MySQL, Redis, MinIO).
# Never builds frontend/backend images, never prunes anything and never
# touches volumes. Safe to run repeatedly.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

if (-not (Test-Path (Join-Path $root '.env'))) {
    throw "缺少 .env：请先执行 Copy-Item .env.example .env"
}

Push-Location $root
try {
    docker compose -f compose.yaml -f compose.dev.yaml up -d mysql redis minio
    docker compose -f compose.yaml -f compose.dev.yaml ps
}
finally {
    Pop-Location
}