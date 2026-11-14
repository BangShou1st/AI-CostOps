import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = dirname(dirname(dirname(fileURLToPath(import.meta.url))))
const CONFIG = readFileSync(join(ROOT, 'frontend', 'nginx', 'default.conf'), 'utf8')
const OBSERVABILITY = readFileSync(join(ROOT, 'compose.observability.yaml'), 'utf8')
const OBSERVABILITY_DOC = readFileSync(
  join(ROOT, 'docs', '04-operations', 'observability', '02-observability.md'),
  'utf8',
)

test('nginx accepts the backend request limit instead of its 1 MiB default', () => {
  const match = CONFIG.match(/^\s*client_max_body_size\s+(\d+)m;\s*$/m)
  assert.ok(match, 'client_max_body_size must be explicit')
  assert.ok(Number(match[1]) >= 525, 'client_max_body_size must cover the 525 MiB backend request limit')
})

test('nginx allows slow large uploads and long backend processing', () => {
  assert.match(CONFIG, /^\s*client_body_timeout\s+300s;\s*$/m)
  assert.match(CONFIG, /^\s*proxy_connect_timeout\s+10s;\s*$/m)
  assert.match(CONFIG, /^\s*proxy_send_timeout\s+600s;\s*$/m)
  assert.match(CONFIG, /^\s*proxy_read_timeout\s+600s;\s*$/m)
  assert.match(CONFIG, /^\s*proxy_request_buffering\s+off;\s*$/m)
})

test('observability instructions include the Gateway-bearing topology', () => {
  for (const document of [OBSERVABILITY, OBSERVABILITY_DOC]) {
    assert.match(document, /compose\.v3-operational\.yaml/)
    assert.match(document, /compose\.observability\.yaml/)
  }
})
