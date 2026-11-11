#!/usr/bin/env node
/**
 * HC-01 Gateway concurrency baseline runner.
 *
 * One command prepares an isolated database, starts the real Backend (Flyway +
 * settlement worker), the real MySQL/Redis infrastructure, one controlled mock
 * Provider and one or two independent Gateway JVMs, then executes the load
 * matrix. Every result row carries the exact Git SHA, process count, limits,
 * Provider mode, concurrency, round and measurement window.
 *
 * Usage:
 *   node scripts/perf/hc01/runner.mjs                 # full baseline
 *   node scripts/perf/hc01/runner.mjs --quick         # short smoke of the harness
 *   node scripts/perf/hc01/runner.mjs --build         # rebuild jars first
 *   node scripts/perf/hc01/runner.mjs --skip-build    # reuse existing jars
 *   node scripts/perf/hc01/runner.mjs --keep-running  # leave app processes up
 *
 * Infra (MySQL/Redis/MinIO) is never stopped by this script.
 */
import { spawnSync } from 'node:child_process'
import { createWriteStream, existsSync } from 'node:fs'
import http from 'node:http'
import { join, resolve } from 'node:path'
import {
  DB_NAME, GATEWAY_DB_USER, HC01_DIR, LOG_DIR, MODEL_KEY, ROOT, config, ensureDirs,
  findJar, gitValue, hostEnvironment, httpRequest, mysql, mysqlNumber, mysqlRows, mysqlScalar,
  nowIso, pythonCommand, redisCli, sleep, startProcess, stopProcess, writeJson,
} from './lib.mjs'
import { allFixtureBudgets, seed } from './seed.mjs'
import { runLoad, runOneShot } from './load.mjs'
import { Sampler } from './sampler.mjs'
import {
  budgetSnapshot, channelIsolation, dispatchSnapshot, explainKeyStatements, settlementSnapshot,
  settlementViolations, sqlTimestamp, topSqlDigests, waitSettlementConverged,
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
const SKIP_BUILD = flags.has('--skip-build')
const FORCE_BUILD = flags.has('--build')
const KEEP_RUNNING = flags.has('--keep-running')
// HC-02 A/B support: run only the account-period hotspot suite and/or point
// the runner at a pre-built Gateway jar from another commit.
const SUITE = options.suite ?? (QUICK ? 'quick' : 'full')
const VARIANT = options.variant ?? null
const CODE_SHA = options['code-sha'] ?? null
const GATEWAY_JAR_OVERRIDE = options['gateway-jar'] ? resolve(ROOT, options['gateway-jar']) : null
const RUN_NONCE = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)
const ROUNDS = QUICK ? 1 : Number(options.rounds ?? 3)
const WARMUP_SEC = QUICK ? 2 : Number(options.warmup ?? 8)
const MEASURE_SEC = QUICK ? 4 : Number(options.measure ?? 20)
const OUTPUT_DIR = options.output
  ? join(ROOT, options.output)
  : QUICK
    ? join(HC01_DIR, 'quick-evidence')
    : join(ROOT, 'docs', '03-acceptance', 'maintenance', '2026-11', '2026-11-11-hc-01-gateway-concurrency')
const RAW_RESULT_PATH = join(HC01_DIR, `load-results-${RUN_NONCE}.json`)
const SEED_PATH = join(HC01_DIR, 'seed.json')
const BACKEND_READY_TIMEOUT_MS = 300_000
const GATEWAY_READY_TIMEOUT_MS = 240_000

process.on('unhandledRejection', reason => {
  // A client-canceled SSE socket can emit a late ECONNRESET/aborted after the
  // measurement promise settled. It is expected transport noise, not a load
  // result; record it and keep the harness alive. Anything else is fatal.
  if (reason?.code === 'ECONNRESET' || reason?.message === 'aborted') {
    state.notes.push(`late socket abort: ${reason.code ?? reason.message}`)
    return
  }
  throw reason
})

const cfg = config()
const state = {
  seed: null,
  gatewayPassword: null,
  gatewayPhase: null,
  gatewayPorts: [],
  environment: null,
  scenarios: [],
  notes: [],
  mockMode: null,
}

function log(message) {
  console.log(`[HC-01 ${new Date().toISOString().slice(11, 19)}] ${message}`)
}

function runMaven(module) {
  const executable = process.platform === 'win32'
    ? join(ROOT, module, 'mvnw.cmd')
    : join(ROOT, module, 'mvnw')
  const logPath = join(LOG_DIR, `build-${module}.log`)
  const out = createWriteStream(logPath, { flags: 'a' })
  log(`building ${module} -> ${logPath}`)
  const result = spawnSync(executable, ['-B', '-DskipTests', 'package'], {
    cwd: join(ROOT, module),
    shell: process.platform === 'win32',
    windowsHide: true,
    timeout: 15 * 60_000,
  })
  if (result.status !== 0) throw new Error(`${module} build failed (${result.status}); see ${logPath}`)
  out.end()
}

function ensureJars() {
  const gatewayJar = GATEWAY_JAR_OVERRIDE ?? findJar('gateway', 'gateway-')
  const backendJar = findJar('backend', 'backend-')
  const needBuild = FORCE_BUILD || !gatewayJar || !backendJar
  if (needBuild && !SKIP_BUILD) {
    if (!GATEWAY_JAR_OVERRIDE) runMaven('gateway')
    if (!backendJar) runMaven('backend')
  }
  const gateway = GATEWAY_JAR_OVERRIDE ?? findJar('gateway', 'gateway-')
  const backend = findJar('backend', 'backend-')
  if (!gateway || !backend) throw new Error('缺少可执行 JAR；请去掉 --skip-build 或先运行 npm run build')
  if (!existsSync(gateway)) throw new Error(`Gateway JAR 不存在: ${gateway}`)
  return { gateway, backend }
}

function prepareDatabase() {
  log(`recreating dedicated database ${DB_NAME}`)
  mysql(`DROP DATABASE IF EXISTS \`${DB_NAME}\`;
    CREATE DATABASE \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
    GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${cfg.mysqlUser}'@'%';
    FLUSH PRIVILEGES;`, { database: null, password: cfg.mysqlRootPassword })
}

function killDbConnections(user) {
  // An abrupt JVM kill can leave an idle MySQL session holding row locks until
  // the server notices the broken socket. The harness stops/restarts processes
  // between phases, so explicitly reap only this database's sessions for the
  // stopped identity before measuring the next phase.
  const rows = mysqlRows(`SELECT id FROM information_schema.processlist
    WHERE DB='${DB_NAME}' AND USER='${user}'`, { database: null })
  for (const [id] of rows) {
    try { mysql(`KILL ${Number(id)};`, { database: null }) } catch { /* session already gone */ }
  }
}

function createGatewayDbUser() {
  const password = `${RUN_NONCE}-${Math.random().toString(36).slice(2)}-gw`
  const owned = ['budget_reservation', 'gateway_request', 'gateway_route_attempt',
    'gateway_usage_fact', 'gateway_usage_dimension']
  const grants = owned.map(table => `GRANT SELECT, INSERT, UPDATE ON \`${DB_NAME}\`.\`${table}\` TO '${GATEWAY_DB_USER}'@'%';`).join('\n')
  // The Gateway runtime also hosts the Advisor inference worker. These are the
  // M21 production-minimum Advisor grants (no financial writes).
  const advisorGrants = [
    'GRANT SELECT, UPDATE ON `%DB%`.advisor_inference_job TO \'%USER%\'@\'%\';',
    'GRANT SELECT, UPDATE ON `%DB%`.advisor_inference_attempt TO \'%USER%\'@\'%\';',
    'GRANT INSERT ON `%DB%`.advisor_explanation TO \'%USER%\'@\'%\';',
    'GRANT SELECT ON `%DB%`.advisor_profile TO \'%USER%\'@\'%\';',
    'GRANT SELECT ON `%DB%`.advisor_evidence_snapshot TO \'%USER%\'@\'%\';',
  ].map(statement => statement.replaceAll('%DB%', DB_NAME).replaceAll('%USER%', GATEWAY_DB_USER)).join('\n')
  mysql(`CREATE USER IF NOT EXISTS '${GATEWAY_DB_USER}'@'%' IDENTIFIED BY '${password}';
    ALTER USER '${GATEWAY_DB_USER}'@'%' IDENTIFIED BY '${password}';
    GRANT SELECT ON \`${DB_NAME}\`.* TO '${GATEWAY_DB_USER}'@'%';
    GRANT LOCK TABLES ON \`${DB_NAME}\`.* TO '${GATEWAY_DB_USER}'@'%';
    ${grants}
    ${advisorGrants}
    FLUSH PRIVILEGES;`, { database: null, password: cfg.mysqlRootPassword })
  const grantsText = mysqlScalar(`SHOW GRANTS FOR '${GATEWAY_DB_USER}'@'%';`, {
    database: null, password: cfg.mysqlRootPassword,
  })
  if (String(grantsText).includes('DELETE') || String(grantsText).includes('GRANT OPTION')) {
    throw new Error('Gateway DB identity unexpectedly holds DELETE or GRANT OPTION')
  }
  state.gatewayPassword = password
  return password
}

function backendEnvironment(pollInterval = '1s') {
  return {
    ...process.env,
    SPRING_DATASOURCE_URL: `jdbc:mysql://127.0.0.1:${cfg.mysqlPort}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC`,
    SPRING_DATASOURCE_USERNAME: cfg.mysqlUser,
    SPRING_DATASOURCE_PASSWORD: cfg.mysqlPassword,
    AICOSTOPS_REDIS_HOST: '127.0.0.1',
    AICOSTOPS_REDIS_PORT: String(cfg.redisPort),
    AICOSTOPS_REDIS_PASSWORD: cfg.redisPassword,
    AICOSTOPS_STORAGE_ENDPOINT: `http://127.0.0.1:${cfg.minioPort}`,
    AICOSTOPS_STORAGE_ACCESS_KEY: cfg.storageAccessKey,
    AICOSTOPS_STORAGE_SECRET_KEY: cfg.storageSecretKey,
    AICOSTOPS_STORAGE_BUCKET: cfg.storageBucket,
    AICOSTOPS_JWT_SIGNING_KEY: cfg.jwtSigningKey,
    AICOSTOPS_ALLOW_PUBLIC_REGISTRATION: 'false',
    AICOSTOPS_ALLOWED_ORIGINS: 'http://localhost:5173',
    AICOSTOPS_REFRESH_COOKIE_SECURE: 'false',
    AICOSTOPS_DEV_BOOTSTRAP_ENABLED: 'false',
    AICOSTOPS_GATEWAY_SETTLEMENT_WORKER_ENABLED: 'true',
    AICOSTOPS_GATEWAY_SETTLEMENT_POLL_INTERVAL: pollInterval,
    AICOSTOPS_IMPORT_WORKER_ENABLED: 'false',
    SERVER_PORT: String(cfg.backendPort),
    SPRING_PROFILES_ACTIVE: '',
  }
}

function gatewayEnvironment(port, phase) {
  const high = phase !== 'normal-rate-limit'
  return {
    ...process.env,
    SPRING_DATASOURCE_URL: `jdbc:mysql://127.0.0.1:${cfg.mysqlPort}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC`,
    SPRING_DATASOURCE_USERNAME: GATEWAY_DB_USER,
    SPRING_DATASOURCE_PASSWORD: state.gatewayPassword,
    SPRING_DATA_REDIS_HOST: '127.0.0.1',
    SPRING_DATA_REDIS_PORT: String(cfg.redisPort),
    SPRING_DATA_REDIS_PASSWORD: cfg.redisPassword,
    AICOSTOPS_GATEWAY_PORT: String(port),
    AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1: cfg.credentialHmacKey,
    AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1: cfg.requestHmacKey,
    AICOSTOPS_PROVIDER_KEK_V1: cfg.providerKek,
    AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED: 'false',
    AICOSTOPS_GATEWAY_RATE_LIMIT_ENABLED: 'true',
    AICOSTOPS_GATEWAY_RATE_LIMIT_CAPACITY: high ? '500000' : '60',
    AICOSTOPS_GATEWAY_RATE_LIMIT_REFILL_PER_SECOND: high ? '50000' : '1',
    AICOSTOPS_GATEWAY_QUOTA_ENABLED: 'true',
    AICOSTOPS_GATEWAY_QUOTA_REQUESTS_PER_DAY: high ? '100000000' : '1000',
    AICOSTOPS_GATEWAY_MAX_ACTIVE_STREAMS: phase === 'sse-probe' ? '4' : '128',
    AICOSTOPS_GATEWAY_DB_THREADS: '12',
    AICOSTOPS_GATEWAY_DB_QUEUE_CAPACITY: '256',
    AICOSTOPS_GATEWAY_DB_POOL_MAX: '12',
    SPRING_PROFILES_ACTIVE: '',
  }
}

async function waitHttp(port, path, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const response = await httpRequest({ host: '127.0.0.1', port, path, method: 'GET' }, null, 10_000)
    last = response
    if (response.status === 200) return response
    await sleep(2000)
  }
  throw new Error(`${label} 未就绪 (${path} => HTTP ${last?.status ?? 'n/a'})`)
}

async function startBackend(jar, pollInterval = '1s') {
  log(`starting Backend (Flyway + settlement worker, poll=${pollInterval})`)
  startProcess('backend', 'java', ['-jar', jar], { env: backendEnvironment(pollInterval) })
  await waitHttp(cfg.backendPort, '/actuator/health/readiness', BACKEND_READY_TIMEOUT_MS, 'Backend')
  log('Backend ready')
}

async function startGatewayProcess(port, phase) {
  const jar = GATEWAY_JAR_OVERRIDE ?? findJar('gateway', 'gateway-')
  const name = `gateway-${port}`
  startProcess(name, 'java', ['-jar', jar, `--server.port=${port}`], { env: gatewayEnvironment(port, phase) })
  await waitHttp(port, '/actuator/health/readiness', GATEWAY_READY_TIMEOUT_MS, `Gateway :${port}`)
}

async function startGateways(phase, ports, { track = true } = {}) {
  log(`starting Gateway phase=${phase} ports=${ports.join(',')}`)
  for (const port of ports) await startGatewayProcess(port, phase)
  if (track) {
    state.gatewayPhase = phase
    state.gatewayPorts = ports
  }
  log(`Gateway ready (${ports.join(',')})`)
}

async function stopGateways() {
  for (const port of state.gatewayPorts ?? []) {
    await stopProcess(`gateway-${port}`)
  }
  if (state.gatewayPassword) killDbConnections(GATEWAY_DB_USER)
  state.gatewayPorts = []
  state.gatewayPhase = null
}

const REDIS_CONTAINER = 'ai-costops-hc01-redis'
const REDIS_PORT = 16379
const REDIS_PASSWORD = `hc01-${RUN_NONCE}-redis`

async function ensureRedis() {
  log(`preparing dedicated Redis container ${REDIS_CONTAINER} on 127.0.0.1:${REDIS_PORT}`)
  spawnSync('docker', ['rm', '-f', REDIS_CONTAINER], { encoding: 'utf8', windowsHide: true })
  const result = spawnSync('docker', [
    'run', '-d', '--name', REDIS_CONTAINER,
    '-p', `127.0.0.1:${REDIS_PORT}:6379`,
    'redis:8.8.1-alpine',
    'redis-server', '--requirepass', REDIS_PASSWORD, '--appendonly', 'no', '--save', '',
  ], { encoding: 'utf8', windowsHide: true, timeout: 120_000 })
  if (result.status !== 0) throw new Error(`启动专用 Redis 失败: ${result.stderr}`)
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    const ping = spawnSync('docker', [
      'exec', REDIS_CONTAINER, 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'ping',
    ], { encoding: 'utf8', windowsHide: true })
    if ((ping.stdout ?? '').trim() === 'PONG') {
      cfg.redisPort = REDIS_PORT
      cfg.redisPassword = REDIS_PASSWORD
      cfg.redisContainer = REDIS_CONTAINER
      process.env.HC01_REDIS_PORT = String(REDIS_PORT)
      process.env.HC01_REDIS_PASSWORD = REDIS_PASSWORD
      process.env.HC01_REDIS_CONTAINER = REDIS_CONTAINER
      log('dedicated Redis ready')
      return
    }
    await sleep(1000)
  }
  throw new Error('专用 Redis 未就绪')
}

function removeRedis() {
  spawnSync('docker', ['rm', '-f', REDIS_CONTAINER], { encoding: 'utf8', windowsHide: true })
}

function seedPreflight() {
  const orgId = state.seed.hot.orgId
  const rows = mysqlRows(`SELECT rpc.id, pa.status, pm.status, pm.routing_eligible,
      pcp.id AS profile_id, pv.id AS pricing_id,
      JSON_CONTAINS(pm.capabilities_json, JSON_QUOTE('CHAT_COMPLETIONS'), '$.capabilities') AS chat_capable,
      JSON_CONTAINS(pm.capabilities_json, JSON_QUOTE('SSE_STREAMING'), '$.capabilities') AS stream_capable,
      EXISTS(SELECT 1 FROM provider_credential pcred WHERE pcred.org_id=rpc.org_id
        AND pcred.provider_account_id=rpc.provider_account_id AND pcred.status='ACTIVE') AS credential_ready
    FROM routing_policy_candidate rpc
    LEFT JOIN provider_account pa ON pa.id=rpc.provider_account_id AND pa.org_id=rpc.org_id
    LEFT JOIN provider_model pm ON pm.id=rpc.provider_model_id
    LEFT JOIN provider_connection_profile pcp ON pcp.provider_account_id=rpc.provider_account_id
      AND pcp.org_id=rpc.org_id AND pcp.status='ACTIVE'
    LEFT JOIN pricing_version pv ON pv.org_id=rpc.org_id
      AND pv.provider_account_id=rpc.provider_account_id
      AND pv.provider_model_id=rpc.provider_model_id AND pv.status='ACTIVE'
      AND pv.effective_from <= UTC_TIMESTAMP(6)
      AND (pv.effective_to IS NULL OR pv.effective_to > UTC_TIMESTAMP(6))
    WHERE rpc.org_id=${orgId} AND rpc.status='ACTIVE';`)
  const eligible = rows.filter(row => row[1] === 'ACTIVE' && row[2] === 'ACTIVE'
    && row[3] === '1' && row[4] !== null && row[5] !== null
    && row[6] === '1' && row[7] === '1' && row[8] === '1')
  if (eligible.length === 0) {
    throw new Error(`seed preflight: no eligible routing candidate for org ${orgId}: ${JSON.stringify(rows)}`)
  }
}

async function startMock() {
  log('starting controlled mock Provider')
  const script = join(ROOT, 'scripts', 'perf', 'hc01', 'mock_provider.py')
  const python = pythonCommand()
  startProcess('mock-provider', python.command, [...python.prefix, script], {
    env: { ...process.env, MOCK_PROVIDER_PORT: String(cfg.mockPort) },
  })
  await waitHttp(cfg.mockPort, '/health', 60_000, 'Mock Provider')
  log('Mock Provider ready')
}

async function mockMode(mode, delayMs = 0) {
  const response = await httpRequest({
    host: '127.0.0.1', port: cfg.mockPort, path: '/admin/mode', method: 'POST',
    headers: { 'Content-Type': 'application/json' },
  }, JSON.stringify({ mode, delayMs }), 10_000)
  if (response.status !== 200) throw new Error(`mock mode failed: ${response.status} ${response.body}`)
  state.mockMode = mode
}

async function mockReset() {
  const response = await httpRequest({
    host: '127.0.0.1', port: cfg.mockPort, path: '/admin/reset', method: 'POST',
  }, '{}', 10_000)
  if (response.status !== 200) throw new Error(`mock reset failed: ${response.status}`)
}

async function mockStats() {
  const response = await httpRequest({ host: '127.0.0.1', port: cfg.mockPort, path: '/stats', method: 'GET' }, null, 10_000)
  if (response.status !== 200) throw new Error(`mock stats failed: ${response.status}`)
  return JSON.parse(response.body)
}

function clearCircuitState(orgId) {
  try {
    const pattern = `aicostops:gateway:circuit:v1:${orgId}:*`
    const keys = redisCli(['--scan', '--pattern', pattern])
      .split(/\r?\n/).map(key => key.trim()).filter(Boolean)
    if (keys.length) redisCli(['del', ...keys])
  } catch { /* dedicated test Redis only */ }
}

function clearAllCircuitState() {
  for (const orgId of orgIdsOf(allFixtureBudgets(state.seed))) clearCircuitState(orgId)
}

function resetRedisLimits(credentialId) {
  const day = new Date().toISOString().slice(0, 10).replaceAll('-', '')
  const rateBase = `aicostops:v2:gateway:ratelimit:${credentialId}`
  for (const key of [
    rateBase,
    `${rateBase}:tokens`,
    `${rateBase}:ts`,
    `aicostops:v2:gateway:quota:${credentialId}:${day}`,
  ]) {
    try { redisCli(['del', key]) } catch { /* dedicated test Redis only */ }
  }
}

async function mockRelease() {
  const response = await httpRequest({
    host: '127.0.0.1', port: cfg.mockPort, path: '/admin/release', method: 'POST',
  }, '{}', 10_000)
  return response.status === 200 ? JSON.parse(response.body) : { released: 0 }
}

function uniqueBudgets(fixtures) {
  return [...new Set(fixtures.map(item => item.budgetId))]
}

function orgIdsOf(fixtures) {
  return [...new Set(fixtures.map(item => item.orgId))]
}

function evaluateRound({ load, resources, providerOps, budgets, dispatch, expectations }) {
  const failures = []
  const statusCounts = load.statusCounts ?? {}
  const success = load.successCount ?? 0
  const expectedProviderOps = expectations.providerOps
  if (expectedProviderOps === 'successCount' && providerOps !== success) {
    failures.push(`provider_ops=${providerOps} != admitted_success=${success}`)
  }
  if (typeof expectedProviderOps === 'number' && providerOps !== expectedProviderOps) {
    failures.push(`provider_ops=${providerOps} != expected=${expectedProviderOps}`)
  }
  if (expectations.noServerError) {
    for (const status of Object.keys(statusCounts)) {
      const numeric = Number(status)
      // 503 is the documented bounded dependency failure (DB/Redis overload);
      // it is reported in statusCounts but is not an unbounded 5xx class.
      if ((numeric >= 500 && numeric !== 503) || numeric === -1) {
        failures.push(`unbounded response class HTTP ${status} x ${statusCounts[status]}`)
      }
    }
  }
  if (expectations.allowedStatuses) {
    for (const status of Object.keys(statusCounts)) {
      const numeric = Number(status)
      if (!expectations.allowedStatuses.includes(numeric) && numeric !== 503) {
        failures.push(`unexpected status HTTP ${status} x ${statusCounts[status]}`)
      }
    }
  }
  if (budgets.violations.length) {
    failures.push(`budget overspend: ${budgets.violations.map(item => `budget ${item.budgetId} used ${item.used} > ${item.total}`).join('; ')}`)
  }
  if (dispatch) {
    if (dispatch.duplicateFinalGroups !== 0) failures.push(`duplicate FINAL usage groups=${dispatch.duplicateFinalGroups}`)
    if (dispatch.completedWithNonFinalCurrent !== 0) failures.push(`COMPLETED with non-FINAL current usage=${dispatch.completedWithNonFinalCurrent}`)
    if (dispatch.completedWithoutFinal !== 0) failures.push(`COMPLETED without FINAL usage=${dispatch.completedWithoutFinal}`)
    if (expectations.maxMultiAttemptRequests !== undefined
        && dispatch.multiAttemptRequests > expectations.maxMultiAttemptRequests) {
      failures.push(`multi-attempt requests=${dispatch.multiAttemptRequests} > ${expectations.maxMultiAttemptRequests}`)
    }
  }
  if (expectations.requireActiveStreamsZero) {
    for (const [port, portData] of Object.entries(resources.perPort)) {
      const active = portData.finalGauges?.gateway_active_streams
      if (active !== null && active !== undefined && active !== 0) {
        failures.push(`gateway :${port} active_streams=${active} after round (leak)`)
      }
    }
  }
  if (expectations.requireProviderOpsEquals) {
    // Additional exact provider-operation checks are encoded above.
  }
  return failures
}

async function runScenario(spec) {
  const {
    id, fixture, fixtures, ports, concurrency, rounds = ROUNDS,
    providerMode = 'ok', delayMs = 0, stream = false,
    expectations = {}, measureSec = MEASURE_SEC, warmupSec = WARMUP_SEC,
  } = spec
  const effectiveExpectations = { noServerError: true, allowedStatuses: [200], ...expectations }
  const activeFixtures = fixtures ?? [fixture]
  const rawKeys = activeFixtures.map(item => item.rawKey)
  const budgetIds = uniqueBudgets(activeFixtures)
  const orgIds = orgIdsOf(activeFixtures)
  const results = []
  for (let round = 1; round <= rounds; round++) {
    await mockReset()
    await mockMode(providerMode, delayMs)
    if (warmupSec > 0) {
      await runLoad({
        ports, rawKeys, concurrency, durationSec: warmupSec, tag: `${id}-warm`,
        stream, collect: false,
      })
    }
    if (spec.resetLimitsBeforeMeasure) resetRedisLimits(spec.resetLimitsBeforeMeasure)
    await mockReset()
    await mockMode(providerMode, delayMs)
    const sampler = new Sampler(ports)
    await sampler.start()
    const load = await runLoad({
      ports, rawKeys, concurrency, durationSec: measureSec, tag: `${id}-r${round}`, stream,
    })
    const resources = await sampler.stop()
    await sleep(1000)
    const provider = await mockStats()
    const budgets = budgetSnapshot(budgetIds)
    const dispatch = dispatchSnapshot(orgIds, load.window.startedAt)
    const failures = evaluateRound({
      load, resources, providerOps: provider.post_chat_completions, budgets, dispatch,
      expectations: effectiveExpectations,
    })
    if (expectations.requireActiveStreamsZero) {
      // final gauges are sampled inside resources; the explicit wait below
      // prevents a timing race with permit release after the last response.
      await sleep(1000)
    }
    const result = {
      id,
      phase: state.gatewayPhase,
      instances: ports.length,
      round,
      concurrency,
      providerMode,
      delayMs,
      stream,
      rateLimit: state.gatewayPhase === 'normal-rate-limit'
        ? { capacity: 60, refillPerSecond: 1, quotaPerDay: 1000 }
        : { capacity: 500000, refillPerSecond: 50000, quotaPerDay: 100000000 },
      warmupSec,
      measureSec,
      load,
      providerOps: provider.post_chat_completions,
      budget: budgets,
      dispatch,
      resources,
      failures,
    }
    results.push(result)
    log(`${id} r${round}: successRps=${load.successRps} totalRps=${load.totalRps} statuses=${JSON.stringify(load.statusCounts)} p95=${load.latencyMs?.p95}ms ops=${provider.post_chat_completions} failures=${failures.length}`)
  }
  state.scenarios.push(...results)
  return results
}

async function runOneShotScenario(spec) {
  const {
    id, fixture, fixtures, ports, count, concurrency = count,
    providerMode = 'ok', delayMs = 0, stream = false, cancelAfterFirstChunk = false,
    sameIdempotencyKey = null, expectations = {}, maxCompletionTokens = 16,
  } = spec
  const effectiveExpectations = { noServerError: true, ...expectations }
  const activeFixtures = fixtures ?? [fixture]
  const rawKeys = activeFixtures.map(item => item.rawKey)
  const budgetIds = uniqueBudgets(activeFixtures)
  const orgIds = orgIdsOf(activeFixtures)
  await mockReset()
  await mockMode(providerMode, delayMs)
  const key = sameIdempotencyKey ? `${sameIdempotencyKey}-${RUN_NONCE}` : null
  const startedAt = new Date().toISOString()
  const load = await runOneShot({
    ports, rawKeys, count, concurrency, tag: id, stream, cancelAfterFirstChunk,
    sameIdempotencyKey: key, maxCompletionTokens,
  })
  await sleep(3000)
  const provider = await mockStats()
  const budgets = budgetSnapshot(budgetIds)
  const dispatch = dispatchSnapshot(orgIds, startedAt)
  const failures = evaluateRound({
    load, resources: { perPort: {} }, providerOps: provider.post_chat_completions, budgets, dispatch,
    expectations: effectiveExpectations,
  })
  const result = {
    id,
    phase: state.gatewayPhase,
    instances: ports.length,
    round: 1,
    concurrency,
    count,
    providerMode,
    delayMs,
    stream,
    cancelAfterFirstChunk,
    sameIdempotencyKey: Boolean(sameIdempotencyKey),
    load,
    providerOps: provider.post_chat_completions,
    budget: budgets,
    dispatch,
    resources: null,
    failures,
  }
  state.scenarios.push(result)
  log(`${id}: count=${count} statuses=${JSON.stringify(load.statusCounts)} ops=${provider.post_chat_completions} failures=${failures.length}`)
  return result
}

async function smoke() {
  log('smoke request + FINAL usage check')
  const response = await httpRequest({
    host: '127.0.0.1', port: cfg.gatewayPort, path: '/v1/chat/completions', method: 'POST',
    headers: {
      Authorization: `Bearer ${state.seed.hot.rawKey}`,
      'Idempotency-Key': `hc01-smoke-${RUN_NONCE}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
  }, JSON.stringify({
    model: MODEL_KEY,
    messages: [{ role: 'user', content: 'hc01 smoke' }],
    max_completion_tokens: 16,
  }), 60_000)
  if (response.status !== 200) {
    throw new Error(`smoke request failed: HTTP ${response.status} ${response.body}`)
  }
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    const final = mysqlNumber(`SELECT COUNT(*) FROM gateway_usage_fact uf
      JOIN gateway_request gr ON gr.id=uf.request_id AND gr.org_id=uf.org_id
      WHERE gr.org_id=${state.seed.hot.orgId} AND uf.status='FINAL'`)
    if (final >= 1) {
      log(`smoke FINAL usage confirmed (${final})`)
      return
    }
    await sleep(1000)
  }
  throw new Error('smoke request did not produce FINAL usage within 30s')
}

function openStream(port, rawKey, idempotencyKey) {
  const payload = JSON.stringify({
    model: MODEL_KEY,
    messages: [{ role: 'user', content: 'hc01 sse' }],
    max_completion_tokens: 16,
    stream: true,
  })
  let request
  const done = new Promise((resolve, reject) => {
    request = http.request({
      host: '127.0.0.1', port, path: '/v1/chat/completions', method: 'POST',
      headers: {
        Authorization: `Bearer ${rawKey}`,
        'Idempotency-Key': idempotencyKey,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
        Accept: 'text/event-stream',
      },
    }, response => {
      let body = ''
      response.setEncoding('utf8')
      response.on('data', chunk => { body += chunk })
      response.on('end', () => resolve({ status: response.statusCode, body }))
      response.on('error', reject)
    })
    request.on('error', reject)
    request.on('socket', socket => {
      if (!socket.__hc01ErrorGuard) {
        socket.__hc01ErrorGuard = true
        socket.on('error', () => {})
      }
    })
    request.end(payload)
  })
  return { request, done }
}

async function readActiveStreams(port) {
  const response = await httpRequest({ host: '127.0.0.1', port, path: '/actuator/prometheus', method: 'GET' }, null, 5000)
  const match = /gateway_active_streams(?:\{[^}]*\})?\s+([\d.]+)/.exec(response.body)
  return match ? Number(match[1]) : null
}

async function runSseCeilingProbe() {
  const probePort = cfg.gatewayProbePort
  await startGateways('sse-probe', [probePort], { track: false })
  const fixture = state.seed.hot
  const holders = []
  const holderErrors = []
  let statsDuringWait = null
  try {
    await mockReset()
    await mockMode('hold')
    for (let index = 0; index < 4; index++) {
      const holder = openStream(probePort, fixture.rawKey, `hc01-ceiling-hold-${RUN_NONCE}-${index}`)
      holder.done.catch(error => holderErrors.push(error.code || error.message))
      holders.push(holder)
    }
    const deadline = Date.now() + 45_000
    let active = null
    let mockHeld = 0
    while (Date.now() < deadline) {
      const stats = await mockStats()
      statsDuringWait = stats
      mockHeld = stats.held
      active = await readActiveStreams(probePort)
      if (mockHeld === 4 && active === 4) break
      await sleep(1000)
    }
    const over = await httpRequest({
      host: '127.0.0.1', port: probePort, path: '/v1/chat/completions', method: 'POST',
      headers: {
        Authorization: `Bearer ${fixture.rawKey}`,
        'Idempotency-Key': `hc01-ceiling-over-${RUN_NONCE}`,
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
    }, JSON.stringify({
      model: MODEL_KEY,
      messages: [{ role: 'user', content: 'hc01 ceiling over' }],
      max_completion_tokens: 16, stream: true,
    }), 30_000)
    const providerAfterOver = await mockStats()
    await mockRelease()
    await Promise.all(holders.map(holder => holder.done.catch(() => null)))
    await sleep(1000)
    const activeAfterRelease = await readActiveStreams(probePort)
    const fresh = await httpRequest({
      host: '127.0.0.1', port: probePort, path: '/v1/chat/completions', method: 'POST',
      headers: {
        Authorization: `Bearer ${fixture.rawKey}`,
        'Idempotency-Key': `hc01-ceiling-fresh-${RUN_NONCE}`,
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
    }, JSON.stringify({
      model: MODEL_KEY,
      messages: [{ role: 'user', content: 'hc01 ceiling fresh' }],
      max_completion_tokens: 16, stream: true,
    }), 30_000)
    const failures = []
    if (active !== 4) failures.push(`active_streams=${active}, want 4`)
    if (mockHeld !== 4) failures.push(`mock held=${mockHeld}, want 4`)
    if (over.status !== 429) failures.push(`ceiling+1 HTTP ${over.status}, want 429`)
    if (!String(over.body).includes('GATEWAY_RATE_LIMITED')) failures.push('ceiling+1 body missing GATEWAY_RATE_LIMITED')
    if (providerAfterOver.post_chat_completions !== 4) {
      failures.push(`ceiling+1 dispatched provider work (ops=${providerAfterOver.post_chat_completions}, want 4)`)
    }
    if (activeAfterRelease !== 0) failures.push(`active_streams after release=${activeAfterRelease}, want 0`)
    if (fresh.status !== 200 || !String(fresh.body).includes('[DONE]')) {
      failures.push(`fresh stream after release HTTP ${fresh.status} without [DONE]`)
    }
    state.scenarios.push({
      id: 'sse_ceiling_probe',
      phase: 'sse-probe',
      instances: 1,
      round: 1,
      concurrency: 4,
      count: 5,
      providerMode: 'hold',
      stream: true,
      ceiling: 4,
      load: {
        statusCounts: { '200': holders.length, [String(over.status)]: 1, [String(fresh.status)]: 1 },
        sampleCount: holders.length + 2,
        successCount: holders.length + (fresh.status === 200 ? 1 : 0),
      },
      providerOps: providerAfterOver.post_chat_completions,
      mockStats: providerAfterOver,
      mockStatsDuringWait: statsDuringWait,
      holderErrors,
      activeStreamsDuringHold: active,
      activeStreamsAfterRelease: activeAfterRelease,
      overCeilingStatus: over.status,
      overCeilingBody: String(over.body).slice(0, 400),
      freshStatus: fresh.status,
      freshHasDone: String(fresh.body).includes('[DONE]'),
      failures,
    })
    log(`sse_ceiling_probe: active=${active} over=${over.status} afterRelease=${activeAfterRelease} fresh=${fresh.status} failures=${failures.length}`)
  } finally {
    await mockRelease().catch(() => {})
    for (const holder of holders) {
      try { holder.request?.destroy() } catch { /* already closed */ }
    }
    await stopProcess(`gateway-${probePort}`)
  }
}

async function runSseCancelScenario() {
  const fixture = state.seed.sseCancel
  const startedAt = new Date().toISOString()
  await mockReset()
  await mockMode('hold')
  const streams = []
  for (let index = 0; index < 4; index++) {
    const stream = openStream(cfg.gatewayPort, fixture.rawKey, `hc01-cancel-${RUN_NONCE}-${index}`)
    stream.done.catch(() => null)
    streams.push(stream)
  }
  const deadline = Date.now() + 45_000
  let active = null
  let mockHeld = 0
  while (Date.now() < deadline) {
    const stats = await mockStats()
    mockHeld = stats.held
    active = await readActiveStreams(cfg.gatewayPort)
    if (mockHeld === 4 && active === 4) break
    await sleep(1000)
  }
  // The client cancels; the Gateway must release the stream permit now, while
  // Provider evidence remains UNKNOWN/INCOMPLETE and the hold stays PENDING.
  for (const stream of streams) {
    try { stream.request.destroy() } catch { /* already closed */ }
  }
  await sleep(2000)
  let activeAfterCancel = null
  const releaseDeadline = Date.now() + 30_000
  while (Date.now() < releaseDeadline) {
    activeAfterCancel = await readActiveStreams(cfg.gatewayPort)
    if (activeAfterCancel === 0) break
    await sleep(500)
  }
  await mockRelease()
  await Promise.all(streams.map(stream => stream.done.catch(() => null)))
  await sleep(3000)
  const provider = await mockStats()
  const usage = mysqlRows(`SELECT uf.status, COUNT(*) FROM gateway_usage_fact uf
    JOIN gateway_request gr ON gr.id=uf.request_id AND gr.org_id=uf.org_id
    WHERE gr.org_id=${fixture.orgId} AND gr.project_id=${fixture.projectId}
    GROUP BY uf.status ORDER BY uf.status;`)
  const reservations = mysqlRows(`SELECT status, COUNT(*) FROM budget_reservation
    WHERE org_id=${fixture.orgId} AND budget_id=${fixture.budgetId}
    GROUP BY status ORDER BY status;`)
  const settlements = mysqlNumber(`SELECT COUNT(*) FROM gateway_settlement gs
    JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
    WHERE gr.org_id=${fixture.orgId} AND gr.project_id=${fixture.projectId}`)
  const failures = []
  if (active !== 4) failures.push(`holders did not converge: active=${active}, want 4`)
  if (mockHeld !== 4) failures.push(`mock held=${mockHeld}, want 4`)
  if (activeAfterCancel !== 0) failures.push(`active_streams after cancel=${activeAfterCancel}, want 0 (permit leak)`)
  if (provider.post_chat_completions !== 4) failures.push(`provider_ops=${provider.post_chat_completions}, want 4`)
  const finalCount = Number(usage.find(row => row[0] === 'FINAL')?.[1] ?? 0)
  if (finalCount !== 0) failures.push(`canceled streams produced FINAL usage=${finalCount}`)
  const pendingHolds = Number(reservations.find(row => row[0] === 'PENDING_HOLD')?.[1] ?? 0)
  if (pendingHolds !== 4) failures.push(`PENDING_HOLD reservations=${pendingHolds}, want 4`)
  if (settlements !== 0) failures.push(`canceled streams produced settlements=${settlements}`)
  state.scenarios.push({
    id: 'sse_cancel_release',
    phase: state.gatewayPhase,
    instances: 1,
    round: 1,
    concurrency: 4,
    count: 4,
    providerMode: 'hold',
    stream: true,
    startedAt,
    providerOps: provider.post_chat_completions,
    mockStats: provider,
    activeStreamsDuringHold: active,
    activeStreamsAfterCancel: activeAfterCancel,
    usageStatusCounts: Object.fromEntries(usage.map(([status, count]) => [status, Number(count)])),
    reservationStatusCounts: Object.fromEntries(reservations.map(([status, count]) => [status, Number(count)])),
    settlements,
    failures,
  })
  log(`sse_cancel_release: active=${active} afterCancel=${activeAfterCancel} usage=${JSON.stringify(Object.fromEntries(usage))} failures=${failures.length}`)
}

async function runDisconnectScenario() {
  const fixture = state.seed.providerFailure
  const startedAt = new Date().toISOString()
  await mockReset()
  await mockMode('disconnect')
  const load = await runOneShot({
    ports: [cfg.gatewayPort], rawKeys: [fixture.rawKey], count: 5, concurrency: 5,
    tag: `hc01-disconnect-${RUN_NONCE}`, stream: false,
  })
  await sleep(3000)
  const provider = await mockStats()
  const usage = mysqlRows(`SELECT uf.status, COUNT(*) FROM gateway_usage_fact uf
    JOIN gateway_request gr ON gr.id=uf.request_id AND gr.org_id=uf.org_id
    WHERE gr.org_id=${fixture.orgId} AND gr.project_id=${fixture.projectId}
    GROUP BY uf.status ORDER BY uf.status;`)
  const reservations = mysqlRows(`SELECT status, COUNT(*) FROM budget_reservation
    WHERE org_id=${fixture.orgId} AND budget_id=${fixture.budgetId}
    GROUP BY status ORDER BY status;`)
  const settlements = mysqlNumber(`SELECT COUNT(*) FROM gateway_settlement gs
    JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
    WHERE gr.org_id=${fixture.orgId} AND gr.project_id=${fixture.projectId}`)
  const budgets = budgetSnapshot([fixture.budgetId])
  const failures = []
  if (provider.post_chat_completions !== 5) failures.push(`provider_ops=${provider.post_chat_completions}, want 5`)
  if (load.statusCounts['200']) failures.push(`disconnect produced HTTP 200 x ${load.statusCounts['200']}`)
  const finalCount = Number(usage.find(row => row[0] === 'FINAL')?.[1] ?? 0)
  if (finalCount !== 0) failures.push(`disconnect produced FINAL usage=${finalCount} (missing usage must not be zero cost)`)
  const unknownLike = usage.filter(row => row[0] !== 'FINAL').reduce((sum, row) => sum + Number(row[1]), 0)
  if (unknownLike !== 5) failures.push(`UNKNOWN/INCOMPLETE usage=${unknownLike}, want 5`)
  const pendingHolds = Number(reservations.find(row => row[0] === 'PENDING_HOLD')?.[1] ?? 0)
  if (pendingHolds !== 5) failures.push(`PENDING_HOLD reservations=${pendingHolds}, want 5`)
  if (settlements !== 0) failures.push(`disconnect produced settlements=${settlements}`)
  failures.push(...budgets.violations.map(item => `budget ${item.budgetId} overspent`))
  state.scenarios.push({
    id: 'provider_disconnect_unknown',
    phase: state.gatewayPhase,
    instances: 1,
    round: 1,
    concurrency: 5,
    count: 5,
    providerMode: 'disconnect',
    startedAt,
    load,
    providerOps: provider.post_chat_completions,
    mockStats: provider,
    usageStatusCounts: Object.fromEntries(usage.map(([status, count]) => [status, Number(count)])),
    reservationStatusCounts: Object.fromEntries(reservations.map(([status, count]) => [status, Number(count)])),
    settlements,
    failures,
  })
  log(`provider_disconnect_unknown: statuses=${JSON.stringify(load.statusCounts)} usage=${JSON.stringify(Object.fromEntries(usage))} failures=${failures.length}`)
}

function fixtureList(name) {
  if (name === 'hot') return [state.seed.hot]
  if (name === 'multiBudget') return [state.seed.hot, ...state.seed.multiBudget]
  if (name === 'multiOrg') return [state.seed.hot, ...state.seed.multiOrg]
  throw new Error(`unknown fixture ${name}`)
}

async function runFullMatrix() {
  const hot = state.seed.hot
  const singlePort = [cfg.gatewayPort]
  const levels = QUICK ? [5, 20] : [5, 20, 50, 100, 200]

  for (const concurrency of levels) {
    await runScenario({
      id: `hot_fast_single_c${concurrency}`,
      fixture: hot,
      ports: singlePort,
      concurrency,
      expectations: { providerOps: 'successCount', noServerError: false, maxMultiAttemptRequests: 0 },
    })
  }

  await runScenario({
    id: 'multi_budget_single_c100',
    fixtures: fixtureList('multiBudget'),
    ports: singlePort,
    concurrency: QUICK ? 20 : 100,
    expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
  })
  await runScenario({
    id: 'multi_org_single_c100',
    fixtures: fixtureList('multiOrg'),
    ports: singlePort,
    concurrency: QUICK ? 20 : 100,
    expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
  })

  await runScenario({
    id: 'slow_provider_single_c50',
    fixture: hot,
    ports: singlePort,
    concurrency: QUICK ? 10 : 50,
    providerMode: 'delay',
    delayMs: 500,
    expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
  })

  for (const count of QUICK ? [10] : [50, 100]) {
    await runOneShotScenario({
      id: `idempotency_same_key_c${count}`,
      fixture: hot,
      ports: singlePort,
      count,
      sameIdempotencyKey: `hc01-idem-${count}`,
      expectations: { providerOps: 1, maxMultiAttemptRequests: 1 },
    })
  }

  await runOneShotScenario({
    id: 'budget_exhaustion_c10',
    fixture: state.seed.exhaust,
    ports: singlePort,
    count: 10,
    maxCompletionTokens: 8192,
    expectations: { providerOps: 2, maxMultiAttemptRequests: 0 },
  })

  await runSseCeilingProbe()

  // Successful SSE must run before any scenario that can open the route
  // circuit; a circuit-open pre-stream failure is the dedicated leak path
  // covered by the Gateway regression test.
  await runScenario({
    id: 'sse_success_single_c8',
    fixture: hot,
    ports: singlePort,
    concurrency: 8,
    stream: true,
    measureSec: QUICK ? 3 : 8,
    warmupSec: 1,
    expectations: { providerOps: 'successCount', requireActiveStreamsZero: true },
  })

  await runSseCancelScenario()
  clearAllCircuitState()
  await runDisconnectScenario()
  clearAllCircuitState()

  if (!QUICK) {
    await startGatewayProcess(cfg.gatewayPort2, 'high')
    state.gatewayPorts = [cfg.gatewayPort, cfg.gatewayPort2]
    state.gatewayPhase = 'high'
    log(`second Gateway ready (${cfg.gatewayPort2})`)
    const dual = [cfg.gatewayPort, cfg.gatewayPort2]
    for (const concurrency of [50, 100]) {
      await runScenario({
        id: `hot_fast_dual_c${concurrency}`,
        fixture: hot,
        ports: dual,
        concurrency,
        expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
      })
    }
    await runScenario({
      id: 'multi_budget_dual_c100',
      fixtures: fixtureList('multiBudget'),
      ports: dual,
      concurrency: 100,
      expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
    })
    await runScenario({
      id: 'multi_org_dual_c100',
      fixtures: fixtureList('multiOrg'),
      ports: dual,
      concurrency: 100,
      expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
    })
  }

  await stopGateways()
  clearAllCircuitState()
  await startGateways('normal-rate-limit', [cfg.gatewayPort])
  await runScenario({
    id: 'rate_limit_normal_c20',
    fixture: state.seed.rateLimit,
    ports: [cfg.gatewayPort],
    concurrency: 20,
    warmupSec: 0,
    measureSec: QUICK ? 4 : 10,
    rounds: ROUNDS,
    resetLimitsBeforeMeasure: state.seed.rateLimit.credentialId,
    expectations: {
      providerOps: 'successCount',
      noServerError: true,
      allowedStatuses: [200, 429],
      maxMultiAttemptRequests: 0,
    },
  })
  await stopGateways()
}

/**
 * HC-02 A/B suite: only the account-period hotspot matrix the issue requires.
 * It keeps the same fixtures, warmup/measure windows, rounds and invariant
 * checks as the full matrix, but omits SSE/idempotency/disconnect/rate-limit
 * scenarios that do not exercise the billing-period lock.
 */
async function runHc02Matrix() {
  const hot = state.seed.hot
  const singlePort = [cfg.gatewayPort]

  for (const concurrency of [20, 50, 100]) {
    await runScenario({
      id: `hot_fast_single_c${concurrency}`,
      fixture: hot,
      ports: singlePort,
      concurrency,
      expectations: { providerOps: 'successCount', noServerError: false, maxMultiAttemptRequests: 0 },
    })
  }

  await runScenario({
    id: 'multi_budget_single_c100',
    fixtures: fixtureList('multiBudget'),
    ports: singlePort,
    concurrency: 100,
    expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
  })
  await runScenario({
    id: 'multi_org_single_c100',
    fixtures: fixtureList('multiOrg'),
    ports: singlePort,
    concurrency: 100,
    expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
  })

  await startGatewayProcess(cfg.gatewayPort2, 'high')
  state.gatewayPorts = [cfg.gatewayPort, cfg.gatewayPort2]
  state.gatewayPhase = 'high'
  log(`second Gateway ready (${cfg.gatewayPort2})`)
  const dual = [cfg.gatewayPort, cfg.gatewayPort2]
  for (const concurrency of [50, 100]) {
    await runScenario({
      id: `hot_fast_dual_c${concurrency}`,
      fixture: hot,
      ports: dual,
      concurrency,
      expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
    })
  }

  await stopGateways()
  clearAllCircuitState()
}

async function finalVerification() {
  const fixtures = allFixtureBudgets(state.seed)
  const orgIds = orgIdsOf(fixtures)
  log('waiting for settlement convergence')
  const converged = await waitSettlementConverged(orgIds, {
    timeoutMs: QUICK ? 180_000 : 30 * 60_000,
    onTick: snapshots => {
      const pending = snapshots.reduce((sum, item) => sum + item.pending, 0)
      const settled = snapshots.reduce((sum, item) => sum + item.settled, 0)
      log(`settlement drain: pending=${pending} settled=${settled}`)
    },
  })
  const violations = settlementViolations(converged.snapshots)
  const budgets = budgetSnapshot(uniqueBudgets(fixtures))
  const dispatchChecks = orgIds.map(orgId => ({
    orgId,
    ...dispatchSnapshot([orgId], new Date(Date.now() - 4 * 60 * 60_000).toISOString()),
  }))
  const final = {
    converged: converged.converged,
    settlement: converged.snapshots,
    settlementViolations: violations,
    budget: budgets,
    dispatch: dispatchChecks,
    topSql: topSqlDigests(),
    explains: explainKeyStatements(state.seed),
    channel: channelIsolation(),
  }
  const streamsAtEnd = {}
  if (state.gatewayPorts.length) {
    for (const port of state.gatewayPorts) streamsAtEnd[port] = await readActiveStreams(port)
  }
  final.activeStreamsAtEnd = streamsAtEnd
  return final
}

function environmentSnapshot(sqlExplain) {
  const host = hostEnvironment()
  const mysqlVersion = mysqlScalar('SELECT VERSION();')
  const mysqlSettings = mysqlRows(`SELECT @@innodb_buffer_pool_size, @@max_connections, @@innodb_flush_log_at_trx_commit,
    @@sync_binlog, @@innodb_lock_wait_timeout, @@transaction_isolation;`)[0]
  const redisInfo = (() => {
    try {
      const spawn = spawnSync('docker', ['exec', cfg.redisContainer, 'redis-cli', '-a', cfg.redisPassword,
        '--no-auth-warning', 'info', 'server'], { encoding: 'utf8', windowsHide: true })
      const map = Object.fromEntries((spawn.stdout ?? '').split(/\r?\n/)
        .map(line => /^(\w+):([^,\r\n]+)/.exec(line)).filter(Boolean).map(match => [match[1], match[2]]))
      return { version: map.redis_version, mode: map.redis_mode, os: map.os }
    } catch {
      return { version: null }
    }
  })()
  return {
    capturedAt: nowIso(),
    git: {
      sha: CODE_SHA ?? gitValue(['rev-parse', 'HEAD']),
      worktreeSha: gitValue(['rev-parse', 'HEAD']),
      branch: gitValue(['rev-parse', '--abbrev-ref', 'HEAD']),
      dirty: host.gitDirty,
      variant: VARIANT,
    },
    host,
    docker: {
      serverVersion: host.dockerServer,
      vmMemBytes: host.dockerMemBytes,
    },
    mysql: {
      version: mysqlVersion,
      database: DB_NAME,
      innodbBufferPoolBytes: Number(mysqlSettings[0]),
      maxConnections: Number(mysqlSettings[1]),
      innodbFlushLogAtTrxCommit: Number(mysqlSettings[2]),
      syncBinlog: Number(mysqlSettings[3]),
      innodbLockWaitTimeoutSec: Number(mysqlSettings[4]),
      transactionIsolation: mysqlSettings[5],
    },
    redis: redisInfo,
    backend: {
      port: cfg.backendPort,
      profile: 'default (empty)',
      settlementPollInterval: '1s (test setting; product default 5s)',
      importWorkerEnabled: false,
      jvm: host.java,
    },
    gateway: {
      ports: [cfg.gatewayPort, cfg.gatewayPort2],
      processes: 2,
      profile: 'default (empty)',
      dbThreads: 12,
      dbQueueCapacity: 256,
      dbPoolMax: 12,
      maxActiveStreams: 128,
      highRateLimit: { capacity: 500000, refillPerSecond: 50000, quotaPerDay: 100000000 },
      normalRateLimit: { capacity: 60, refillPerSecond: 1, quotaPerDay: 1000 },
    },
    mockProvider: {
      port: cfg.mockPort,
      modes: ['ok', 'delay', 'slow-stream', 'disconnect', 'http500', 'timeout', 'hold'],
      usage: { promptTokens: 5, completionTokens: 3, totalTokens: 8 },
    },
    loadClient: {
      runtime: process.version,
      model: 'closed-loop fixed concurrency; success RPS = completed responses / measured window',
    },
    limitsAndHonesty: [
      'Docker VM memory is recorded above; MySQL buffer pool is small relative to production.',
      'Load client and both Gateway JVMs share one physical host.',
      'No external load balancer; the dual-instance client distributes VUs across ports.',
      'No rate-limit protection was disabled; the isolated test used a raised threshold and the normal phase used product defaults.',
    ],
  }
}

function writeArtifacts(finalResult) {
  ensureDirs()
  const environment = environmentSnapshot()
  const artifacts = {
    schemaVersion: 1,
    runId: RUN_NONCE,
    generatedAt: nowIso(),
    commit: environment.git.sha,
    quick: QUICK,
    configuration: {
      suite: SUITE,
      variant: VARIANT,
      codeSha: CODE_SHA,
      gatewayJar: options['gateway-jar'] ?? null,
      rounds: ROUNDS,
      warmupSec: WARMUP_SEC,
      measureSec: MEASURE_SEC,
      outputDir: OUTPUT_DIR,
    },
    environment,
    fixtureTopology: {
      modelKey: MODEL_KEY,
      hot: { orgId: state.seed.hot.orgId, projectId: state.seed.hot.projectId, budgetId: state.seed.hot.budgetId },
      multiBudgetCount: state.seed.multiBudget.length,
      multiOrgCount: state.seed.multiOrg.length,
      separateBudgets: {
        exhaust: state.seed.exhaust.budgetId,
        uncertain: state.seed.uncertain.budgetId,
        rateLimit: state.seed.rateLimit.budgetId,
        providerFailure: state.seed.providerFailure.budgetId,
        sseCancel: state.seed.sseCancel.budgetId,
      },
    },
    scenarios: state.scenarios,
    final: finalResult,
    notes: state.notes,
  }
  writeJson(RAW_RESULT_PATH, artifacts)
  writeJson(join(OUTPUT_DIR, 'load-results.json'), artifacts)
  writeJson(join(OUTPUT_DIR, 'environment.json'), environment)
  writeJson(join(OUTPUT_DIR, 'db-invariants.json'), finalResult)
  log(`artifacts written: ${RAW_RESULT_PATH}`)
  log(`evidence written: ${join(OUTPUT_DIR, 'load-results.json')}`)
  return artifacts
}

async function stopAll() {
  await stopProcess('gateway-18081')
  await stopProcess('gateway-18082')
  await stopProcess('gateway-18083')
  await stopProcess('mock-provider')
  await stopProcess('backend')
}

async function main() {
  ensureDirs()
  log(`HC-01 runner quick=${QUICK} suite=${SUITE} variant=${VARIANT ?? 'n/a'} rounds=${ROUNDS} warmup=${WARMUP_SEC}s measure=${MEASURE_SEC}s`)
  await stopAll()
  await ensureRedis()
  killDbConnections(GATEWAY_DB_USER)
  killDbConnections(cfg.mysqlUser)
  const jars = ensureJars()
  state.jars = jars
  prepareDatabase()
  await startBackend(jars.backend)
  createGatewayDbUser()
  state.seed = seed()
  state.seed.gatewayDbUser = { user: GATEWAY_DB_USER, password: state.gatewayPassword }
  writeJson(SEED_PATH, state.seed)
  seedPreflight()
  await startMock()
  await startGateways('high', [cfg.gatewayPort])
  await smoke()

  // Mixed control: the settlement worker is part of the deployed system, but
  // it is asynchronous to the Gateway path. Measure the contention once, then
  // stop it so the Gateway matrix attributes DB pressure to the Gateway only.
  await runScenario({
    id: 'hot_fast_mixed_settlement_c50',
    fixture: state.seed.hot,
    ports: [cfg.gatewayPort],
    concurrency: 50,
    rounds: 1,
    warmupSec: 5,
    measureSec: QUICK ? 6 : 15,
    expectations: { providerOps: 'successCount', maxMultiAttemptRequests: 0 },
  })
  await stopProcess('backend')
  killDbConnections(cfg.mysqlUser)
  log('Backend/settlement worker stopped for the isolated Gateway matrix')

  if (SUITE === 'hc02') {
    await runHc02Matrix()
  } else {
    await runFullMatrix()
  }

  log('restarting Backend to drain FINAL usage into Settlement/Ledger')
  await startBackend(jars.backend, '500ms')
  const finalResult = await finalVerification()
  const artifacts = writeArtifacts(finalResult)
  const failures = verifyVerdict(artifacts)
  if (!KEEP_RUNNING) {
    await stopAll()
    removeRedis()
    log('application processes and dedicated Redis stopped; shared infrastructure left running')
  } else {
    log('application processes and dedicated Redis left running (--keep-running)')
  }
  return failures.length ? 1 : 0
}

function verifyVerdict(artifacts) {
  const scenarioFailures = artifacts.scenarios.flatMap(scenario =>
    (scenario.failures ?? []).map(failure => `${scenario.id}: ${failure}`))
  const finalFailures = [
    ...artifacts.final.settlementViolations,
    ...artifacts.final.budget.violations.map(item => `budget ${item.budgetId} overspent`),
    ...(artifacts.final.converged ? [] : ['settlement did not converge within timeout']),
  ]
  const all = [...new Set([...scenarioFailures, ...finalFailures])]
  if (all.length) {
    console.error(`[HC-01-RED] ${all.length} invariant/load failures:`)
    for (const failure of all) console.error(`  - ${failure}`)
  } else {
    console.log('[HC-01-GREEN] all recorded invariant checks passed')
  }
  return all
}

main()
  .then(code => process.exit(code))
  .catch(async error => {
    console.error(`[HC-01-FAIL] ${error.stack ?? error.message}`)
    await stopAll().catch(() => {})
    process.exit(1)
  })
