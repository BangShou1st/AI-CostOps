/**
 * HC-03 environment constants.
 *
 * Imported before any shared HC-01 helper so the inherited `HC01_*` lookups in
 * `scripts/perf/hc01/lib.mjs` resolve to the HC-03 dedicated resources. The
 * whole point of HC-03 is that every stoppable dependency is owned by this
 * task: a throw-away MySQL 8.4 container and a throw-away Redis container,
 * never the shared development infrastructure.
 */
import { randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const ROOT = fileURLToPath(new URL('../../..', import.meta.url))
export const HC03_DIR = join(ROOT, '.local-dev', 'hc03')
export const LOG_DIR = join(HC03_DIR, 'logs')
export const PID_DIR = join(HC03_DIR, 'pids')
export const RAW_DIR = join(HC03_DIR, 'raw')

export const RUN_NONCE = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)

export const DB_NAME = 'aicostops_hc03'
export const GATEWAY_DB_USER = 'gw_hc03'
export const MODEL_KEY = 'hc03-chat'
export const PROVIDER_CODE = 'HC03MOCK'

export const MYSQL_CONTAINER = 'ai-costops-hc03-mysql'
export const REDIS_CONTAINER = 'ai-costops-hc03-redis'
export const MYSQL_PORT = Number(process.env.HC03_MYSQL_PORT ?? 13307)
export const REDIS_PORT = Number(process.env.HC03_REDIS_PORT ?? 16379)
export const MYSQL_IMAGE = 'mysql:8.4'
export const REDIS_IMAGE = 'redis:8.8.1-alpine'

export const BACKEND_PORT = 18080
export const GATEWAY_A_PORT = 18081
export const GATEWAY_B_PORT = 18082
export const MOCK_PORT = 18089

/**
 * Run secrets are persisted under .local-dev/hc03 (git-ignored) so a later
 * `--attach` invocation can talk to the containers/processes created earlier.
 * A fresh checkout simply generates new throw-away secrets.
 */
const SECRETS_PATH = join(HC03_DIR, 'run-secrets.json')
const secrets = (() => {
  if (existsSync(SECRETS_PATH)) {
    try {
      const parsed = JSON.parse(readFileSync(SECRETS_PATH, 'utf8'))
      if (parsed?.mysqlRoot && parsed?.redis && parsed?.gatewayDb && parsed?.backendApp) return parsed
    } catch { /* regenerate below */ }
  }
  const generated = {
    generatedAt: new Date().toISOString(),
    mysqlRoot: `hc03-${RUN_NONCE}-${randomBytes(9).toString('base64url')}`,
    redis: `hc03-${RUN_NONCE}-${randomBytes(9).toString('base64url')}`,
    gatewayDb: `hc03-${RUN_NONCE}-${randomBytes(9).toString('base64url')}`,
    backendApp: `hc03-${RUN_NONCE}-${randomBytes(9).toString('base64url')}`,
  }
  mkdirSync(HC03_DIR, { recursive: true })
  writeFileSync(SECRETS_PATH, `${JSON.stringify(generated, null, 2)}\n`, 'utf8')
  return generated
})()

export const MYSQL_ROOT_PASSWORD = secrets.mysqlRoot
export const REDIS_PASSWORD = secrets.redis
export const GATEWAY_DB_PASSWORD = secrets.gatewayDb
export const BACKEND_APP_PASSWORD = secrets.backendApp
// Shared HC-01 helpers use these env names. They must point at the HC-03
// dedicated resources, never at the developer databases on 3307/6379.
process.env.HC01_DB = DB_NAME
process.env.HC01_MYSQL_HOST = '127.0.0.1'
process.env.HC01_MYSQL_PORT = String(MYSQL_PORT)
process.env.HC01_MYSQL_CONTAINER = MYSQL_CONTAINER
process.env.HC01_REDIS_HOST = '127.0.0.1'
process.env.HC01_REDIS_PORT = String(REDIS_PORT)
process.env.HC01_REDIS_CONTAINER = REDIS_CONTAINER

export const EVIDENCE_DIR = join(
  ROOT, 'docs', '03-acceptance', 'maintenance', '2026-11',
  '2026-11-11-hc-03-failure-recovery')

export function mysqlOptions() {
  return {
    host: '127.0.0.1',
    port: MYSQL_PORT,
    user: 'root',
    password: MYSQL_ROOT_PASSWORD,
    database: DB_NAME,
  }
}

export function redisCliArgs(args) {
  return ['exec', REDIS_CONTAINER, 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', ...args]
}
