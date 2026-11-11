/**
 * HC-03 fault-injection utilities.
 *
 * Reuses the HC-01 harness primitives (HTTP client, waits, JSON evidence,
 * process listing, statistics) and adds what HC-01 deliberately never did:
 * lifecycle control over dependency containers *owned by this task*, forceful
 * Gateway/Backend process kills, an interactive MySQL session for reproducing
 * a deterministic pre-dispatch window, and an append-only event log.
 */
import { spawn, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  appendFileSync, closeSync, createWriteStream, existsSync, mkdirSync, openSync, readFileSync,
  unlinkSync, writeFileSync,
} from 'node:fs'
import { join } from 'node:path'
import {
  capture, decimalToScaled, findJar, gitValue, hostEnvironment, httpRequest, nowIso, readJson,
  scaledToDecimal, sleep, stats, waitFor, writeJson,
} from '../hc01/lib.mjs'
import { mysqlBinary } from '../hc01/lib.mjs'
import {
  DB_NAME, HC03_DIR, LOG_DIR, MYSQL_CONTAINER,
  MYSQL_IMAGE, MYSQL_PORT, MYSQL_ROOT_PASSWORD, PID_DIR, RAW_DIR, REDIS_CONTAINER, REDIS_IMAGE,
  REDIS_PASSWORD, REDIS_PORT, ROOT,
} from './env.mjs'

export {
  capture, decimalToScaled, findJar, gitValue, hostEnvironment, httpRequest, nowIso, readJson,
  scaledToDecimal, sleep, stats, waitFor, writeJson,
}

/** Resolves the real python.exe behind the Windows `py` launcher. */
export function resolvePythonCommand() {
  const configured = process.env.HC01_PYTHON
  if (configured) return { command: configured, prefix: [] }
  if (process.platform !== 'win32') return { command: 'python3', prefix: [] }
  const probe = spawnSync('py', ['-3', '-c', 'import sys;print(sys.executable)'], {
    encoding: 'utf8', windowsHide: true, timeout: 20_000,
  })
  const executable = (probe.stdout ?? '').trim()
  if (probe.status === 0 && executable) return { command: executable, prefix: [] }
  return { command: 'py', prefix: ['-3'] }
}

export function ensureDirs() {
  for (const dir of [HC03_DIR, LOG_DIR, RAW_DIR, PID_DIR]) mkdirSync(dir, { recursive: true })
}

export function log(message) {
  console.log(`[HC-03 ${new Date().toISOString().slice(11, 19)}] ${message}`)
}

// ---------------------------------------------------------------------------
// Docker (only HC-03 owned containers; all calls name an explicit container)
// ---------------------------------------------------------------------------

export function docker(args, options = {}) {
  const result = spawnSync('docker', args, {
    cwd: ROOT, encoding: 'utf8', windowsHide: true, timeout: options.timeout ?? 180_000,
  })
  if (result.error && !options.allowFailure) throw result.error
  if (result.status !== 0 && !options.allowFailure) {
    throw new Error(`docker ${args.join(' ')} failed (${result.status}): ${result.stderr || result.stdout}`)
  }
  return { status: result.status, stdout: (result.stdout ?? '').trim(), stderr: (result.stderr ?? '').trim() }
}

export function dockerAvailable() {
  return docker(['version', '--format', '{{.Server.Version}}'], { allowFailure: true }).stdout || null
}

export function containerState(name) {
  return docker(['inspect', '-f', '{{.State.Status}}', name], { allowFailure: true }).stdout || 'missing'
}

export function removeContainer(name) {
  docker(['rm', '-f', name], { allowFailure: true })
}

export function recreateMysql() {
  log(`recreating dedicated MySQL container ${MYSQL_CONTAINER} (127.0.0.1:${MYSQL_PORT})`)
  removeContainer(MYSQL_CONTAINER)
  const result = docker([
    'run', '-d', '--name', MYSQL_CONTAINER,
    '-p', `127.0.0.1:${MYSQL_PORT}:3306`,
    '-e', `MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}`,
    '-e', 'MYSQL_ROOT_HOST=%',
    '-e', `MYSQL_DATABASE=${DB_NAME}`,
    MYSQL_IMAGE,
    '--max-connections=200', '--innodb-buffer-pool-size=128M',
  ])
  if (result.status !== 0) throw new Error(`start dedicated MySQL failed: ${result.stderr}`)
}

export function waitMysqlReady(timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const ping = docker([
      'exec', MYSQL_CONTAINER, 'mysqladmin', 'ping', '-h', '127.0.0.1',
      '-uroot', `-p${MYSQL_ROOT_PASSWORD}`, '--silent',
    ], { allowFailure: true })
    if (ping.status === 0 && /alive/i.test(ping.stdout)) return true
    spawnSync(process.platform === 'win32' ? 'ping.exe' : 'sleep', process.platform === 'win32'
      ? ['-n', '2', '127.0.0.1'] : ['1'], { windowsHide: true })
  }
  throw new Error('dedicated MySQL did not become ready')
}

export function stopMysql() {
  docker(['stop', '-t', '1', MYSQL_CONTAINER])
}

export function startMysql() {
  docker(['start', MYSQL_CONTAINER])
}

export function recreateRedis() {
  log(`recreating dedicated Redis container ${REDIS_CONTAINER} (127.0.0.1:${REDIS_PORT})`)
  removeContainer(REDIS_CONTAINER)
  const result = docker([
    'run', '-d', '--name', REDIS_CONTAINER,
    '-p', `127.0.0.1:${REDIS_PORT}:6379`,
    REDIS_IMAGE,
    'redis-server', '--requirepass', REDIS_PASSWORD, '--appendonly', 'no', '--save', '',
  ])
  if (result.status !== 0) throw new Error(`start dedicated Redis failed: ${result.stderr}`)
}

export function waitRedisReady(timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const ping = docker([
      'exec', REDIS_CONTAINER, 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'ping',
    ], { allowFailure: true })
    if (ping.stdout.trim() === 'PONG') return true
    spawnSync(process.platform === 'win32' ? 'ping.exe' : 'sleep', process.platform === 'win32'
      ? ['-n', '2', '127.0.0.1'] : ['1'], { windowsHide: true })
  }
  throw new Error('dedicated Redis did not become ready')
}

export function stopRedis() {
  docker(['stop', '-t', '1', REDIS_CONTAINER])
}

export function startRedis() {
  docker(['start', REDIS_CONTAINER])
}

export function pauseRedis() {
  docker(['pause', REDIS_CONTAINER])
}

export function unpauseRedis() {
  docker(['unpause', REDIS_CONTAINER])
}

export function redisCli(args) {
  return docker([
    'exec', REDIS_CONTAINER, 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', ...args,
  ])
}

export function redisPing() {
  const result = docker([
    'exec', REDIS_CONTAINER, 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning', 'ping',
  ], { allowFailure: true })
  return result.stdout.trim()
}

// ---------------------------------------------------------------------------
// SQL through the host mysql client against the HC-03 container only
// ---------------------------------------------------------------------------

export function sqlStatement(text, options = {}) {
  const args = [
    '-h', '127.0.0.1', '-P', String(MYSQL_PORT), '-u', 'root',
    '--default-character-set=utf8mb4', '-N', '-B',
  ]
  const database = Object.hasOwn(options, 'database') ? options.database : DB_NAME
  if (database) args.push(database)
  args.push('-e', text)
  const result = spawnSync(mysqlBinary(), args, {
    cwd: ROOT,
    encoding: 'utf8',
    env: { ...process.env, MYSQL_PWD: MYSQL_ROOT_PASSWORD },
    windowsHide: true,
    timeout: options.timeout ?? 120_000,
  })
  if (result.error) throw result.error
  const stderr = (result.stderr ?? '').split(/\r?\n/)
    .filter(line => line && !line.includes('Using a password')).join('\n')
  if (result.status !== 0) {
    throw new Error(`mysql failed (${result.status}): ${stderr || result.stdout}`)
  }
  return { stdout: (result.stdout ?? '').trim(), stderr }
}

export function sqlRows(text, options = {}) {
  const { stdout } = sqlStatement(text, options)
  if (!stdout) return []
  return stdout.split(/\r?\n/).map(line => line.split('\t'))
}

export function sqlScalar(text, options = {}) {
  return sqlRows(text, options)[0]?.[0] ?? null
}

export function sqlNumber(text, options = {}) {
  const value = sqlScalar(text, options)
  return value === null || value === '' ? null : Number(value)
}

export function sqlQuote(value) {
  return `'${String(value).replaceAll('\\', '\\\\').replaceAll("'", "''")}'`
}

export function sqlTimestamp(date = new Date()) {
  return date.toISOString().replace('T', ' ').replace('Z', '')
}

/** One interactive mysql client kept open so it can hold real row locks. */
export class MySqlSession {
  constructor() {
    const args = [
      '-h', '127.0.0.1', '-P', String(MYSQL_PORT), '-u', 'root', '-D', DB_NAME,
      '-B', '-N', '--default-character-set=utf8mb4',
    ]
    this.child = spawn(mysqlBinary(), args, {
      cwd: ROOT,
      env: { ...process.env, MYSQL_PWD: MYSQL_ROOT_PASSWORD },
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    })
    this.stdout = ''
    this.stderr = ''
    this.child.stdout.setEncoding('utf8')
    this.child.stderr.setEncoding('utf8')
    this.child.stdout.on('data', chunk => { this.stdout += chunk })
    this.child.stderr.on('data', chunk => { this.stderr += chunk })
    this.child.on('error', error => { this.stderr += String(error) })
    this.child.stdin.on('error', () => { /* session already gone after a DB stop */ })
  }

  send(text) {
    this.child.stdin.write(`${text.replace(/;?\s*$/, '')};\n`)
    return this
  }

  async close() {
    try { this.child.stdin.write('ROLLBACK;\nQUIT;\n') } catch { /* already closed */ }
    await sleep(300)
    try { this.child.kill() } catch { /* already gone */ }
  }
}

export function dataLockWaits() {
  try {
    return sqlNumber(`SELECT COUNT(*) FROM performance_schema.data_lock_waits`)
  } catch {
    return null
  }
}

export function budgetRowLockWaiters() {
  try {
    return sqlNumber(`SELECT COUNT(*) FROM performance_schema.data_lock_waits w
      JOIN performance_schema.data_locks bl
        ON bl.ENGINE_TRANSACTION_ID=w.BLOCKING_ENGINE_TRANSACTION_ID
       AND bl.LOCK_STATUS='GRANTED'
      WHERE bl.OBJECT_NAME='budget'`) ?? 0
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// Processes
// ---------------------------------------------------------------------------

export function pidFile(name) {
  return join(PID_DIR, `${name}.pid`)
}

export function pidOf(name) {
  const file = pidFile(name)
  if (!existsSync(file)) return null
  const pid = Number(readFileSync(file, 'utf8').trim())
  return Number.isFinite(pid) && pid > 0 ? pid : null
}

export function isProcessRunning(pid) {
  if (!pid) return false
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

export function startProc(name, command, args, options = {}) {
  const logPath = join(LOG_DIR, `${name}.log`)
  mkdirSync(LOG_DIR, { recursive: true })
  appendFileSync(logPath, `\n===== ${nowIso()} start ${command} ${args.join(' ')} =====\n`, 'utf8')
  // Detached child with a file-backed stdio: the JVM must survive this
  // harness process exiting (--keep-running / --attach) and must not die on a
  // closed stdout pipe.
  const fd = openSync(logPath, 'a')
  const child = spawn(command, args, {
    cwd: options.cwd ?? ROOT,
    env: options.env ?? process.env,
    windowsHide: true,
    detached: true,
    stdio: ['ignore', fd, fd],
  })
  closeSync(fd)
  if (child.pid) writeFileSync(pidFile(name), String(child.pid), 'utf8')
  child.on('exit', code => {
    appendFileSync(logPath, `===== ${nowIso()} exit ${code} =====\n`, 'utf8')
  })
  child.unref()
  return { child, logPath }
}

/** Graceful stop (used for teardown). */
export async function stopProc(name, timeoutMs = 25_000) {
  const pid = pidOf(name)
  if (!pid) return
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], {
      encoding: 'utf8', windowsHide: true, timeout: timeoutMs,
    })
  } else {
    try { process.kill(pid, 'SIGTERM') } catch { /* gone */ }
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline && isProcessRunning(pid)) await sleep(200)
    if (isProcessRunning(pid)) {
      try { process.kill(pid, 'SIGKILL') } catch { /* gone */ }
    }
  }
  if (existsSync(pidFile(name))) unlinkSync(pidFile(name))
}

/**
 * Forceful kill, the Windows equivalent of SIGKILL: taskkill /F /T terminates
 * the JVM and its children without shutdown hooks, so no graceful flush can
 * mask the durability behaviour under test.
 */
export async function killProc(name) {
  const pid = pidOf(name)
  if (!pid) throw new Error(`no pid recorded for ${name}`)
  const started = Date.now()
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], {
      encoding: 'utf8', windowsHide: true, timeout: 30_000,
    })
  } else {
    try { process.kill(pid, 'SIGKILL') } catch { /* gone */ }
  }
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline && isProcessRunning(pid)) await sleep(50)
  if (existsSync(pidFile(name))) unlinkSync(pidFile(name))
  return { pid, killMs: Date.now() - started, stillRunning: isProcessRunning(pid) }
}

// ---------------------------------------------------------------------------
// Builds and HTTP
// ---------------------------------------------------------------------------

export function buildModule(module, label) {
  const executable = process.platform === 'win32'
    ? join(ROOT, module, 'mvnw.cmd')
    : join(ROOT, module, 'mvnw')
  log(`building ${label ?? module} (Maven package -DskipTests)`)
  const result = spawnSync(executable, ['-B', '-DskipTests', 'package'], {
    cwd: join(ROOT, module),
    shell: process.platform === 'win32',
    windowsHide: true,
    timeout: 30 * 60_000,
  })
  if (result.status !== 0) throw new Error(`${module} build failed (${result.status})`)
}

export function ensureJars({ forceBuild = false, skipBuild = false } = {}) {
  let gateway = findJar('gateway', 'gateway-')
  let backend = findJar('backend', 'backend-')
  if (forceBuild && !skipBuild) {
    buildModule('gateway', 'Gateway')
    buildModule('backend', 'Backend')
  } else {
    if (!gateway) buildModule('gateway', 'Gateway')
    if (!backend) buildModule('backend', 'Backend')
  }
  gateway = findJar('gateway', 'gateway-')
  backend = findJar('backend', 'backend-')
  if (!gateway || !backend) throw new Error('缺少可执行 JAR；请去掉 --skip-build 或先构建')
  return { gateway, backend }
}

export function jarSha256(path) {
  // In-process hashing: no dependency on a host `sha256sum` or PowerShell
  // being present in the runner's environment (both were absent in one run).
  try {
    return createHash('sha256').update(readFileSync(path)).digest('hex')
  } catch {
    return null
  }
}

export async function waitHttp(port, path, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const response = await httpRequest({ host: '127.0.0.1', port, path, method: 'GET' }, null, 10_000)
    last = response
    if (response.status === 200) return response
    await sleep(1000)
  }
  throw new Error(`${label} 未就绪 (${path} => HTTP ${last?.status ?? 'n/a'})`)
}

export async function httpStatus(port, path, timeoutMs = 5000) {
  const response = await httpRequest({ host: '127.0.0.1', port, path, method: 'GET' }, null, timeoutMs)
  return { status: response.status, body: response.body, ms: response.ms }
}

// ---------------------------------------------------------------------------
// Append-only event log
// ---------------------------------------------------------------------------

export class EventLog {
  constructor(path) {
    this.path = path
    this.seq = 0
    mkdirSync(join(path, '..'), { recursive: true })
    writeFileSync(path, '', 'utf8')
  }

  record(scenario, rep, phase, detail = {}) {
    const entry = {
      seq: ++this.seq,
      at: nowIso(),
      scenario,
      rep,
      phase,
      ...detail,
    }
    appendFileSync(this.path, `${JSON.stringify(entry)}\n`, 'utf8')
    return entry
  }
}

export class Timeline {
  constructor() {
    this.t0 = Date.now()
  }

  mark(scenario, rep, phase, detail, events) {
    const elapsedMs = Date.now() - this.t0
    if (events) events.record(scenario, rep, phase, { elapsedMs, ...detail })
    return elapsedMs
  }

  reset() {
    this.t0 = Date.now()
  }
}
