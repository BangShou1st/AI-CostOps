/**
 * HC-01 shared utilities: local configuration, MySQL/Redis/docker access,
 * Prometheus parsing, process lifecycle and small statistics helpers.
 *
 * The harness is zero-dependency (Node 24 built-ins only) so the whole load
 * run is reproducible with the repository's existing toolchain.
 */
import { spawn, spawnSync } from 'node:child_process'
import { createWriteStream, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync, unlinkSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import http from 'node:http'
import os from 'node:os'

export const ROOT = fileURLToPath(new URL('../../..', import.meta.url))
export const HC01_DIR = join(ROOT, '.local-dev', 'hc01')
export const LOG_DIR = join(HC01_DIR, 'logs')
export const RAW_DIR = join(HC01_DIR, 'raw')
export const PID_DIR = join(HC01_DIR, 'pids')

export const DB_NAME = process.env.HC01_DB ?? 'aicostops_hc01'
export const GATEWAY_DB_USER = 'gw_hc01'
export const PROVIDER_CODE = 'HC01MOCK'
export const MODEL_KEY = 'hc01-chat'

export function ensureDirs() {
  for (const dir of [HC01_DIR, LOG_DIR, RAW_DIR, PID_DIR]) mkdirSync(dir, { recursive: true })
}

export function loadDotEnv() {
  const file = join(ROOT, '.env')
  if (!existsSync(file)) throw new Error('缺少 .env：HC-01 需要本机 MySQL/Redis/JWT/KEK 配置。')
  const values = {}
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line || line.startsWith('#') || !line.includes('=')) continue
    const index = line.indexOf('=')
    values[line.slice(0, index)] = line.slice(index + 1)
  }
  return values
}

export function config() {
  const dotenv = loadDotEnv()
  const pick = (name, fallback) => process.env[name] ?? dotenv[name] ?? fallback
  return {
    dotenv,
    mysqlHost: pick('HC01_MYSQL_HOST', '127.0.0.1'),
    mysqlPort: Number(pick('MYSQL_PORT', '3307')),
    mysqlRootPassword: dotenv.MYSQL_ROOT_PASSWORD,
    mysqlUser: dotenv.MYSQL_USER,
    mysqlPassword: dotenv.MYSQL_PASSWORD,
    redisHost: pick('HC01_REDIS_HOST', '127.0.0.1'),
    redisPort: Number(pick('HC01_REDIS_PORT', '16379')),
    redisPassword: pick('HC01_REDIS_PASSWORD', ''),
    redisContainer: pick('HC01_REDIS_CONTAINER', 'ai-costops-hc01-redis'),
    mysqlContainer: pick('HC01_MYSQL_CONTAINER', 'ai-costops-mysql-1'),
    jwtSigningKey: dotenv.AICOSTOPS_JWT_SIGNING_KEY,
    storageAccessKey: dotenv.MINIO_ROOT_USER,
    storageSecretKey: dotenv.MINIO_ROOT_PASSWORD,
    storageBucket: dotenv.MINIO_BUCKET,
    minioPort: Number(dotenv.MINIO_API_PORT ?? '9000'),
    credentialHmacKey: dotenv.AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1,
    requestHmacKey: dotenv.AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1,
    providerKek: dotenv.AICOSTOPS_PROVIDER_KEK_V1,
    backendPort: Number(pick('HC01_BACKEND_PORT', '18080')),
    gatewayPort: Number(pick('HC01_GATEWAY_PORT', '18081')),
    gatewayPort2: Number(pick('HC01_GATEWAY_PORT_2', '18082')),
    gatewayProbePort: Number(pick('HC01_GATEWAY_PROBE_PORT', '18083')),
    mockPort: Number(pick('HC01_MOCK_PORT', '18089')),
  }
}

export function pythonCommand() {
  if (process.env.HC01_PYTHON) return { command: process.env.HC01_PYTHON, prefix: [] }
  if (process.platform === 'win32') {
    // The WindowsApps `python.exe` alias can be a Store stub that exits 49;
    // the py launcher resolves the real interpreter deterministically.
    return { command: 'py', prefix: [] }
  }
  return { command: 'python3', prefix: [] }
}

export function mysqlBinary() {
  const configured = process.env.HC01_MYSQL_BIN
  if (configured) return configured
  if (process.platform === 'win32') {
    const path = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
    if (existsSync(path)) return path
    return 'mysql.exe'
  }
  return 'mysql'
}

export function mysql(sql, options = {}) {
  const cfg = options.config ?? config()
  const args = [
    '-h', cfg.mysqlHost, '-P', String(cfg.mysqlPort),
    '-u', options.user ?? 'root',
    '--default-character-set=utf8mb4', '-N', '-B',
  ]
  const database = Object.hasOwn(options, 'database') ? options.database : DB_NAME
  if (database) args.push(database)
  args.push('-e', sql)
  const result = spawnSync(mysqlBinary(), args, {
    cwd: ROOT,
    encoding: 'utf8',
    env: { ...process.env, MYSQL_PWD: options.password ?? cfg.mysqlRootPassword },
    windowsHide: true,
    timeout: options.timeout ?? 120_000,
  })
  if (result.error) throw result.error
  const stderr = (result.stderr ?? '').split(/\r?\n/).filter(line => line && !line.includes('Using a password')).join('\n')
  if (result.status !== 0) {
    throw new Error(`mysql failed (${result.status}): ${stderr || result.stdout}`)
  }
  return { stdout: (result.stdout ?? '').trim(), stderr }
}

export function mysqlRows(sql, options = {}) {
  const { stdout } = mysql(sql, options)
  if (!stdout) return []
  return stdout.split(/\r?\n/).map(line => line.split('\t'))
}

export function mysqlScalar(sql, options = {}) {
  return mysqlRows(sql, options)[0]?.[0] ?? null
}

export function mysqlNumber(sql, options = {}) {
  const value = mysqlScalar(sql, options)
  return value === null || value === '' ? null : Number(value)
}

export function redisCli(args, options = {}) {
  const cfg = options.config ?? config()
  const result = spawnSync('docker', [
    'exec', cfg.redisContainer, 'redis-cli',
    '-a', cfg.redisPassword, '--no-auth-warning', ...args,
  ], { cwd: ROOT, encoding: 'utf8', windowsHide: true, timeout: options.timeout ?? 30_000 })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`redis-cli failed: ${result.stderr || result.stdout}`)
  return result.stdout
}

export function docker(args, options = {}) {
  const result = spawnSync('docker', args, {
    cwd: ROOT, encoding: 'utf8', windowsHide: true, timeout: options.timeout ?? 60_000,
  })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`docker ${args.join(' ')} failed: ${result.stderr || result.stdout}`)
  return result.stdout
}

export function dockerAvailable() {
  const result = spawnSync('docker', ['version', '--format', '{{.Server.Version}}'], {
    encoding: 'utf8', windowsHide: true, timeout: 15_000,
  })
  return result.status === 0 ? result.stdout.trim() : null
}

export function selectRows(sql, options = {}) {
  return mysqlRows(sql, options)
}

export function sqlQuote(value) {
  return `'${String(value).replaceAll('\\', '\\\\').replaceAll("'", "''")}'`
}

export function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

export function nowIso() {
  return new Date().toISOString()
}

export function percentile(values, p) {
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  const index = Math.min(sorted.length - 1, Math.floor((sorted.length * p) / 100))
  return sorted[index]
}

export function stats(values) {
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  const sum = sorted.reduce((total, value) => total + value, 0)
  return {
    count: sorted.length,
    min: sorted[0],
    p50: percentile(sorted, 50),
    p90: percentile(sorted, 90),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1],
    mean: Math.round((sum / sorted.length) * 100) / 100,
  }
}

export function responseSnippet(body, limit = 400) {
  if (!body) return ''
  return body.length > limit ? `${body.slice(0, limit)}…` : body
}

export function httpRequest(options, body, timeoutMs = 60_000) {
  return new Promise(resolve => {
    const started = process.hrtime.bigint()
    if (body !== undefined && body !== null) {
      options.headers = {
        ...(options.headers ?? {}),
        'Content-Length': Buffer.byteLength(body),
      }
    }
    const req = http.request(options, response => {
      let text = ''
      response.setEncoding('utf8')
      response.on('data', chunk => { text += chunk })
      response.on('end', () => {
        resolve({
          status: response.statusCode,
          headers: response.headers,
          body: text,
          ms: Number(process.hrtime.bigint() - started) / 1e6,
        })
      })
    })
    req.on('error', error => {
      resolve({
        status: -1,
        error: error.code || error.message,
        body: '',
        ms: Number(process.hrtime.bigint() - started) / 1e6,
      })
    })
    req.setTimeout(timeoutMs, () => req.destroy(new Error('client-timeout')))
    if (body !== undefined && body !== null) req.write(body)
    req.end()
  })
}

export async function waitFor(label, check, timeoutMs, intervalMs = 1000, onTick) {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    try {
      last = await check()
      if (last) return last
    } catch (error) {
      last = error
    }
    if (onTick) onTick(last)
    await sleep(intervalMs)
  }
  throw new Error(`等待超时: ${label} (${timeoutMs}ms)`)
}

export function parsePrometheus(text) {
  const series = []
  for (const line of text.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue
    const match = /^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)/.exec(line)
    if (!match) continue
    const value = Number(match[3])
    if (!Number.isFinite(value)) continue
    series.push({ name: match[1], labels: match[2] ?? '', value })
  }
  return series
}

export function sumMetric(series, name, labelIncludes = '') {
  let total = 0
  let seen = false
  for (const item of series) {
    if (item.name !== name) continue
    if (labelIncludes && !item.labels.includes(labelIncludes)) continue
    total += item.value
    seen = true
  }
  return seen ? total : null
}

export function maxMetric(series, name, labelIncludes = '') {
  let value = null
  for (const item of series) {
    if (item.name !== name) continue
    if (labelIncludes && !item.labels.includes(labelIncludes)) continue
    value = value === null ? item.value : Math.max(value, item.value)
  }
  return value
}

export function labelValue(labels, name) {
  const match = new RegExp(`${name}="([^"]*)"`).exec(labels)
  return match ? match[1] : null
}

export async function fetchPrometheus(port, timeoutMs = 5000) {
  const response = await httpRequest({
    host: '127.0.0.1', port, path: '/actuator/prometheus', method: 'GET',
  }, null, timeoutMs)
  if (response.status !== 200) throw new Error(`prometheus ${port} HTTP ${response.status}`)
  return parsePrometheus(response.body)
}

export function writeJson(path, value) {
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
}

export function readJson(path, fallback = null) {
  if (!existsSync(path)) return fallback
  return JSON.parse(readFileSync(path, 'utf8'))
}

export function pidFile(name) {
  return join(PID_DIR, `${name}.pid`)
}

export function isProcessRunning(pid) {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

export function startProcess(name, command, args, options = {}) {
  const logPath = join(LOG_DIR, `${name}.log`)
  mkdirSync(LOG_DIR, { recursive: true })
  const out = createWriteStream(logPath, { flags: 'a' })
  out.write(`\n===== ${nowIso()} start ${command} ${args.join(' ')} =====\n`)
  const child = spawn(command, args, {
    cwd: options.cwd ?? ROOT,
    env: options.env ?? process.env,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  child.stdout.pipe(out, { end: false })
  child.stderr.pipe(out, { end: false })
  if (child.pid) writeFileSync(pidFile(name), String(child.pid), 'utf8')
  child.on('exit', code => {
    out.write(`===== ${nowIso()} exit ${code} =====\n`)
    out.end()
  })
  return { child, logPath }
}

export async function stopProcess(name, timeoutMs = 20_000) {
  const file = pidFile(name)
  if (!existsSync(file)) return
  const pid = Number(readFileSync(file, 'utf8').trim())
  if (!Number.isFinite(pid) || pid <= 0) {
    unlinkSync(file)
    return
  }
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], {
      encoding: 'utf8', windowsHide: true, timeout: timeoutMs,
    })
  } else {
    try { process.kill(-pid, 'SIGTERM') } catch { try { process.kill(pid, 'SIGTERM') } catch { /* gone */ } }
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline && isProcessRunning(pid)) await sleep(200)
    if (isProcessRunning(pid)) {
      try { process.kill(-pid, 'SIGKILL') } catch { try { process.kill(pid, 'SIGKILL') } catch { /* gone */ } }
    }
  }
  if (existsSync(file)) unlinkSync(file)
}

export function capture(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? ROOT,
    encoding: 'utf8',
    env: options.env ?? process.env,
    windowsHide: true,
    timeout: options.timeout ?? 60_000,
  })
  return {
    status: result.status,
    stdout: (result.stdout ?? '').trim(),
    stderr: (result.stderr ?? '').trim(),
  }
}

export function gitValue(args) {
  const result = capture('git', args)
  return result.status === 0 ? result.stdout : null
}

export function findJar(module, prefix) {
  const dir = join(ROOT, module, 'target')
  if (!existsSync(dir)) return null
  const files = []
  for (const name of readdirSync(dir)) {
    if (name.startsWith(prefix) && name.endsWith('.jar') && !name.endsWith('-sources.jar')) {
      files.push(join(dir, name))
    }
  }
  return files.length ? files[0] : null
}

export function hostEnvironment() {
  const cpus = os.cpus()
  const dockerMemory = (() => {
    const result = spawnSync('docker', ['info', '--format', '{{.MemTotal}}'], { encoding: 'utf8', windowsHide: true })
    return result.status === 0 ? Number(result.stdout.trim()) : null
  })()
  return {
    hostname: os.hostname(),
    platform: process.platform,
    release: os.release(),
    arch: process.arch,
    cpuModel: cpus[0]?.model ?? null,
    cpuCores: cpus.length,
    totalMemBytes: os.totalmem(),
    freeMemBytes: os.freemem(),
    dockerServer: dockerAvailable(),
    dockerMemBytes: dockerMemory,
    node: process.version,
    java: capture('java', ['-version']).stderr.split(/\r?\n/)[0] ?? null,
    python: capture(pythonCommand().command, ['--version']).stdout,
    gitSha: gitValue(['rev-parse', 'HEAD']),
    gitBranch: gitValue(['rev-parse', '--abbrev-ref', 'HEAD']),
    gitDirty: (gitValue(['status', '--porcelain']) ?? '') !== '',
  }
}

export function decimalToScaled(value) {
  const text = String(value ?? '0').trim()
  const negative = text.startsWith('-')
  const unsigned = negative ? text.slice(1) : text
  const [whole, fraction = ''] = unsigned.split('.')
  const padded = `${fraction}00000000`.slice(0, 8)
  const scaled = BigInt(`${whole || '0'}${padded}`)
  return negative ? -scaled : scaled
}

export function scaledToDecimal(scaled) {
  const negative = scaled < 0n
  const text = (negative ? -scaled : scaled).toString().padStart(9, '0')
  return `${negative ? '-' : ''}${text.slice(0, -8)}.${text.slice(-8)}`
}
