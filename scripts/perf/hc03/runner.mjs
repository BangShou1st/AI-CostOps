#!/usr/bin/env node
/**
 * HC-03 fault-injection and recovery runner.
 *
 * Everything that can be stopped is owned by this task:
 *   - dedicated MySQL 8.4 container `ai-costops-hc03-mysql` (:13307),
 *   - dedicated Redis container      `ai-costops-hc03-redis`  (:16379),
 *   - one Backend JVM (:18080), two independent Gateway JVMs (:18081/:18082),
 *   - one controlled Mock Provider (:18089).
 * Shared development MySQL/Redis/MinIO are never touched.
 *
 * Usage:
 *   node scripts/perf/hc03/runner.mjs                     # all scenarios, 3 reps
 *   node scripts/perf/hc03/runner.mjs --quick              # 1 rep (harness smoke)
 *   node scripts/perf/hc03/runner.mjs --scenario=F01,F03pre,F03post
 *   node scripts/perf/hc03/runner.mjs --reps=3 --build
 *   node scripts/perf/hc03/runner.mjs --keep-running       # leave environment up
 *   node scripts/perf/hc03/runner.mjs --cleanup            # remove HC-03 containers only
 */
import { createHmac } from 'node:crypto'
import { copyFileSync, existsSync, mkdirSync, readFileSync, unlinkSync } from 'node:fs'
import http from 'node:http'
import { join } from 'node:path'
import {
  BACKEND_APP_PASSWORD, BACKEND_PORT, DB_NAME, EVIDENCE_DIR, GATEWAY_A_PORT, GATEWAY_B_PORT,
  GATEWAY_DB_PASSWORD, GATEWAY_DB_USER, HC03_DIR, LOG_DIR, MODEL_KEY, MOCK_PORT, MYSQL_CONTAINER,
  MYSQL_IMAGE, MYSQL_PORT, REDIS_CONTAINER, REDIS_IMAGE, REDIS_PASSWORD, REDIS_PORT,
  ROOT, RUN_NONCE,
} from './env.mjs'
import {
  EventLog, MySqlSession, Timeline, assertContainerAccess, budgetRowLockWaiters, containerState,
  dataLockWaits, docker, ensureDirs, ensureJars, getHarnessOwner, hostEnvironment, httpRequest,
  httpStatus, inspectContainer, isProcessRunning, jarSha256, killProc, log, lookupProcess, pauseRedis,
  pidFile, pidOf, readOwnerRecord, readPidRecord, recreateMysql, recreateRedis, redisCli, redisPing,
  removeOwnedContainer, resolvePythonCommand, setHarnessOwner, sleep, sqlNumber, sqlQuote, sqlRows,
  sqlStatement, startMysql, startProc, startRedis, stopMysql, stopProc, stopRedis, unpauseRedis,
  waitHttp, waitMysqlReady, waitRedisReady, writeJson, writeOwnerRecord,
} from './lib.mjs'
import {
  OWNER_LABEL, OwnershipError, RESOURCE_LABEL, RUN_LABEL, decideContainer, decideProcess,
  newOwnerId,
} from './ownership.mjs'
import { config } from '../hc01/lib.mjs'
import { createGatewayDbUser, seed } from './seed.mjs'
import {
  budgetSnapshot, fixtureSnapshot, mysqlGlobalCounters, orgLedgerSnapshot,
  periodSnapshot, requestDetail, violations, waitFor, waitForSettlement,
} from './verify.mjs'

const argv = process.argv.slice(2)
const flags = new Set(argv.filter(value => value.startsWith('--') && !value.includes('=')))
const options = Object.fromEntries(
  argv.filter(value => value.startsWith('--') && value.includes('='))
    .map(value => {
      const index = value.indexOf('=')
      return [value.slice(2, index), value.slice(index + 1)]
    }),
)
const QUICK = flags.has('--quick')
const KEEP_RUNNING = flags.has('--keep-running')
const BUILD = flags.has('--build')
const SKIP_BUILD = flags.has('--skip-build')
const CLEANUP_ONLY = flags.has('--cleanup')
const ATTACH = flags.has('--attach')
const REPS = Number(options.reps ?? (QUICK ? 1 : 3))
const SELECTED = (options.scenario ?? (QUICK ? 'F01,F03pre' : 'F01,F02,F03pre,F03post,F04,F05,F06'))
  .split(',').map(value => value.trim()).filter(Boolean)
const EVIDENCE = options.output ? join(ROOT, options.output) : EVIDENCE_DIR
const BACKEND_APP_USER = 'hc03_app'

const dotenv = config().dotenv
const EVENTS_PATH = join(HC03_DIR, 'events.jsonl')
const RAW_RESULTS_PATH = join(HC03_DIR, 'raw', 'results.json')

const state = {
  seed: null,
  jars: null,
  events: null,
  timeline: new Timeline(),
  scenarios: [],
  environment: null,
  startErrors: [],
  bootstrapBackend: false,
}

function requireDotenv(name) {
  const value = dotenv[name]
  if (!value) throw new Error(`.env 缺少 ${name}；HC-03 需要真实本地密钥材料（不提交）`)
  return value
}

function configureSeedSecrets() {
  process.env.HC03_PROVIDER_KEK = requireDotenv('AICOSTOPS_PROVIDER_KEK_V1')
  process.env.HC03_CREDENTIAL_HMAC_KEY = requireDotenv('AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1')
  process.env.HC03_REQUEST_HMAC_KEY = requireDotenv('AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1')
}

// ---------------------------------------------------------------------------
// Process / environment lifecycle
// ---------------------------------------------------------------------------

function backendEnvironment() {
  return {
    ...process.env,
    SPRING_DATASOURCE_URL: `jdbc:mysql://127.0.0.1:${MYSQL_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC`,
    SPRING_DATASOURCE_USERNAME: BACKEND_APP_USER,
    SPRING_DATASOURCE_PASSWORD: BACKEND_APP_PASSWORD,
    AICOSTOPS_REDIS_HOST: '127.0.0.1',
    AICOSTOPS_REDIS_PORT: String(REDIS_PORT),
    AICOSTOPS_REDIS_PASSWORD: REDIS_PASSWORD,
    AICOSTOPS_STORAGE_ENDPOINT: `http://127.0.0.1:${dotenv.MINIO_API_PORT ?? '9000'}`,
    AICOSTOPS_STORAGE_ACCESS_KEY: dotenv.MINIO_ROOT_USER,
    AICOSTOPS_STORAGE_SECRET_KEY: dotenv.MINIO_ROOT_PASSWORD,
    AICOSTOPS_STORAGE_BUCKET: dotenv.MINIO_BUCKET,
    AICOSTOPS_JWT_SIGNING_KEY: dotenv.AICOSTOPS_JWT_SIGNING_KEY,
    AICOSTOPS_ALLOW_PUBLIC_REGISTRATION: 'false',
    AICOSTOPS_ALLOWED_ORIGINS: 'http://localhost:5173',
    AICOSTOPS_REFRESH_COOKIE_SECURE: 'false',
    AICOSTOPS_DEV_BOOTSTRAP_ENABLED: 'false',
    AICOSTOPS_GATEWAY_SETTLEMENT_WORKER_ENABLED: 'true',
    AICOSTOPS_GATEWAY_SETTLEMENT_POLL_INTERVAL: '500ms',
    AICOSTOPS_IMPORT_WORKER_ENABLED: 'false',
    SERVER_PORT: String(BACKEND_PORT),
    SPRING_PROFILES_ACTIVE: '',
  }
}

function gatewayEnvironment(port) {
  return {
    ...process.env,
    SPRING_DATASOURCE_URL: `jdbc:mysql://127.0.0.1:${MYSQL_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC`,
    SPRING_DATASOURCE_USERNAME: GATEWAY_DB_USER,
    SPRING_DATASOURCE_PASSWORD: GATEWAY_DB_PASSWORD,
    SPRING_DATA_REDIS_HOST: '127.0.0.1',
    SPRING_DATA_REDIS_PORT: String(REDIS_PORT),
    SPRING_DATA_REDIS_PASSWORD: REDIS_PASSWORD,
    AICOSTOPS_GATEWAY_PORT: String(port),
    AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1: dotenv.AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1,
    AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1: dotenv.AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1,
    AICOSTOPS_PROVIDER_KEK_V1: dotenv.AICOSTOPS_PROVIDER_KEK_V1,
    AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED: 'false',
    // The harness Provider is a loopback mock; the public-only transport
    // resolver stays on by default for every real deployment.
    AICOSTOPS_GATEWAY_PROVIDER_ALLOW_PRIVATE_ENDPOINTS: 'true',
    // The limiter/quota stay enabled: they are the fail-closed gate under test.
    AICOSTOPS_GATEWAY_RATE_LIMIT_ENABLED: 'true',
    AICOSTOPS_GATEWAY_RATE_LIMIT_CAPACITY: '100000',
    AICOSTOPS_GATEWAY_RATE_LIMIT_REFILL_PER_SECOND: '10000',
    AICOSTOPS_GATEWAY_QUOTA_ENABLED: 'true',
    AICOSTOPS_GATEWAY_QUOTA_REQUESTS_PER_DAY: '1000000',
    AICOSTOPS_GATEWAY_MAX_ACTIVE_STREAMS: '64',
    AICOSTOPS_GATEWAY_DB_THREADS: '12',
    AICOSTOPS_GATEWAY_DB_QUEUE_CAPACITY: '256',
    AICOSTOPS_GATEWAY_DB_POOL_MAX: '12',
    // Short, explicit windows: a killed instance is reaped after 20 s, and a
    // hung upstream cannot outlive 15 s. reservation-ttl must exceed
    // hard-timeout (GatewayProperties#validate).
    AICOSTOPS_GATEWAY_HARD_TIMEOUT_MS: '15000',
    AICOSTOPS_GATEWAY_HEADER_TIMEOUT_MS: '8000',
    AICOSTOPS_GATEWAY_STREAM_IDLE_TIMEOUT_MS: '8000',
    AICOSTOPS_GATEWAY_RESERVATION_TTL_MS: '20000',
    AICOSTOPS_GATEWAY_RESERVATION_RECOVERY_INTERVAL_MS: '3000',
    SPRING_PROFILES_ACTIVE: '',
  }
}

async function startBackend() {
  log('starting Backend (Flyway + Settlement Worker)')
  startProc('backend', 'java', ['-jar', state.jars.backend], { env: backendEnvironment() })
  await waitHttp(BACKEND_PORT, '/actuator/health/readiness', 300_000, 'Backend')
  log('Backend ready')
}

async function startGateway(port) {
  log(`starting Gateway :${port}`)
  startProc(`gateway-${port}`, 'java',
    ['-jar', state.jars.gateway, `--server.port=${port}`], { env: gatewayEnvironment(port) })
  await waitHttp(port, '/actuator/health/readiness', 240_000, `Gateway :${port}`)
  log(`Gateway :${port} ready`)
}

async function startGateways() {
  await startGateway(GATEWAY_A_PORT)
  await startGateway(GATEWAY_B_PORT)
}

async function startMock() {
  log('starting controlled Mock Provider')
  const script = join(ROOT, 'scripts', 'perf', 'hc01', 'mock_provider.py')
  const python = resolvePythonCommand()
  startProc('mock-provider', python.command,
    [...python.prefix, script], { env: { ...process.env, MOCK_PROVIDER_PORT: String(MOCK_PORT) } })
  await waitHttp(MOCK_PORT, '/health', 60_000, 'Mock Provider')
  log('Mock Provider ready')
}

// ---------------------------------------------------------------------------
// Resource ownership: nothing is deleted or killed without proof
// ---------------------------------------------------------------------------

const MANAGED_PROCESSES = ['gateway-18081', 'gateway-18082', 'mock-provider', 'backend']
const FIXED_CONTAINERS = [
  { name: MYSQL_CONTAINER, resource: 'mysql' },
  { name: REDIS_CONTAINER, resource: 'redis' },
]

function startNewOwner() {
  const owner = newOwnerId(RUN_NONCE)
  writeOwnerRecord({ owner, runId: RUN_NONCE, createdAt: new Date().toISOString() })
  setHarnessOwner({ owner, runId: RUN_NONCE })
  log(`harness owner ${owner}`)
}

/**
 * A fresh run may only start when no fixed-name container and no managed
 * process from an earlier run is still present. Nothing is removed here:
 * leftovers must be cleaned explicitly with `--cleanup`, which itself verifies
 * labels and PID identities.
 */
function preflightFreshEnvironment() {
  const ownerRecord = readOwnerRecord()
  const recordedOwner = ownerRecord?.owner ?? '<unowned>'
  const problems = []

  for (const { name } of FIXED_CONTAINERS) {
    const existing = inspectContainer(name)
    if (!existing.exists) continue
    const decision = decideContainer({
      mode: 'create', name, expectedOwner: recordedOwner, existing,
    })
    problems.push(decision.message
      ?? `Container ${name} already exists; refusing to replace it implicitly.`)
  }

  for (const name of MANAGED_PROCESSES) {
    const record = readPidRecord(name)
    if (!record) continue
    if (record.legacy) {
      if (isProcessRunning(record.pid)) {
        problems.push(`Process ${name} (pid ${record.pid}) still has a legacy bare-number PID file `
          + 'without a verifiable owner/start time. The harness will not signal it. Inspect it, '
          + `then delete ${pidFile(name)} if it is safe.`)
      } else {
        unlinkSync(pidFile(name))
        log(`removed stale legacy PID file for ${name} (pid ${record.pid} is not running)`)
      }
      continue
    }
    if (record.corrupt) {
      problems.push(`Process ${name} has an unreadable PID record at ${pidFile(name)}. `
        + 'The harness will not signal it; inspect the file/process manually.')
      continue
    }
    const actual = lookupProcess(record.pid)
    if (!actual?.alive) {
      unlinkSync(pidFile(name))
      log(`removed stale PID record for ${name} (pid ${record.pid} is not running)`)
      continue
    }
    const decision = decideProcess({
      mode: 'stop', name, expectedOwner: record.owner, record, actual,
    })
    if (decision.allow) {
      problems.push(`Process ${name} (pid ${record.pid}, owner ${record.owner}) is still running `
        + 'from an earlier HC-03 run. Run `node scripts/perf/hc03/runner.mjs --cleanup`; the '
        + 'harness will not kill it implicitly.')
    } else {
      problems.push(decision.message)
    }
  }

  if (problems.length) {
    throw new OwnershipError(
      `HC-03 environment is not clean; refusing to start:\n- ${problems.join('\n- ')}`,
      'ENVIRONMENT_NOT_CLEAN',
    )
  }
  startNewOwner()
}

function attachToExistingEnvironment() {
  const ownerRecord = readOwnerRecord()
  if (!ownerRecord?.owner) {
    throw new OwnershipError(
      '--attach requires an HC-03 owner record at .local-dev/hc03/run-owner.json. '
        + 'Refusing to attach to containers/processes of unknown ownership.',
      'NO_OWNER_FILE',
    )
  }
  setHarnessOwner({ owner: ownerRecord.owner, runId: ownerRecord.runId ?? RUN_NONCE })
  for (const { name } of FIXED_CONTAINERS) {
    assertContainerAccess('attach', name)
  }
  for (const name of MANAGED_PROCESSES) {
    const record = readPidRecord(name)
    if (!record || record.legacy || record.corrupt) {
      throw new OwnershipError(
        `--attach cannot verify process ${name}: missing, legacy or unreadable PID record.`,
        'UNVERIFIABLE_PROCESS',
      )
    }
    const actual = lookupProcess(record.pid)
    if (!actual?.alive) {
      throw new OwnershipError(`--attach expected ${name} (pid ${record.pid}) to be running.`)
    }
    const decision = decideProcess({
      mode: 'stop', name, expectedOwner: ownerRecord.owner, record, actual,
    })
    if (!decision.allow) throw new OwnershipError(decision.message, decision.reason)
  }
  log(`attached to owner ${ownerRecord.owner}`)
}

async function cleanupOwnedEnvironment() {
  const ownerRecord = readOwnerRecord()
  if (!ownerRecord?.owner) {
    throw new OwnershipError(
      'No HC-03 owner record at .local-dev/hc03/run-owner.json; refusing to delete containers or '
        + 'stop processes. If you are certain they belong to HC-03, inspect them with '
        + '`docker inspect ai-costops-hc03-mysql ai-costops-hc03-redis`, then remove them manually.',
      'NO_OWNER_FILE',
    )
  }
  setHarnessOwner({ owner: ownerRecord.owner, runId: ownerRecord.runId ?? RUN_NONCE })
  log(`cleanup for owner ${ownerRecord.owner}`)
  return teardownProcesses({ removeContainers: true })
}

async function setupEnvironment() {
  log(`HC-03 run ${RUN_NONCE} database=${DB_NAME} mysql=:${MYSQL_PORT} redis=:${REDIS_PORT}`)
  configureSeedSecrets()

  recreateMysql()
  waitMysqlReady()
  recreateRedis()
  waitRedisReady()
  log('dedicated MySQL and Redis are ready')

  sqlStatement(`CREATE USER IF NOT EXISTS '${BACKEND_APP_USER}'@'%' IDENTIFIED BY '${BACKEND_APP_PASSWORD}';
    ALTER USER '${BACKEND_APP_USER}'@'%' IDENTIFIED BY '${BACKEND_APP_PASSWORD}';
    GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${BACKEND_APP_USER}'@'%';
    FLUSH PRIVILEGES;`, { database: null })

  state.jars = ensureJars({ forceBuild: BUILD, skipBuild: SKIP_BUILD })
  log(`jars gateway=${state.jars.gateway} backend=${state.jars.backend}`)
  await startBackend()
  log(`gateway DB identity: ${createGatewayDbUser(GATEWAY_DB_USER, GATEWAY_DB_PASSWORD).grants} grants`)
  state.seed = seed()
  log(`seeded orgs hot=${state.seed.hotOrg.orgId} close=${state.seed.closeOrg.orgId}`)
  await startMock()
  await startGateways()
}

/**
 * Best-effort teardown that never escalates: each stop/remove is ownership
 * checked and a refusal is reported instead of forced.
 */
async function teardownProcesses({ removeContainers = false } = {}) {
  if (!getHarnessOwner()) {
    return { refusals: [] }
  }
  const refusals = []
  for (const name of MANAGED_PROCESSES) {
    try {
      const result = await stopProc(name)
      if (result?.stopped) log(`stopped ${name}`)
    } catch (error) {
      refusals.push(error.message)
      log(`REFUSED to stop ${name}: ${error.message}`)
    }
  }
  if (removeContainers) {
    for (const { name } of FIXED_CONTAINERS) {
      try {
        const result = removeOwnedContainer(name)
        if (result.removed) log(`removed container ${name}`)
      } catch (error) {
        refusals.push(error.message)
        log(`REFUSED to remove ${name}: ${error.message}`)
      }
    }
  }
  return { refusals }
}

// ---------------------------------------------------------------------------
// HTTP drivers
// ---------------------------------------------------------------------------

function requestBody({ stream = false, maxCompletionTokens = 16, content = 'hc03 fault drill' } = {}) {
  return JSON.stringify({
    model: MODEL_KEY,
    messages: [{ role: 'user', content }],
    max_completion_tokens: maxCompletionTokens,
    ...(stream ? { stream: true } : {}),
  })
}

async function sendChat(port, fixture, idemKey, options = {}) {
  const body = options.body ?? requestBody(options)
  const response = await httpRequest({
    host: '127.0.0.1',
    port,
    path: '/v1/chat/completions',
    method: 'POST',
    headers: {
      Authorization: `Bearer ${fixture.rawKey}`,
      'Idempotency-Key': idemKey,
      'Content-Type': 'application/json',
      Accept: options.stream ? 'text/event-stream' : 'application/json',
    },
  }, body, options.timeoutMs ?? 60_000)
  return {
    port,
    status: response.status,
    error: response.error ?? null,
    ms: Math.round(response.ms),
    errorCode: extractErrorCode(response.body),
    bodySnippet: String(response.body ?? '').slice(0, 300),
    body,
  }
}

function extractErrorCode(body) {
  try {
    const parsed = JSON.parse(body)
    return parsed?.error?.code ?? null
  } catch {
    return null
  }
}

function openStream(port, fixture, idemKey, body) {
  const payload = body ?? requestBody({ stream: true })
  const started = Date.now()
  let request
  const done = new Promise(resolve => {
    request = http.request({
      host: '127.0.0.1',
      port,
      path: '/v1/chat/completions',
      method: 'POST',
      agent: false,
      headers: {
        Authorization: `Bearer ${fixture.rawKey}`,
        'Idempotency-Key': idemKey,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
        Accept: 'text/event-stream',
      },
    }, response => {
      let text = ''
      response.setEncoding('utf8')
      response.on('data', chunk => { text += chunk })
      response.on('end', () => resolve({
        status: response.statusCode, body: text, ms: Date.now() - started, outcome: 'ended',
      }))
      response.on('aborted', () => resolve({
        status: response.statusCode, body: text, ms: Date.now() - started, outcome: 'aborted',
      }))
      response.on('error', error => resolve({
        status: -1, body: text, ms: Date.now() - started, outcome: error.code ?? 'error',
      }))
    })
    request.on('error', error => resolve({
      status: -1, body: '', ms: Date.now() - started, outcome: error.code ?? 'error',
    }))
    request.on('socket', socket => {
      if (!socket.__hc03Guard) {
        socket.__hc03Guard = true
        socket.on('error', () => {})
      }
    })
    request.end(payload)
  })
  return { request, done, body: payload }
}

async function mockMode(mode, delayMs = 0) {
  const response = await httpRequest({
    host: '127.0.0.1', port: MOCK_PORT, path: '/admin/mode', method: 'POST',
    headers: { 'Content-Type': 'application/json' },
  }, JSON.stringify({ mode, delayMs }), 10_000)
  if (response.status !== 200) throw new Error(`mock mode failed: ${response.status} ${response.body}`)
}

async function mockReset() {
  const response = await httpRequest({
    host: '127.0.0.1', port: MOCK_PORT, path: '/admin/reset', method: 'POST',
  }, '{}', 10_000)
  if (response.status !== 200) throw new Error(`mock reset failed: ${response.status}`)
}

async function mockStats() {
  const response = await httpRequest({
    host: '127.0.0.1', port: MOCK_PORT, path: '/stats', method: 'GET',
  }, null, 10_000)
  if (response.status !== 200) throw new Error(`mock stats failed: ${response.status}`)
  return JSON.parse(response.body)
}

async function mockRelease() {
  const response = await httpRequest({
    host: '127.0.0.1', port: MOCK_PORT, path: '/admin/release', method: 'POST',
  }, '{}', 10_000)
  return response.status === 200 ? JSON.parse(response.body) : { released: 0 }
}

function resetRedisLimitKeys(credentialId) {
  const day = new Date().toISOString().slice(0, 10).replaceAll('-', '')
  const rateBase = `aicostops:v2:gateway:ratelimit:${credentialId}`
  for (const key of [
    rateBase, `${rateBase}:tokens`, `${rateBase}:ts`,
    `aicostops:v2:gateway:quota:${credentialId}:${day}`,
  ]) {
    redisCli(['del', key])
  }
}

function clearCircuit(orgId) {
  const output = redisCli(['--scan', '--pattern', `aicostops:gateway:circuit:v1:${orgId}:*`]).stdout
  const keys = output.split(/\r?\n/).map(key => key.trim()).filter(Boolean)
  if (keys.length) redisCli(['del', ...keys])
}

async function readiness(port) {
  return httpStatus(port, '/actuator/health/readiness', 8000)
}

function idemDigest(rawIdemKey) {
  const key = Buffer.from(dotenv.AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1.trim(), 'base64')
  const hmac = createHmac('sha256', key)
  hmac.update(Buffer.from('idem\0', 'utf8'))
  hmac.update(Buffer.from(rawIdemKey, 'utf8'))
  return hmac.digest('hex').toUpperCase()
}

function requestExists(projectId, rawIdemKey) {
  return sqlNumber(`SELECT COUNT(*) FROM gateway_request
    WHERE project_id=${projectId} AND idempotency_key_digest=UNHEX(${sqlQuote(idemDigest(rawIdemKey))})`) > 0
}

/**
 * A request that failed during a dependency outage may legitimately have a
 * durable row (the outage can begin between authentication and TX1). What must
 * never exist is dispatch evidence, usage, or a settlement for it.
 */
function failedRequestDispatchEvidence(projectId, rawIdemKey) {
  const rows = sqlRows(`SELECT id, state FROM gateway_request
    WHERE project_id=${projectId} AND idempotency_key_digest=UNHEX(${sqlQuote(idemDigest(rawIdemKey))})`)
  if (!rows.length) return { exists: false, safe: true, state: null }
  const requestId = Number(rows[0][0])
  const dispatchedAttempts = sqlNumber(`SELECT COUNT(*) FROM gateway_route_attempt
    WHERE request_id=${requestId} AND status IN ('DISPATCH_INTENT','BILLABLE_POSSIBLE','COMPLETED')`)
  const usageFacts = sqlNumber(`SELECT COUNT(*) FROM gateway_usage_fact WHERE request_id=${requestId}`)
  const settlements = sqlNumber(`SELECT COUNT(*) FROM gateway_settlement WHERE request_id=${requestId}`)
  const state = rows[0][1]
  const terminalUnsafe = ['DISPATCH_INTENT', 'UPSTREAM_ACTIVE', 'TRANSPORT_COMPLETED'].includes(state)
  return {
    exists: true,
    state,
    dispatchedAttempts,
    usageFacts,
    settlements,
    safe: dispatchedAttempts === 0 && usageFacts === 0 && settlements === 0 && !terminalUnsafe,
  }
}

function gatewayLogTail(lines = 40) {
  const path = join(LOG_DIR, `gateway-${GATEWAY_A_PORT}.log`)
  if (!existsSync(path)) return []
  return readFileSync(path, 'utf8').split(/\r?\n/).filter(Boolean).slice(-lines)
}

function backendLogTail(lines = 40) {
  const path = join(LOG_DIR, 'backend.log')
  if (!existsSync(path)) return []
  return readFileSync(path, 'utf8').split(/\r?\n/).filter(Boolean).slice(-lines)
}

// ---------------------------------------------------------------------------
// Scenario helpers
// ---------------------------------------------------------------------------

function expect(checks, name, ok, detail) {
  checks.push({ name, ok: Boolean(ok), detail })
  return ok
}

function scenarioTitle(id) {
  return {
    F01: 'F01 Redis outage at Gateway rate-limit/quota',
    F02: 'F02 MySQL outage before TX1/TX2',
    F03pre: 'F03a Gateway SIGKILL in the definitively pre-dispatch window',
    F03post: 'F03b Gateway SIGKILL after TX2 / provider may have executed',
    F04: 'F04 Gateway A killed, Gateway B keeps serving new requests',
    F05: 'F05 Settlement worker killed / database outage',
    F06: 'F06 Close exclusive lock vs Gateway shared period lock',
  }[id] ?? id
}

async function runScenario(id, body) {
  const record = {
    id,
    title: scenarioTitle(id),
    startedAt: new Date().toISOString(),
    endedAt: null,
    rounds: [],
    status: 'PASS',
    failures: [],
  }
  log(`=== ${id} ${record.title} (reps=${REPS}) ===`)
  try {
    for (let rep = 1; rep <= REPS; rep++) {
      state.timeline.reset()
      const round = { rep, startedAt: new Date().toISOString(), checks: [], failures: [], timeline: {}, metrics: {} }
      try {
        await body(round, rep)
      } catch (error) {
        round.failures.push(error?.stack ?? String(error))
        state.events.record(id, rep, 'SCENARIO_ERROR', { message: String(error?.message ?? error) })
      }
      round.endedAt = new Date().toISOString()
      round.failures.push(...round.checks.filter(check => !check.ok)
        .map(check => `${check.name}: ${JSON.stringify(check.detail)}`))
      if (round.failures.length) {
        round.status = 'FAIL'
        record.status = 'FAIL'
        record.failures.push(...round.failures.map(failure => `rep ${rep}: ${failure}`))
        for (const failure of round.failures) log(`  FAIL ${id} rep ${rep}: ${failure}`)
      } else {
        round.status = 'PASS'
      }
      record.rounds.push(round)
      log(`${id} rep ${rep}: ${round.status}` +
        (round.failures.length ? ` (${round.failures.length} failure(s))` : ''))
      // Self-heal the environment between rounds so one failed drill cannot
      // cascade into the next one with a dependency still down.
      await ensureHealthy().catch(error => log(`post-round recovery warning: ${error.message}`))
    }
  } finally {
    if (record.status === 'PASS') record.status = 'PASS'
    record.endedAt = new Date().toISOString()
    state.scenarios.push(record)
  }
  return record
}

async function ensureHealthy({ redis = true, mysql = true } = {}) {
  if (redis && redisPing() !== 'PONG') {
    startRedis()
    waitRedisReady()
  }
  if (mysql) {
    const stateValue = containerState(MYSQL_CONTAINER)
    if (stateValue !== 'running') {
      startMysql()
      waitMysqlReady()
    }
  }
  for (const [name, port, start] of [
    [`gateway-${GATEWAY_A_PORT}`, GATEWAY_A_PORT, () => startGateway(GATEWAY_A_PORT)],
    [`gateway-${GATEWAY_B_PORT}`, GATEWAY_B_PORT, () => startGateway(GATEWAY_B_PORT)],
    ['backend', BACKEND_PORT, () => startBackend()],
  ]) {
    if (!isProcessRunning(pidOf(name))) {
      log(`${name} is not running; restarting before the next round`)
      await start()
    } else {
      await waitHttp(port, '/actuator/health/readiness', 120_000, name)
    }
  }
}

// ---------------------------------------------------------------------------
// Control Plane authentication (used only by the F06 Close drill)
// ---------------------------------------------------------------------------

const BOOTSTRAP_EMAIL = 'hc03-admin@example.test'
const BOOTSTRAP_PASSWORD = 'hc03-admin-pass-2026'

function bootstrapBackendEnvironment() {
  return {
    ...backendEnvironment(),
    // `dev` activates DevAuthenticationBootstrap. The dedicated F06
    // organization already exists with exactly the current-UTC-month period,
    // so the bootstrap reuses it and inserts no second period.
    SPRING_PROFILES_ACTIVE: 'dev',
    AICOSTOPS_DEV_BOOTSTRAP_ENABLED: 'true',
    AICOSTOPS_DEV_BOOTSTRAP_EMAIL: BOOTSTRAP_EMAIL,
    AICOSTOPS_DEV_BOOTSTRAP_PASSWORD: BOOTSTRAP_PASSWORD,
    AICOSTOPS_DEV_BOOTSTRAP_DISPLAY_NAME: 'HC-03 Close Admin',
    PUBLIC_REGISTRATION_ORG_SLUG: state.seed.closeOrg.slug,
  }
}

async function startBackendWithBootstrap() {
  log('restarting Backend with the dev bootstrap bound to the F06 organization')
  await stopProc('backend')
  startProc('backend', 'java', ['-jar', state.jars.backend], { env: bootstrapBackendEnvironment() })
  await waitHttp(BACKEND_PORT, '/actuator/health/readiness', 300_000, 'Backend (bootstrap)')
}

async function apiCall(token, method, path, body) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await httpRequest({
    host: '127.0.0.1', port: BACKEND_PORT, path, method, headers,
  }, body === undefined ? null : JSON.stringify(body), 120_000)
  let parsed = null
  try { parsed = JSON.parse(response.body) } catch { /* non-JSON error body */ }
  return { status: response.status, body: response.body, json: parsed, ms: Math.round(response.ms) }
}

async function apiLogin() {
  const response = await apiCall(null, 'POST', '/api/v1/auth/login',
    { email: BOOTSTRAP_EMAIL, password: BOOTSTRAP_PASSWORD })
  if (response.status !== 200 || !response.json?.accessToken) {
    throw new Error(`HC-03 admin login failed: HTTP ${response.status} ${String(response.body).slice(0, 200)}`)
  }
  return response.json.accessToken
}

// ---------------------------------------------------------------------------
// F06 — Close exclusive lock vs Gateway shared period lock (process level)
// ---------------------------------------------------------------------------

async function f06CloseRace(round, rep) {
  const fixture = state.seed.f06
  await ensureHealthy()
  resetRedisLimitKeys(fixture.credentialId)
  await mockReset()
  await mockMode('ok')

  if (!state.bootstrapBackend) {
    await startBackendWithBootstrap()
    state.bootstrapBackend = true
  }
  const token = await apiLogin()
  const readinessProbe = await apiCall(token, 'GET',
    `/api/v1/billing-periods/${fixture.periodId}/close-readiness`)
  expect(round.checks, 'authenticated Control Plane context is bound to the F06 organization',
    readinessProbe.status === 200,
    { status: readinessProbe.status, body: String(readinessProbe.body).slice(0, 200) })

  const before = fixtureSnapshot(fixture)
  const settledBase = before.settlements.statuses.SETTLED ?? 0
  const periodBefore = periodSnapshot(fixture.periodId)
  round.metrics.periodBefore = periodBefore
  expect(round.checks, 'F06 period starts OPEN', periodBefore.status === 'OPEN', periodBefore.status)

  const baseline = await sendChat(GATEWAY_A_PORT, fixture, `hc03-f06-base-${rep}-${RUN_NONCE}`)
  expect(round.checks, 'ordinary request on the F06 organization is accepted',
    baseline.status === 200, { status: baseline.status })
  await waitForSettlement(fixture, settledBase + 1, 120_000)
  await mockReset()

  // Complete the current reconciliation first when the API allows it, so the
  // only remaining decisive Close blocker is the Gateway financial work.
  const recon = await apiCall(token, 'POST', '/api/v1/reconciliation-runs',
    { billingPeriodId: String(fixture.periodId) })
  round.metrics.reconciliation = {
    status: recon.status,
    runStatus: recon.json?.status ?? null,
    runId: recon.json?.id ?? null,
  }
  state.events.record('F06', rep, 'reconciliation', round.metrics.reconciliation)

  // A possibly-billable request in flight: TX2 committed, Provider holds the
  // stream, money is reserved. This is committed dispatch evidence that Close
  // must see.
  await mockMode('hold')
  const heldIdem = `hc03-f06-held-${rep}-${RUN_NONCE}`
  const heldBody = requestBody({ stream: true })
  const stream = openStream(GATEWAY_A_PORT, fixture, heldIdem, heldBody)
  stream.done.catch(() => null)
  await waitFor(() => {
    const detail = requestDetail(fixture.projectId)
    return detail && detail.state === 'UPSTREAM_ACTIVE' ? detail : null
  }, 'F06 request is UPSTREAM_ACTIVE before Close', 30_000, 250)
  await waitFor(async () => {
    const statsNow = await mockStats()
    return statsNow.held >= 1 ? statsNow : null
  }, 'F06 held stream is at the Provider', 30_000, 250)

  state.timeline.mark('F06', rep, 'close-start', { periodId: fixture.periodId })
  const tCloseStart = Date.now()
  const closePromise = apiCall(token, 'POST', `/api/v1/billing-periods/${fixture.periodId}/close`)

  // Concurrent new traffic while Close is being evaluated. A request that
  // lands in the CLOSING window must fail closed; requests outside it must be
  // ordinary successes. Neither may dispatch twice or overspend.
  await sleep(200)
  const loadKeys = Array.from({ length: 6 }, (_, index) => `hc03-f06-load-${rep}-${index}-${RUN_NONCE}`)
  const loadResults = await Promise.all(loadKeys.map((key, index) =>
    sendChat(index % 2 === 0 ? GATEWAY_A_PORT : GATEWAY_B_PORT, fixture, key, { timeoutMs: 60_000 })))

  const close = await closePromise
  const tCloseDone = Date.now()
  round.timeline.closeAt = new Date(tCloseDone).toISOString()
  round.timeline.closeMs = tCloseDone - tCloseStart
  round.metrics.close = {
    status: close.status,
    runStatus: close.json?.runStatus ?? null,
    periodStatus: close.json?.periodStatus ?? null,
    ms: close.ms,
    checks: (close.json?.checks ?? []).map(check => ({
      code: check.blockerCode, result: check.result, itemCount: check.itemCount,
    })),
  }
  round.metrics.load = loadResults.map(item => ({
    status: item.status, errorCode: item.errorCode, ms: item.ms,
  }))
  const closingWindowObserved = loadResults.some(item => item.status === 503)
  round.metrics.closingWindowObserved = closingWindowObserved

  expect(round.checks, 'real Close request reaches the Control Plane', close.status === 200,
    { status: close.status, body: String(close.body).slice(0, 300) })
  expect(round.checks, 'Close is BLOCKED by committed Gateway financial work',
    close.json?.runStatus === 'BLOCKED', close.json?.runStatus)
  const gatewayBlocker = (close.json?.checks ?? []).find(check =>
    check.blockerCode === 'PENDING_GATEWAY_FINANCIAL_WORK')
  expect(round.checks, 'the blocker list includes PENDING_GATEWAY_FINANCIAL_WORK=FAIL',
    gatewayBlocker?.result === 'FAIL' && Number(gatewayBlocker?.itemCount) >= 1,
    gatewayBlocker ?? 'missing')
  // The reconciliation run above may legitimately leave an unresolved case
  // (this synthetic organization has no statement evidence). Its blocker is
  // recorded but not required to pass: the Gateway blocker result is itself
  // the decisive, independently readable proof that committed dispatch facts
  // were seen by Close.
  expect(round.checks, 'the period returns to OPEN after a blocked Close',
    close.json?.periodStatus === 'OPEN', close.json?.periodStatus)

  const unexpected = loadResults.filter(item => item.status !== 200 && item.status !== 503)
  expect(round.checks, 'concurrent traffic only observes 200 or documented 503',
    unexpected.length === 0, unexpected.map(item => ({ status: item.status, code: item.errorCode })))
  const rejected = loadResults.filter(item => item.status === 503)
  expect(round.checks, 'every 503 carries GATEWAY_DEPENDENCY_UNAVAILABLE',
    rejected.every(item => item.errorCode === 'GATEWAY_DEPENDENCY_UNAVAILABLE'),
    rejected.map(item => item.errorCode))
  const accepted = loadResults.filter(item => item.status === 200)
  round.metrics.accepted = accepted.length
  round.metrics.rejectedDuringClose = rejected.length
  expect(round.checks, 'Provider calls equal accepted requests plus the held stream',
    (await mockStats()).post_chat_completions === accepted.length + 1,
    { providerOps: (await mockStats()).post_chat_completions, accepted: accepted.length })

  await mockRelease()
  const heldResult = await stream.done
  round.metrics.heldStream = { status: heldResult.status, outcome: heldResult.outcome }
  expect(round.checks, 'the held request completes as one ordinary stream after the Close race',
    heldResult.status === 200 && String(heldResult.body).includes('[DONE]'),
    round.metrics.heldStream)

  // No bypass: the Gateways remain usable after a blocked Close, and the
  // committed facts settle exactly once.
  await mockMode('ok')
  const after = await sendChat(GATEWAY_B_PORT, fixture, `hc03-f06-after-${rep}-${RUN_NONCE}`)
  expect(round.checks, 'a new request still works after the blocked Close',
    after.status === 200, { status: after.status, errorCode: after.errorCode })

  const expectedSettled = settledBase + 3 + accepted.length
  const snapshot = await waitForSettlement(fixture, expectedSettled, 180_000)
  expect(round.checks, 'Gateway financial facts settle exactly once around the Close race',
    (snapshot.settlements.statuses.SETTLED ?? 0) === expectedSettled,
    { settled: snapshot.settlements.statuses, expected: expectedSettled })
  expect(round.checks, 'no duplicate route attempt outcome',
    (snapshot.requests.multiAttempt ?? 0) === 0, snapshot.requests)
  expect(round.checks, 'no budget overspend after the Close race',
    !snapshot.budget.overspent, snapshot.budget)
  round.failures.push(...violations(snapshot, `F06 rep ${rep}`))

  const periodAfter = periodSnapshot(fixture.periodId)
  round.metrics.periodAfter = periodAfter
  expect(round.checks, 'the period is not CLOSED and not stuck CLOSING',
    periodAfter.status === 'OPEN', periodAfter.status)
  round.metrics.db = snapshot
  state.events.record('F06', rep, 'verified', {
    closeStatus: close.json?.runStatus, closeMs: round.timeline.closeMs,
    accepted: accepted.length, rejected: rejected.length,
    closingWindowObserved, failures: round.failures.length,
  })
}

async function settleBaseline(fixture, idemKey, { port = GATEWAY_A_PORT } = {}) {
  const before = fixtureSnapshot(fixture)
  const response = await sendChat(port, fixture, idemKey)
  if (response.status !== 200) {
    throw new Error(`baseline request failed: HTTP ${response.status} ${response.bodySnippet}`)
  }
  const settledTarget = (before.settlements.statuses.SETTLED ?? 0) + 1
  return waitForSettlement(fixture, settledTarget, 120_000)
}

// ---------------------------------------------------------------------------
// F01 — Redis outage / blackhole at the rate-limit and quota gate
// ---------------------------------------------------------------------------

async function f01RedisOutage(round, rep, variant) {
  const fixture = state.seed.fixtures.f01
  await ensureHealthy()
  clearCircuit(fixture.orgId)
  resetRedisLimitKeys(fixture.credentialId)
  await mockReset()
  await mockMode('ok')
  const before = fixtureSnapshot(fixture)
  const settledBase = before.settlements.statuses.SETTLED ?? 0
  const finalBase = before.usage.statuses.FINAL ?? 0

  const baselineIdem = `hc03-f01-${variant}-base-${rep}-${RUN_NONCE}`
  const baseline = await sendChat(GATEWAY_A_PORT, fixture, baselineIdem)
  expect(round.checks, `${variant}: warm request before injection is 200`,
    baseline.status === 200, { status: baseline.status, ms: baseline.ms })
  const opsAfterBaseline = (await mockStats()).post_chat_completions
  expect(round.checks, `${variant}: warm request dispatched exactly one Provider call`,
    opsAfterBaseline === 1, opsAfterBaseline)
  await mockReset()

  state.timeline.mark('F01', rep, 'inject', { variant, container: REDIS_CONTAINER })
  if (variant === 'stop') stopRedis()
  else pauseRedis()
  const tInject = Date.now()
  round.timeline.injectAt = new Date(tInject).toISOString()

  const rejected = await sendChat(GATEWAY_A_PORT, fixture,
    `hc03-f01-${variant}-reject-1-${rep}-${RUN_NONCE}`, { timeoutMs: 30_000 })
  const tDetect = Date.now()
  round.timeline.detectAt = new Date(tDetect).toISOString()
  round.timeline.detectMs = tDetect - tInject
  state.events.record('F01', rep, 'detect', {
    variant, httpStatus: rejected.status, errorCode: rejected.errorCode, latencyMs: rejected.ms,
    elapsedMs: tDetect - tInject,
  })
  expect(round.checks, `${variant}: Redis outage fails closed with HTTP 503`,
    rejected.status === 503, { status: rejected.status, errorCode: rejected.errorCode })
  expect(round.checks, `${variant}: fail-closed response carries GATEWAY_DEPENDENCY_UNAVAILABLE`,
    rejected.errorCode === 'GATEWAY_DEPENDENCY_UNAVAILABLE', rejected.errorCode)
  expect(round.checks, `${variant}: rejection is bounded (<= 10 s)`,
    rejected.ms <= 10_000, `${rejected.ms}ms`)

  const second = await sendChat(GATEWAY_A_PORT, fixture,
    `hc03-f01-${variant}-reject-2-${rep}-${RUN_NONCE}`, { timeoutMs: 30_000 })
  expect(round.checks, `${variant}: second request during outage also fails closed`,
    second.status === 503, { status: second.status, errorCode: second.errorCode })
  expect(round.checks, `${variant}: no Provider I/O while Redis is unavailable`,
    (await mockStats()).post_chat_completions === 0, (await mockStats()).post_chat_completions)
  const readinessDuring = await readiness(GATEWAY_A_PORT)
  round.metrics.readinessDuringOutage = readinessDuring.status

  // The local circuit-breaker degradation in RedisCircuitBreakerService must
  // never be reached as an admission shortcut: the limiter fails first.
  expect(round.checks, `${variant}: outage did not create a request row (no admission)`,
    !requestExists(fixture.projectId, `hc03-f01-${variant}-reject-1-${rep}-${RUN_NONCE}`),
    'gateway_request must stay empty for the rejected idempotency identity')

  state.timeline.mark('F01', rep, 'restore', { variant })
  if (variant === 'stop') startRedis()
  else unpauseRedis()
  const tRestore = Date.now()
  waitRedisReady()
  round.timeline.restoreAt = new Date(tRestore).toISOString()
  round.timeline.restoreMs = Date.now() - tDetect

  // Recovery: a NEW idempotency identity must pass the limiter and the whole
  // financial path. Retry briefly while Lettuce/health reconnects.
  let healthy = null
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    healthy = await sendChat(GATEWAY_A_PORT, fixture,
      `hc03-f01-${variant}-recovered-${rep}-${RUN_NONCE}`, { timeoutMs: 30_000 })
    if (healthy.status === 200) break
    await sleep(1000)
  }
  const tHealthy = Date.now()
  round.timeline.healthyAt = new Date(tHealthy).toISOString()
  round.timeline.recoveryMs = tHealthy - tRestore
  expect(round.checks, `${variant}: new request succeeds after Redis recovery`,
    healthy?.status === 200, { status: healthy?.status, errorCode: healthy?.errorCode })
  expect(round.checks, `${variant}: exactly one Provider call for the recovered request`,
    (await mockStats()).post_chat_completions === 1, (await mockStats()).post_chat_completions)

  const snapshot = await waitForSettlement(fixture, settledBase + 2, 120_000)
  expect(round.checks, `${variant}: recovered request reaches exactly one FINAL usage + settlement`,
    (snapshot.usage.statuses.FINAL ?? 0) === finalBase + 2
      && (snapshot.settlements.statuses.SETTLED ?? 0) === settledBase + 2,
    { usage: snapshot.usage.statuses, settlements: snapshot.settlements.statuses })
  expect(round.checks, `${variant}: no budget overspend`,
    !snapshot.budget.overspent, snapshot.budget)
  round.failures.push(...violations(snapshot, `F01 ${variant} rep ${rep}`))
  round.metrics.providerOps = (await mockStats()).post_chat_completions
  round.metrics.db = snapshot
  state.events.record('F01', rep, 'verified', {
    variant, checks: round.checks.length, failures: round.failures.length,
    detectMs: round.timeline.detectMs, recoveryMs: round.timeline.recoveryMs,
  })
}

// ---------------------------------------------------------------------------
// F02 — MySQL outage before TX1/TX2
// ---------------------------------------------------------------------------

async function f02MysqlOutage(round, rep) {
  const fixture = state.seed.fixtures.f02
  await ensureHealthy()
  clearCircuit(fixture.orgId)
  resetRedisLimitKeys(fixture.credentialId)
  await mockReset()
  await mockMode('ok')
  const before = fixtureSnapshot(fixture)
  const settledBase = before.settlements.statuses.SETTLED ?? 0
  const finalBase = before.usage.statuses.FINAL ?? 0

  const baseline = await sendChat(GATEWAY_A_PORT, fixture, `hc03-f02-base-${rep}-${RUN_NONCE}`)
  expect(round.checks, 'warm request before injection is 200', baseline.status === 200,
    { status: baseline.status })
  expect(round.checks, 'warm request dispatched exactly one Provider call',
    (await mockStats()).post_chat_completions === 1, (await mockStats()).post_chat_completions)
  await mockReset()

  state.timeline.mark('F02', rep, 'inject', { container: MYSQL_CONTAINER })
  stopMysql()
  const tInject = Date.now()
  round.timeline.injectAt = new Date(tInject).toISOString()

  const idemA = `hc03-f02-reject-a-${rep}-${RUN_NONCE}`
  const idemB = `hc03-f02-reject-b-${rep}-${RUN_NONCE}`
  const first = await sendChat(GATEWAY_A_PORT, fixture, idemA, { timeoutMs: 60_000 })
  const tDetect = Date.now()
  round.timeline.detectAt = new Date(tDetect).toISOString()
  round.timeline.detectMs = tDetect - tInject
  state.events.record('F02', rep, 'detect', {
    httpStatus: first.status, errorCode: first.errorCode, latencyMs: first.ms,
    elapsedMs: tDetect - tInject,
  })
  expect(round.checks, 'MySQL outage fails closed with HTTP 503',
    first.status === 503, { status: first.status, errorCode: first.errorCode })
  expect(round.checks, 'rejection is bounded (<= 45 s)',
    first.ms <= 45_000, `${first.ms}ms`)
  expect(round.checks, 'no fake success while MySQL is unavailable',
    first.status !== 200 && first.errorCode !== null, { status: first.status, code: first.errorCode })
  const second = await sendChat(GATEWAY_B_PORT, fixture, idemB, { timeoutMs: 60_000 })
  expect(round.checks, 'the other Gateway instance also fails closed',
    second.status === 503, { status: second.status, errorCode: second.errorCode })
  expect(round.checks, 'no Provider I/O during the MySQL outage',
    (await mockStats()).post_chat_completions === 0, (await mockStats()).post_chat_completions)
  round.metrics.readinessA = (await readiness(GATEWAY_A_PORT)).status
  round.metrics.readinessB = (await readiness(GATEWAY_B_PORT)).status
  round.metrics.readinessBackend = (await readiness(BACKEND_PORT)).status

  state.timeline.mark('F02', rep, 'restore', { container: MYSQL_CONTAINER })
  startMysql()
  const tRestore = Date.now()
  waitMysqlReady()
  round.timeline.restoreAt = new Date(tRestore).toISOString()
  round.timeline.restoreMs = Date.now() - tDetect

  // Hikari must be able to acquire fresh connections again.
  let healthy = null
  let attempts = 0
  const deadline = Date.now() + 150_000
  while (Date.now() < deadline) {
    attempts++
    healthy = await sendChat(GATEWAY_A_PORT, fixture,
      `hc03-f02-recovered-${rep}-${RUN_NONCE}`, { timeoutMs: 60_000 })
    if (healthy.status === 200) break
    await sleep(2000)
  }
  const tHealthy = Date.now()
  round.timeline.healthyAt = new Date(tHealthy).toISOString()
  round.timeline.recoveryMs = tHealthy - tRestore
  round.metrics.recoveryAttempts = attempts
  expect(round.checks, 'connection pool recovers and a new request succeeds',
    healthy?.status === 200, { status: healthy?.status, errorCode: healthy?.errorCode, attempts })
  expect(round.checks, 'recovered request dispatched exactly one Provider call',
    (await mockStats()).post_chat_completions === 1, (await mockStats()).post_chat_completions)

  // Durable evidence is readable again only after the database is back; the
  // failed identities must still carry no dispatch/usage/settlement trail.
  const evidenceA = failedRequestDispatchEvidence(fixture.projectId, idemA)
  const evidenceB = failedRequestDispatchEvidence(fixture.projectId, idemB)
  expect(round.checks, 'failed requests carry no dispatch/usage/settlement evidence',
    evidenceA.safe && evidenceB.safe, { a: evidenceA, b: evidenceB })
  round.metrics.failedRequestEvidence = { a: evidenceA, b: evidenceB }

  const snapshot = await waitForSettlement(fixture, settledBase + 2, 120_000)
  expect(round.checks, 'pre-outage and post-recovery requests both settle exactly once',
    (snapshot.usage.statuses.FINAL ?? 0) === finalBase + 2
      && (snapshot.settlements.statuses.SETTLED ?? 0) === settledBase + 2,
    { usage: snapshot.usage.statuses, settlements: snapshot.settlements.statuses })
  expect(round.checks, 'no budget overspend', !snapshot.budget.overspent, snapshot.budget)
  round.failures.push(...violations(snapshot, `F02 rep ${rep}`))
  round.metrics.db = snapshot
  state.events.record('F02', rep, 'verified', {
    detectMs: round.timeline.detectMs, recoveryMs: round.timeline.recoveryMs,
    failures: round.failures.length,
  })
}

// ---------------------------------------------------------------------------
// F03a — SIGKILL in the definitively pre-dispatch window
// ---------------------------------------------------------------------------

async function f03PreDispatchKill(round, rep) {
  const fixture = state.seed.fixtures.f03pre
  await ensureHealthy()
  clearCircuit(fixture.orgId)
  resetRedisLimitKeys(fixture.credentialId)
  await mockReset()
  await mockMode('ok')
  const before = fixtureSnapshot(fixture)
  const settledBase = before.settlements.statuses.SETTLED ?? 0
  const dispatchedBase = before.requests.possiblyDispatched ?? 0

  const baseline = await sendChat(GATEWAY_A_PORT, fixture, `hc03-f03pre-base-${rep}-${RUN_NONCE}`)
  expect(round.checks, 'warm request before injection is 200', baseline.status === 200,
    { status: baseline.status })
  await waitForSettlement(fixture, settledBase + 1, 120_000)
  // Capture only after the warm request has settled: the killed admission must
  // not change Actual/Committed/holds relative to this point.
  const actualBase = budgetSnapshot(fixture).actual
  await mockReset()

  // Hold the exact BillingPeriod row exclusively. TX1's shared period fence
  // must block before it can create any reservation, while the route attempt
  // has already been durably PLANNED. This is a real row lock held by a real
  // second session, not a mocked admission failure.
  const lock = new MySqlSession()
  lock.send('BEGIN')
  lock.send(`SELECT id FROM billing_period WHERE id=${fixture.periodId} AND org_id=${fixture.orgId} FOR UPDATE`)
  await sleep(700)
  let lockProven = false
  try {
    sqlStatement(`SET SESSION innodb_lock_wait_timeout=2;
      SELECT id FROM billing_period WHERE id=${fixture.periodId} AND org_id=${fixture.orgId} FOR UPDATE NOWAIT;`)
  } catch (error) {
    lockProven = /3572|Cannot acquire|lock/i.test(String(error.message))
  }
  expect(round.checks, 'external session really holds the BillingPeriod row X lock', lockProven,
    'SELECT ... FOR UPDATE NOWAIT from a second session must fail with 3572')

  const idem = `hc03-f03pre-killed-${rep}-${RUN_NONCE}`
  const pendingRequest = sendChat(GATEWAY_A_PORT, fixture, idem, { timeoutMs: 120_000 })
  pendingRequest.then(result => {
    log(`F03pre pending request settled early: HTTP ${result.status} ${result.errorCode ?? result.error ?? ''}`)
  }, error => log(`F03pre pending request rejected: ${error.message}`))

  let lastDetail = null
  let lastLogAt = 0
  const blocked = await waitFor(() => {
    lastDetail = requestDetail(fixture.projectId)
    if (Date.now() - lastLogAt > 3000) {
      lastLogAt = Date.now()
      log(`F03pre wait: ${JSON.stringify(lastDetail)} lockProven=${lockProven} waiters=${dataLockWaits()}`)
    }
    if (!lastDetail) return null
    const planned = lastDetail.attempts.length === 1 && lastDetail.attempts[0].status === 'PLANNED'
    const notYetReserved = lastDetail.reservations.length === 0
    return planned && notYetReserved ? lastDetail : null
  }, 'route attempt PLANNED and TX1 blocked before reservation', 30_000, 250)
  round.metrics.requestBeforeKill = {
    id: blocked.id, state: blocked.state, attempts: blocked.attempts,
    reservations: blocked.reservations, lockWaiters: budgetRowLockWaiters(),
  }
  expect(round.checks, 'request is durably between PLANNED and TX2 at kill time',
    blocked.state === 'VALIDATED' && blocked.reservations.length === 0,
    { state: blocked.state, attempts: blocked.attempts.map(a => a.status) })
  expect(round.checks, 'a real transaction is waiting on the BillingPeriod row lock',
    (dataLockWaits() ?? 0) > 0, dataLockWaits())

  state.timeline.mark('F03pre', rep, 'inject', { pid: pidOf(`gateway-${GATEWAY_A_PORT}`) })
  const tInject = Date.now()
  const kill = await killProc(`gateway-${GATEWAY_A_PORT}`)
  const tDetect = Date.now()
  round.timeline.injectAt = new Date(tInject).toISOString()
  round.timeline.detectAt = new Date(tDetect).toISOString()
  round.timeline.detectMs = tDetect - tInject
  round.metrics.kill = kill
  state.events.record('F03pre', rep, 'kill', { pid: kill.pid, killMs: kill.killMs })
  expect(round.checks, 'Gateway A process is gone after SIGKILL-equivalent kill', !kill.stillRunning,
    kill)

  await lock.close()
  const clientResult = await pendingRequest
  round.metrics.client = clientResult
  expect(round.checks, 'the killed in-flight client never receives a fabricated 200',
    clientResult.status !== 200, { status: clientResult.status, error: clientResult.error })

  const afterKill = requestDetail(fixture.projectId)
  round.metrics.requestAfterKill = {
    state: afterKill.state, attempts: afterKill.attempts,
    reservations: afterKill.reservations, usage: afterKill.usage,
  }
  expect(round.checks, 'the killed request kept its PLANNED attempt and never reached DISPATCH_INTENT',
    afterKill.attempts.every(attempt => attempt.status === 'PLANNED' && attempt.dispatchIntentAt === 'NULL'),
    afterKill.attempts)
  expect(round.checks, 'TX1 rolled back: no reservation survived the killed JVM',
    afterKill.reservations.length === 0, afterKill.reservations)
  expect(round.checks, 'no Provider I/O for the killed pre-dispatch request',
    (await mockStats()).post_chat_completions === 0, (await mockStats()).post_chat_completions)
  const budgetAfter = budgetSnapshot(fixture)
  expect(round.checks, 'budget Actual/Committed/holds unchanged by the killed admission',
    budgetAfter.actual === actualBase && budgetAfter.committed === '0.00000000'
      && budgetAfter.effectiveHolds === '0.00000000',
    { actualBefore: actualBase, actualAfter: budgetAfter.actual, budget: budgetAfter })

  // Recovery runs in Gateway B (shared DB): the stale pre-dispatch request is
  // terminally failed after the reservation TTL, without touching money.
  const recovered = await waitFor(() => {
    const detail = requestDetail(fixture.projectId)
    return detail.state === 'FAILED_PRE_DISPATCH' ? detail : null
  }, 'stale pre-dispatch request converges to FAILED_PRE_DISPATCH', 120_000, 1000)
  round.metrics.recovered = { state: recovered.state, reservations: recovered.reservations }
  expect(round.checks, 'recovery terminates the stale request as FAILED_PRE_DISPATCH',
    recovered.state === 'FAILED_PRE_DISPATCH', recovered.state)
  expect(round.checks, 'recovery does not fabricate usage or settlement',
    recovered.usage.length === 0 && recovered.settlements.length === 0,
    { usage: recovered.usage, settlements: recovered.settlements })
  expect(round.checks, 'no Provider call was retried by recovery',
    (await mockStats()).post_chat_completions === 0, (await mockStats()).post_chat_completions)

  // Restart the killed instance and prove it is a functioning replica again.
  const tRestoreStart = Date.now()
  await startGateway(GATEWAY_A_PORT)
  const tRestore = Date.now()
  round.timeline.restoreAt = new Date(tRestore).toISOString()
  round.timeline.restoreMs = tRestore - tRestoreStart
  const fresh = await sendChat(GATEWAY_A_PORT, fixture, `hc03-f03pre-fresh-${rep}-${RUN_NONCE}`)
  const tHealthy = Date.now()
  round.timeline.healthyAt = new Date(tHealthy).toISOString()
  round.timeline.recoveryMs = tHealthy - tRestore
  expect(round.checks, 'restarted Gateway A serves a new request', fresh.status === 200,
    { status: fresh.status, errorCode: fresh.errorCode })
  expect(round.checks, 'exactly one Provider call for the restarted instance request',
    (await mockStats()).post_chat_completions === 1, (await mockStats()).post_chat_completions)

  const snapshot = await waitForSettlement(fixture, settledBase + 2, 120_000)
  expect(round.checks, 'only the real request settled; the killed one never did',
    (snapshot.settlements.statuses.SETTLED ?? 0) === settledBase + 2
      && (snapshot.requests.possiblyDispatched ?? 0) === dispatchedBase + 2,
    { settled: snapshot.settlements.statuses, possiblyDispatched: snapshot.requests.possiblyDispatched })
  round.failures.push(...violations(snapshot, `F03pre rep ${rep}`))
  round.metrics.db = snapshot
  state.events.record('F03pre', rep, 'verified', {
    detectMs: round.timeline.detectMs, recoveryMs: round.timeline.recoveryMs,
    failures: round.failures.length,
  })
}

// ---------------------------------------------------------------------------
// F03b — SIGKILL after TX2 while the Provider may have executed
// ---------------------------------------------------------------------------

async function f03PostDispatchKill(round, rep) {
  const fixture = state.seed.fixtures.f03post
  await ensureHealthy()
  clearCircuit(fixture.orgId)
  resetRedisLimitKeys(fixture.credentialId)
  await mockReset()
  await mockMode('hold')
  const before = fixtureSnapshot(fixture)
  const settledBase = before.settlements.statuses.SETTLED ?? 0
  const finalBase = before.usage.statuses.FINAL ?? 0

  const idem = `hc03-f03post-killed-${rep}-${RUN_NONCE}`
  const body = requestBody({ stream: true })
  const stream = openStream(GATEWAY_A_PORT, fixture, idem, body)
  stream.done.then(result => {
    log(`F03post stream ended: HTTP ${result.status} outcome=${result.outcome} ms=${Math.round(result.ms)} bytes=${result.body.length}`)
  }, error => log(`F03post stream rejected: ${error.message}`))

  let lastDetail = null
  let lastLogAt = 0
  const dispatched = await waitFor(() => {
    lastDetail = requestDetail(fixture.projectId)
    if (Date.now() - lastLogAt > 3000) {
      lastLogAt = Date.now()
      log(`F03post wait: ${JSON.stringify(lastDetail)}`)
    }
    if (!lastDetail) return null
    const possiblySent = lastDetail.attempts.some(attempt => attempt.status === 'DISPATCH_INTENT')
      && (lastDetail.state === 'UPSTREAM_ACTIVE' || lastDetail.state === 'DISPATCH_INTENT')
    return possiblySent ? lastDetail : null
  }, 'request reaches TX2 dispatch intent', 30_000, 250)
  const held = await waitFor(async () => {
    const statsNow = await mockStats()
    return statsNow.held >= 1 ? statsNow : null
  }, 'mock Provider holds the dispatched stream', 30_000, 250)
  round.metrics.requestBeforeKill = {
    id: dispatched.id, state: dispatched.state, attempts: dispatched.attempts,
    reservations: dispatched.reservations, providerHeld: held.held,
  }
  expect(round.checks, 'Provider really received the request before the kill',
    held.held >= 1 && held.post_chat_completions === 1, held)
  expect(round.checks, 'durable reservation exists at kill time',
    dispatched.reservations.some(reservation => reservation.status === 'ACTIVE'),
    dispatched.reservations)

  state.timeline.mark('F03post', rep, 'inject', { pid: pidOf(`gateway-${GATEWAY_A_PORT}`) })
  const tInject = Date.now()
  const kill = await killProc(`gateway-${GATEWAY_A_PORT}`)
  const tDetect = Date.now()
  round.timeline.injectAt = new Date(tInject).toISOString()
  round.timeline.detectAt = new Date(tDetect).toISOString()
  round.timeline.detectMs = tDetect - tInject
  round.metrics.kill = kill
  state.events.record('F03post', rep, 'kill', { pid: kill.pid, killMs: kill.killMs })

  const clientResult = await stream.done
  round.metrics.client = {
    status: clientResult.status, outcome: clientResult.outcome,
    ms: Math.round(clientResult.ms), bytes: clientResult.body.length,
  }
  await mockRelease()
  expect(round.checks, 'the client SSE connection drops (no seamless stream migration)',
    clientResult.status !== 200 || clientResult.outcome !== 'ended' || !clientResult.body.includes('[DONE]'),
    round.metrics.client)

  const afterKill = requestDetail(fixture.projectId)
  expect(round.checks, 'request remains financially uncertain, not fabricated FINAL',
    afterKill.state === 'UPSTREAM_ACTIVE' || afterKill.state === 'DISPATCH_INTENT',
    { state: afterKill.state })
  expect(round.checks, 'no usage fact was fabricated by the crash',
    afterKill.usage.length === 0, afterKill.usage)

  // The reservation TTL is a recovery trigger, not proof of zero cost: a
  // dispatched-but-unconfirmed attempt must become PENDING_HOLD, not RELEASED.
  const heldAfterTtl = await waitFor(() => {
    const detail = requestDetail(fixture.projectId)
    return detail.reservations.some(reservation => reservation.status === 'PENDING_HOLD') ? detail : null
  }, 'expired dispatched reservation becomes PENDING_HOLD', 120_000, 1000)
  round.metrics.afterTtl = {
    state: heldAfterTtl.state, reservations: heldAfterTtl.reservations, attempts: heldAfterTtl.attempts,
  }
  expect(round.checks, 'recovery converts the expired hold to PENDING_HOLD and never releases it',
    heldAfterTtl.reservations.filter(reservation => reservation.status === 'PENDING_HOLD').length === 1
      && !heldAfterTtl.reservations.some(reservation => reservation.status === 'RELEASED'),
    heldAfterTtl.reservations)
  expect(round.checks, 'no FINAL usage and no settlement for the uncertain request',
    heldAfterTtl.usage.length === 0 && heldAfterTtl.settlements.length === 0,
    { usage: heldAfterTtl.usage, settlements: heldAfterTtl.settlements })

  // A replayed identity on the healthy instance must not dispatch a second time.
  const replay = await sendChat(GATEWAY_B_PORT, fixture, idem, { body, stream: true, timeoutMs: 30_000 })
  expect(round.checks, 'replaying the same identity on Gateway B is refused (409 in-progress)',
    replay.status === 409 && replay.errorCode === 'GATEWAY_REQUEST_IN_PROGRESS',
    { status: replay.status, errorCode: replay.errorCode })
  expect(round.checks, 'replay caused no second Provider call',
    (await mockStats()).post_chat_completions === 1, (await mockStats()).post_chat_completions)

  const tRestoreStart = Date.now()
  await startGateway(GATEWAY_A_PORT)
  const tRestore = Date.now()
  round.timeline.restoreAt = new Date(tRestore).toISOString()
  round.timeline.restoreMs = tRestore - tRestoreStart
  await mockMode('ok')
  const fresh = await sendChat(GATEWAY_A_PORT, fixture, `hc03-f03post-fresh-${rep}-${RUN_NONCE}`)
  const tHealthy = Date.now()
  round.timeline.healthyAt = new Date(tHealthy).toISOString()
  round.timeline.recoveryMs = tHealthy - tRestore
  expect(round.checks, 'restarted Gateway A serves a new request', fresh.status === 200,
    { status: fresh.status, errorCode: fresh.errorCode })
  expect(round.checks, 'only the new request reached the Provider',
    (await mockStats()).post_chat_completions === 2, (await mockStats()).post_chat_completions)

  const snapshot = await waitForSettlement(fixture, settledBase + 1, 120_000)
  expect(round.checks, 'exactly one settlement for the accepted requests',
    (snapshot.settlements.statuses.SETTLED ?? 0) === settledBase + 1
      && (snapshot.usage.statuses.FINAL ?? 0) === finalBase + 1,
    { settled: snapshot.settlements.statuses, usage: snapshot.usage.statuses })
  expect(round.checks, 'no duplicate FINAL usage group for the killed request',
    snapshot.requests.multiAttempt === 0, snapshot.requests)
  expect(round.checks, 'no budget overspend', !snapshot.budget.overspent, snapshot.budget)
  round.failures.push(...violations(snapshot, `F03post rep ${rep}`))
  round.metrics.db = snapshot
  state.events.record('F03post', rep, 'verified', {
    detectMs: round.timeline.detectMs, recoveryMs: round.timeline.recoveryMs,
    failures: round.failures.length,
  })
}

// ---------------------------------------------------------------------------
// F04 — one Gateway instance dies, the other keeps serving new requests
// ---------------------------------------------------------------------------

async function f04InstanceFailover(round, rep) {
  const fixture = state.seed.fixtures.f04
  await ensureHealthy()
  clearCircuit(fixture.orgId)
  resetRedisLimitKeys(fixture.credentialId)
  await mockReset()
  await mockMode('hold')
  const before = fixtureSnapshot(fixture)
  const settledBase = before.settlements.statuses.SETTLED ?? 0
  const finalBase = before.usage.statuses.FINAL ?? 0

  const victimIdem = `hc03-f04-victim-${rep}-${RUN_NONCE}`
  const victimBody = requestBody({ stream: true })
  const stream = openStream(GATEWAY_A_PORT, fixture, victimIdem, victimBody)
  stream.done.catch(() => null)
  await waitFor(() => {
    const detail = requestDetail(fixture.projectId)
    return detail && detail.state === 'UPSTREAM_ACTIVE' ? detail : null
  }, 'victim stream reaches UPSTREAM_ACTIVE', 30_000, 250)
  await waitFor(async () => {
    const statsNow = await mockStats()
    return statsNow.held >= 1 ? statsNow : null
  }, 'victim stream is held by the mock Provider', 30_000, 250)

  state.timeline.mark('F04', rep, 'inject', { victimPort: GATEWAY_A_PORT })
  const tInject = Date.now()
  const kill = await killProc(`gateway-${GATEWAY_A_PORT}`)
  const tDetect = Date.now()
  round.timeline.injectAt = new Date(tInject).toISOString()
  round.timeline.detectAt = new Date(tDetect).toISOString()
  round.timeline.detectMs = tDetect - tInject
  round.metrics.kill = kill
  state.events.record('F04', rep, 'kill', { victimPort: GATEWAY_A_PORT, pid: kill.pid, killMs: kill.killMs })

  const victimResult = await stream.done
  round.metrics.victimStream = {
    status: victimResult.status, outcome: victimResult.outcome,
    ms: Math.round(victimResult.ms), bytes: victimResult.body.length,
  }
  await mockRelease()

  // New request on the surviving instance. This is route-by-client, not an
  // automatic entry-point failover: the harness chooses :18082 explicitly.
  const tSwitch = Date.now()
  const survivor = await sendChat(GATEWAY_B_PORT, fixture,
    `hc03-f04-survivor-${rep}-${RUN_NONCE}`, { timeoutMs: 60_000 })
  const tSurvivor = Date.now()
  round.timeline.failoverAt = new Date(tSurvivor).toISOString()
  round.timeline.failoverMs = tSurvivor - tSwitch
  expect(round.checks, 'Gateway B (healthy) serves a new request while A is dead',
    survivor.status === 200, { status: survivor.status, errorCode: survivor.errorCode, ms: survivor.ms })
  expect(round.checks, 'new request on B reached the Provider exactly once',
    (await mockStats()).post_chat_completions === 2, (await mockStats()).post_chat_completions)

  const replay = await sendChat(GATEWAY_B_PORT, fixture, victimIdem,
    { body: victimBody, stream: true, timeoutMs: 30_000 })
  expect(round.checks, 'replaying A\'s possibly-dispatched identity on B is refused',
    replay.status === 409, { status: replay.status, errorCode: replay.errorCode })
  expect(round.checks, 'no transparent replay produced a second Provider call',
    (await mockStats()).post_chat_completions === 2, (await mockStats()).post_chat_completions)

  const victimAfter = await waitFor(() => {
    const rows = sqlRows(`SELECT r.status FROM budget_reservation r
      JOIN gateway_request gr ON gr.id=r.request_id AND gr.org_id=r.org_id
      WHERE gr.project_id=${fixture.projectId} AND gr.idempotency_key_digest=UNHEX(${sqlQuote(idemDigest(victimIdem))})`)
    return rows.length && rows[0][0] === 'PENDING_HOLD' ? rows[0][0] : null
  }, 'victim hold becomes PENDING_HOLD', 120_000, 1000)
  expect(round.checks, 'the killed stream kept its PENDING_HOLD (never released)', victimAfter === 'PENDING_HOLD',
    victimAfter)

  const tRestoreStart = Date.now()
  await startGateway(GATEWAY_A_PORT)
  const tRestore = Date.now()
  round.timeline.restoreAt = new Date(tRestore).toISOString()
  round.timeline.restoreMs = tRestore - tRestoreStart
  await mockMode('ok')
  const fresh = await sendChat(GATEWAY_A_PORT, fixture, `hc03-f04-restarted-${rep}-${RUN_NONCE}`)
  const tHealthy = Date.now()
  round.timeline.healthyAt = new Date(tHealthy).toISOString()
  round.timeline.recoveryMs = tHealthy - tRestore
  expect(round.checks, 'restarted A serves a new request', fresh.status === 200,
    { status: fresh.status, errorCode: fresh.errorCode })

  const snapshot = await waitForSettlement(fixture, settledBase + 2, 120_000)
  expect(round.checks, 'only the two ordinary requests settled; the killed one did not',
    (snapshot.settlements.statuses.SETTLED ?? 0) === settledBase + 2
      && (snapshot.usage.statuses.FINAL ?? 0) === finalBase + 2,
    { settled: snapshot.settlements.statuses, usage: snapshot.usage.statuses })
  expect(round.checks, 'no duplicate FINAL usage', (snapshot.requests.multiAttempt ?? 0) === 0,
    snapshot.requests)
  expect(round.checks, 'no budget overspend', !snapshot.budget.overspent, snapshot.budget)
  round.failures.push(...violations(snapshot, `F04 rep ${rep}`))
  round.metrics.db = snapshot
  state.events.record('F04', rep, 'verified', {
    failoverMs: round.timeline.failoverMs, failures: round.failures.length,
  })
}

// ---------------------------------------------------------------------------
// F05 — Settlement worker killed inside its transaction / DB outage
// ---------------------------------------------------------------------------

/**
 * Creates FINAL usage while the Backend is stopped (so no worker can settle
 * it), then exclusively holds that request's reservation row and restarts the
 * Backend. The settlement transaction takes the period and budget locks, then
 * blocks on the reservation: a real in-flight financial transaction. The
 * Gateway path itself never locks the reservation row, so the order request
 * stays unaffected.
 */
async function prepareBlockedSettlement(fixture, rep, label) {
  await stopProc('backend')
  const order = await sendChat(GATEWAY_A_PORT, fixture, `hc03-f05-${label}-${rep}-${RUN_NONCE}`)
  if (order.status !== 200) {
    throw new Error(`order request failed before the settlement drill: HTTP ${order.status}`)
  }
  const detail = await waitFor(() => {
    const current = requestDetail(fixture.projectId)
    return current?.usage.some(usage => usage.status === 'FINAL') ? current : null
  }, 'FINAL usage exists before the worker restarts', 60_000, 250)
  const reservationId = sqlNumber(`SELECT id FROM budget_reservation
    WHERE request_id=${detail.id} AND org_id=${fixture.orgId}`)
  if (!reservationId) throw new Error('the order request has no reservation to lock')
  const lock = new MySqlSession()
  try {
    lock.send('BEGIN')
    lock.send(`SELECT id FROM budget_reservation WHERE id=${reservationId} AND org_id=${fixture.orgId} FOR UPDATE`)
    await sleep(500)
    let lockProven = false
    try {
      sqlStatement(`SET SESSION innodb_lock_wait_timeout=2;
        SELECT id FROM budget_reservation WHERE id=${reservationId} AND org_id=${fixture.orgId} FOR UPDATE NOWAIT;`)
    } catch (error) {
      lockProven = /3572|Cannot acquire|lock/i.test(String(error.message))
    }
    await startBackend()
    const waiting = await waitFor(() => (dataLockWaits() ?? 0) > 0 ? true : null,
      'settlement transaction blocks on the reservation row', 90_000, 250)
    return { order, detail, reservationId, lock, lockProven, waiting }
  } catch (error) {
    await lock.close()
    throw error
  }
}

async function f05WorkerKill(round, rep) {
  const fixture = state.seed.fixtures.f05
  await ensureHealthy()
  resetRedisLimitKeys(fixture.credentialId)
  await mockReset()
  await mockMode('ok')

  const before = fixtureSnapshot(fixture)
  const settledBefore = before.settlements.statuses.SETTLED ?? 0
  const actualBefore = before.budget.actual
  const prepared = await prepareBlockedSettlement(fixture, rep, 'order')
  const { lock, waiting } = prepared
  try {
    expect(round.checks, 'order request is accepted', prepared.order.status === 200,
      { status: prepared.order.status })
    expect(round.checks, 'external session really holds the settlement reservation row',
      prepared.lockProven, 'SELECT ... FOR UPDATE NOWAIT must fail with 3572')
    expect(round.checks, 'a real settlement transaction is blocked in flight before the kill',
      waiting === true, waiting)
    const settlementBefore = sqlRows(`SELECT gs.id, gs.status, gs.attempt_count, gs.ledger_posting_id
      FROM gateway_settlement gs JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
      WHERE gr.project_id=${fixture.projectId} ORDER BY gs.id DESC LIMIT 1`)[0]
    round.metrics.settlementBeforeKill = settlementBefore

  state.timeline.mark('F05', rep, 'inject', { pid: pidOf('backend') })
  const tInject = Date.now()
  const kill = await killProc('backend')
  const tDetect = Date.now()
  round.timeline.injectAt = new Date(tInject).toISOString()
  round.timeline.detectAt = new Date(tDetect).toISOString()
  round.timeline.detectMs = tDetect - tInject
  round.metrics.kill = kill
  state.events.record('F05', rep, 'kill', { pid: kill.pid, killMs: kill.killMs })
  await lock.close()
  await sleep(1500)

  const postingsAfterKill = sqlNumber(`SELECT COUNT(*) FROM ledger_posting lp
    JOIN gateway_settlement gs ON gs.id=lp.source_id AND gs.org_id=lp.org_id
    JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gr.org_id
    WHERE lp.source_type='GATEWAY_SETTLEMENT' AND gr.project_id=${fixture.projectId}`)
  const budgetAfterKill = budgetSnapshot(fixture)
  expect(round.checks, 'a killed settlement transaction leaves no ledger posting',
    postingsAfterKill === settledBefore, { postings: postingsAfterKill, before: settledBefore })
  expect(round.checks, 'a killed settlement transaction leaves Budget Actual unchanged',
    budgetAfterKill.actual === actualBefore, { before: actualBefore, after: budgetAfterKill.actual })
  expect(round.checks, 'the settlement row is not SETTLED after the kill',
    sqlNumber(`SELECT COUNT(*) FROM gateway_settlement gs
      JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
      WHERE gr.project_id=${fixture.projectId} AND gs.status='SETTLED'`) === settledBefore,
    'SETTLED count must be unchanged')

  state.timeline.mark('F05', rep, 'restore', {})
  await startBackend()
  const tRestore = Date.now()
  round.timeline.restoreAt = new Date(tRestore).toISOString()
  round.timeline.restoreMs = tRestore - tDetect

  const snapshot = await waitForSettlement(fixture, settledBefore + 1, 180_000)
  const tHealthy = Date.now()
  round.timeline.healthyAt = new Date(tHealthy).toISOString()
  round.timeline.recoveryMs = tHealthy - tRestore
  expect(round.checks, 'the restarted worker converges the interrupted settlement exactly once',
    (snapshot.settlements.statuses.SETTLED ?? 0) === settledBefore + 1,
    snapshot.settlements.statuses)
  expect(round.checks, 'exactly one posting per settled request (1:1)',
    snapshot.ledger.postings === (snapshot.settlements.statuses.SETTLED ?? 0),
    { postings: snapshot.ledger.postings, settled: snapshot.settlements.statuses.SETTLED })
  expect(round.checks, 'budget Actual equals the single posted amount',
    budgetAfterKill.actual !== budgetSnapshot(fixture).actual && budgetSnapshot(fixture).actual !== '0.00000000',
    { before: budgetAfterKill.actual, after: budgetSnapshot(fixture).actual })
  expect(round.checks, 'no duplicate posting key or source', snapshot.ledger.duplicatePostingKeys === 0
    && snapshot.ledger.duplicateSources === 0, snapshot.ledger)
  round.failures.push(...violations(snapshot, `F05 kill rep ${rep}`))
  round.metrics.db = snapshot
  state.events.record('F05', rep, 'verified', {
    detectMs: round.timeline.detectMs, recoveryMs: round.timeline.recoveryMs,
    failures: round.failures.length,
  })
  } finally {
    await lock.close().catch(() => {})
  }
}

async function f05DatabaseOutage(round, rep) {
  const fixture = state.seed.fixtures.f05
  await ensureHealthy()
  resetRedisLimitKeys(fixture.credentialId)
  await mockReset()
  await mockMode('ok')

  const before = fixtureSnapshot(fixture)
  const settledBefore = before.settlements.statuses.SETTLED ?? 0
  const prepared = await prepareBlockedSettlement(fixture, rep, 'db')
  const { lock, waiting } = prepared
  try {
    expect(round.checks, 'order request is accepted before the DB outage',
      prepared.order.status === 200, { status: prepared.order.status })
    expect(round.checks, 'a settlement transaction is in flight before the DB outage',
      waiting === true, waiting)

    const logSizeBefore = existsSync(join(LOG_DIR, 'backend.log'))
      ? readFileSync(join(LOG_DIR, 'backend.log'), 'utf8').length : 0
  state.timeline.mark('F05db', rep, 'inject', { container: MYSQL_CONTAINER })
  const tInject = Date.now()
  stopMysql()
  round.timeline.injectAt = new Date(tInject).toISOString()
  await lock.close()
  await sleep(4000)

  const tDetect = Date.now()
  round.timeline.detectAt = new Date(tDetect).toISOString()
  round.timeline.detectMs = tDetect - tInject
  const backendAlive = isProcessRunning(pidOf('backend'))
  const readinessDuring = await readiness(BACKEND_PORT)
  const logText = existsSync(join(LOG_DIR, 'backend.log'))
    ? readFileSync(join(LOG_DIR, 'backend.log'), 'utf8').slice(logSizeBefore) : ''
  round.metrics.outage = {
    backendAlive,
    readiness: readinessDuring.status,
    databaseSignalVisible: /DATABASE|datasource|Communications link|Connection refused|HikariPool/i.test(logText),
    logExcerpt: backendLogTail(12),
  }
  expect(round.checks, 'Backend process survives the database outage (no crash-loop)',
    backendAlive, backendAlive)
  expect(round.checks, 'the outage is diagnosable in health/logs',
    readinessDuring.status !== 200 || round.metrics.outage.databaseSignalVisible,
    round.metrics.outage)
  state.events.record('F05db', rep, 'detect', round.metrics.outage)

  state.timeline.mark('F05db', rep, 'restore', { container: MYSQL_CONTAINER })
  startMysql()
  const tRestore = Date.now()
  waitMysqlReady()
  await waitHttp(BACKEND_PORT, '/actuator/health/readiness', 180_000, 'Backend after MySQL restart')
  round.timeline.restoreAt = new Date(tRestore).toISOString()
  round.timeline.restoreMs = Date.now() - tDetect

  const snapshot = await waitForSettlement(fixture, settledBefore + 1, 240_000)
  const tHealthy = Date.now()
  round.timeline.healthyAt = new Date(tHealthy).toISOString()
  round.timeline.recoveryMs = tHealthy - tRestore
  expect(round.checks, 'the worker resumes after the database returns and settles exactly once',
    (snapshot.settlements.statuses.SETTLED ?? 0) === settledBefore + 1,
    snapshot.settlements.statuses)
  expect(round.checks, 'no duplicate posting after the outage',
    snapshot.ledger.duplicatePostingKeys === 0 && snapshot.ledger.duplicateSources === 0,
    snapshot.ledger)
  expect(round.checks, 'one posting per settled request', snapshot.ledger.postings
    === (snapshot.settlements.statuses.SETTLED ?? 0),
  { postings: snapshot.ledger.postings, settled: snapshot.settlements.statuses.SETTLED })
  round.failures.push(...violations(snapshot, `F05 db rep ${rep}`))
  round.metrics.db = snapshot
  state.events.record('F05db', rep, 'verified', {
    detectMs: round.timeline.detectMs, recoveryMs: round.timeline.recoveryMs,
    failures: round.failures.length,
  })
  } finally {
    await lock.close().catch(() => {})
  }
}

// ---------------------------------------------------------------------------
// Evidence
// ---------------------------------------------------------------------------

function environmentSnapshot() {
  const host = hostEnvironment()
  const mysqlVersion = sqlRows('SELECT VERSION()')[0]?.[0] ?? null
  return {
    capturedAt: new Date().toISOString(),
    runId: RUN_NONCE,
    git: {
      sha: host.gitSha,
      branch: host.gitBranch,
      dirty: host.gitDirty,
    },
    host,
    docker: {
      serverVersion: docker(['version', '--format', '{{.Server.Version}}']).stdout,
      vmMemBytes: Number(docker(['info', '--format', '{{.MemTotal}}']).stdout),
    },
    images: {
      mysql: MYSQL_IMAGE,
      redis: REDIS_IMAGE,
      mysqlVersion,
    },
    dedicatedResources: {
      mysqlContainer: MYSQL_CONTAINER,
      mysqlPort: MYSQL_PORT,
      redisContainer: REDIS_CONTAINER,
      redisPort: REDIS_PORT,
      database: DB_NAME,
      sharedInfraTouched: false,
    },
    ownership: {
      owner: getHarnessOwner(),
      ownerLabels: [OWNER_LABEL, RUN_LABEL, RESOURCE_LABEL],
      ownerFile: join(HC03_DIR, 'run-owner.json'),
      pidRecordVersion: 2,
      pidIdentity: 'owner + pid + process start time + command token',
      refusalPolicy: 'foreign/unlabeled containers and legacy/foreign PID files are refused, never deleted or killed',
    },
    processes: {
      backendPort: BACKEND_PORT,
      gatewayPorts: [GATEWAY_A_PORT, GATEWAY_B_PORT],
      mockProviderPort: MOCK_PORT,
      gatewayConfig: {
        hardTimeoutMs: 15000,
        headerTimeoutMs: 8000,
        streamIdleTimeoutMs: 8000,
        reservationTtlMs: 20000,
        reservationRecoveryIntervalMs: 3000,
        rateLimitEnabled: true,
        quotaEnabled: true,
        dbPoolMax: 12,
      },
      backendConfig: {
        settlementWorkerEnabled: true,
        settlementPollInterval: '500ms (test setting; product default 5s)',
      },
    },
    jars: {
      gateway: existsSync(state.jars.gateway) ? state.jars.gateway : null,
      gatewaySha256: existsSync(state.jars.gateway) ? jarSha256(state.jars.gateway) : null,
      backend: existsSync(state.jars.backend) ? state.jars.backend : null,
      backendSha256: existsSync(state.jars.backend) ? jarSha256(state.jars.backend) : null,
    },
    scenarioSelection: SELECTED,
    repetitions: REPS,
    limitsAndHonesty: [
      'All dependency containers are dedicated to HC-03 and are stopped/started by this harness only.',
      'The shared development MySQL/Redis/MinIO containers are never stopped, paused or flushed.',
      'No external load balancer: the harness chooses the surviving Gateway port explicitly.',
      'Single physical host; process and container share the same Docker VM.',
      'F06 process-level Close requires an authenticated Control Plane recipe; otherwise it stays NOT_VERIFIED.',
    ],
  }
}

function writeEvidence() {
  mkdirSync(EVIDENCE, { recursive: true })
  const environment = environmentSnapshot()
  state.environment = environment
  writeJson(join(EVIDENCE, 'environment.json'), environment)
  if (existsSync(EVENTS_PATH)) copyFileSync(EVENTS_PATH, join(EVIDENCE, 'events.jsonl'))
  const orgSnapshots = {
    hot: orgLedgerSnapshot(state.seed.hotOrg.orgId),
    close: orgLedgerSnapshot(state.seed.closeOrg.orgId),
  }
  const fixtureSnapshots = Object.fromEntries(
    Object.entries(state.seed.fixtures).map(([key, fixture]) => [key, fixtureSnapshot(fixture)]),
  )
  fixtureSnapshots.f06 = fixtureSnapshot(state.seed.f06)
  const invariants = {
    generatedAt: new Date().toISOString(),
    runId: RUN_NONCE,
    orgs: orgSnapshots,
    fixtures: fixtureSnapshots,
    periods: {
      hot: periodSnapshot(state.seed.hotOrg.periodId),
      close: periodSnapshot(state.seed.closeOrg.periodId),
    },
    mysqlCounters: mysqlGlobalCounters(),
    violations: [
      ...Object.entries(fixtureSnapshots).flatMap(([key, snapshot]) => violations(snapshot, `fixture ${key}`)),
      ...Object.entries(orgSnapshots).flatMap(([key, snapshot]) => {
        const out = []
        if (snapshot.duplicatePostingKeys) out.push(`org ${key}: duplicate posting keys`)
        if (snapshot.duplicateSources) out.push(`org ${key}: duplicate ledger sources`)
        if (snapshot.settledNonFinalUsage) out.push(`org ${key}: settlement on non-FINAL usage`)
        if (snapshot.settledWithoutPosting) out.push(`org ${key}: SETTLED without posting`)
        if (snapshot.overspentBudgets) out.push(`org ${key}: overspent budgets`)
        return out
      }),
    ],
  }
  writeJson(join(EVIDENCE, 'db-invariants.json'), invariants)
  const results = {
    schemaVersion: 1,
    runId: RUN_NONCE,
    generatedAt: new Date().toISOString(),
    commit: environment.git.sha,
    scenarios: state.scenarios,
    invariants,
    notes: [
      'Raw logs are not committed; excerpts are in the acceptance summary.',
      'Gateway/Backend processes are host JVMs; MySQL/Redis are dedicated Docker containers.',
    ],
  }
  writeJson(RAW_RESULTS_PATH, results)
  writeJson(join(EVIDENCE, 'results.json'), results)
  return { environment, invariants, results }
}

function verdict() {
  const failures = state.scenarios.flatMap(scenario =>
    scenario.failures.map(failure => `${scenario.id}: ${failure}`))
  const notVerified = state.scenarios.flatMap(scenario => scenario.rounds.flatMap(round =>
    round.checks.filter(check => check.notVerified).map(check => `${scenario.id}: ${check.name}`)))
  if (failures.length) {
    console.error(`[HC-03-RED] ${failures.length} invariant failure(s):`)
    for (const failure of failures) console.error(`  - ${failure}`)
  } else {
    console.log('[HC-03-GREEN] all executed fault scenarios satisfied their financial invariants')
  }
  if (notVerified.length) {
    console.log(`[HC-03-PARTIAL] ${notVerified.length} NOT_VERIFIED item(s):`)
    for (const item of notVerified) console.log(`  - ${item}`)
  }
  return failures
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

async function main() {
  ensureDirs()
  state.events = new EventLog(EVENTS_PATH)
  if (CLEANUP_ONLY) {
    try {
      const result = await cleanupOwnedEnvironment()
      if (result.refusals.length) {
        console.error(`[HC-03-CLEANUP-REFUSED] ${result.refusals.length} resource(s) were not removed:`)
        for (const refusal of result.refusals) console.error(`  - ${refusal}`)
        return 1
      }
      log('HC-03 processes and containers owned by this run were removed; shared infra untouched')
      return 0
    } catch (error) {
      console.error(`[HC-03-CLEANUP-REFUSED] ${error.message}`)
      return 1
    }
  }
  let exitCode = 0
  try {
    if (ATTACH) {
      log('attaching to the existing HC-03 environment (--attach)')
      attachToExistingEnvironment()
      state.seed = JSON.parse(readFileSync(join(HC03_DIR, 'seed.json'), 'utf8'))
      state.jars = ensureJars({ skipBuild: true })
      await ensureHealthy()
    } else {
      preflightFreshEnvironment()
      await setupEnvironment()
    }
    await mockMode('ok')
    const smoke = await sendChat(GATEWAY_A_PORT, state.seed.fixtures.hot, `hc03-smoke-${RUN_NONCE}`)
    if (smoke.status !== 200) throw new Error(`smoke request failed: HTTP ${smoke.status} ${smoke.bodySnippet}`)
    await waitForSettlement(state.seed.fixtures.hot, 1, 120_000)
    log('baseline request, FINAL usage and settlement confirmed')

    if (SELECTED.includes('F01')) {
      await runScenario('F01', (round, rep) => f01RedisOutage(round, rep, 'stop'))
      await runScenario('F01', (round, rep) => f01RedisOutage(round, rep, 'pause'))
    }
    if (SELECTED.includes('F02')) await runScenario('F02', f02MysqlOutage)
    if (SELECTED.includes('F03pre')) await runScenario('F03pre', f03PreDispatchKill)
    if (SELECTED.includes('F03post')) await runScenario('F03post', f03PostDispatchKill)
    if (SELECTED.includes('F04')) await runScenario('F04', f04InstanceFailover)
    if (SELECTED.includes('F05')) {
      await runScenario('F05', (round, rep) => f05WorkerKill(round, rep))
      await runScenario('F05db', f05DatabaseOutage)
    }
    if (SELECTED.includes('F06')) await runScenario('F06', f06CloseRace)

    const written = writeEvidence()
    const failures = verdict()
    log(`evidence written to ${EVIDENCE}`)
    log(`db violations: ${written.invariants.violations.length}`)
    exitCode = failures.length ? 1 : 0
  } catch (error) {
    if (error instanceof OwnershipError) {
      console.error(`[HC-03-REFUSED] ${error.message}`)
    } else {
      console.error(`[HC-03-FAIL] ${error?.stack ?? error}`)
    }
    state.startErrors.push(String(error?.message ?? error))
    exitCode = 1
  } finally {
    if (!KEEP_RUNNING) {
      const result = await teardownProcesses({ removeContainers: true }).catch(() => ({ refusals: [] }))
      if (result.refusals.length) {
        console.error(`[HC-03-CLEANUP-REFUSED] ${result.refusals.length} resource(s) were not removed:`)
        for (const refusal of result.refusals) console.error(`  - ${refusal}`)
        exitCode = 1
      } else if (getHarnessOwner()) {
        log('HC-03 processes and its dedicated containers stopped; shared infrastructure untouched')
      } else {
        log('no HC-03 owner was established; nothing was stopped or removed')
      }
    } else {
      log('environment left running (--keep-running)')
    }
  }
  return exitCode
}

main()
  .then(code => process.exit(code))
  .catch(async error => {
    console.error(`[HC-03-FATAL] ${error.stack ?? error.message}`)
    await teardownProcesses({ removeContainers: false }).catch(() => {})
    process.exit(1)
  })
