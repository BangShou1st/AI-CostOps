#!/usr/bin/env bash
# Starts this Compose project's infrastructure through the shared runner.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

exec node "$root/scripts/tasks.mjs" infra:up "$@"
