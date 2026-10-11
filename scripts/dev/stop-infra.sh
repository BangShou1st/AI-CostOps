#!/usr/bin/env bash
# Stops this Compose project's infrastructure; containers and volumes remain.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

exec node "$root/scripts/tasks.mjs" infra:down "$@"
