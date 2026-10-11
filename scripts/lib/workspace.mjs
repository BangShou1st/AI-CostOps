import { existsSync, readFileSync, writeFileSync, mkdirSync, unlinkSync } from 'node:fs'
import { join } from 'node:path'
import { parseEnv } from 'node:util'
import { createServer } from 'node:net'
import { randomUUID } from 'node:crypto'

export function configuration(root, overrides = process.env) {
  const path = join(root, '.env')
  return { ...(existsSync(path) ? parseEnv(readFileSync(path, 'utf8')) : {}), ...overrides }
}

export function port(value, fallback, name) {
  const text = String(value ?? fallback)
  if (!/^\d+$/.test(text) || Number(text) < 1 || Number(text) > 65535) throw new Error(`${name} 必须是 1–65535 的整数端口。`)
  return Number(text)
}

export function appPorts(env) {
  return Object.fromEntries(['backend', 'gateway', 'frontend'].map((name, index) => [name,
    port(env[`AICOSTOPS_${name.toUpperCase()}_PORT`], [8080, 8081, 5173][index], `AICOSTOPS_${name.toUpperCase()}_PORT`)]))
}

export function nativeEnvironment(env, ports) {
  const result = { ...env, SPRING_PROFILES_ACTIVE: 'local', BACKEND_PORT: String(ports.backend), AICOSTOPS_GATEWAY_PORT: String(ports.gateway) }
  const mappings = {
    SPRING_DATASOURCE_USERNAME: 'MYSQL_USER', SPRING_DATASOURCE_PASSWORD: 'MYSQL_PASSWORD',
    AICOSTOPS_REDIS_PASSWORD: 'REDIS_PASSWORD', AICOSTOPS_STORAGE_ACCESS_KEY: 'MINIO_ROOT_USER',
    AICOSTOPS_STORAGE_SECRET_KEY: 'MINIO_ROOT_PASSWORD', AICOSTOPS_STORAGE_BUCKET: 'MINIO_BUCKET',
  }
  for (const [destination, source] of Object.entries(mappings)) {
    if (result[destination] === undefined && env[source] !== undefined) result[destination] = env[source]
  }
  if (env.MYSQL_DATABASE !== undefined) result.SPRING_DATASOURCE_URL ??= `jdbc:mysql://localhost:${port(env.MYSQL_PORT, 3307, 'MYSQL_PORT')}/${env.MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC`
  result.AICOSTOPS_REDIS_HOST ??= 'localhost'
  result.AICOSTOPS_REDIS_PORT ??= String(port(env.REDIS_PORT, 6379, 'REDIS_PORT'))
  result.AICOSTOPS_STORAGE_ENDPOINT ??= `http://localhost:${port(env.MINIO_API_PORT, 9000, 'MINIO_API_PORT')}`
  // Compose's Nginx port/origin are independent of native Vite.
  result.AICOSTOPS_ALLOWED_ORIGINS = env.AICOSTOPS_LOCAL_ALLOWED_ORIGINS ?? `http://localhost:${ports.frontend},http://127.0.0.1:${ports.frontend}`
  result.AICOSTOPS_REFRESH_COOKIE_SECURE = 'false'
  result.AICOSTOPS_DEV_RESET_PAGE_URL ??= `http://localhost:${ports.frontend}/reset-password`
  result.AICOSTOPS_IAM_DEV_INVITE_PAGE_URL ??= `http://localhost:${ports.frontend}/invite`
  return result
}

function availableOn(value, host) {
  return new Promise(resolve => {
    const server = createServer()
    server.once('error', error => resolve(host === '::1' && ['EAFNOSUPPORT', 'EADDRNOTAVAIL'].includes(error.code)))
    server.listen({ port: value, host, exclusive: true }, () => server.close(() => resolve(true)))
  })
}

export async function available(value) {
  return await availableOn(value, '127.0.0.1') && await availableOn(value, '::1')
}

export async function httpHealthy(name, value, signal, timeout = 2000) {
  const results = await Promise.all(['127.0.0.1', '[::1]'].map(async host => {
    try {
      const response = await fetch(`http://${host}:${value}${name === 'frontend' ? '/' : '/actuator/health'}`, {
        signal: AbortSignal.any([AbortSignal.timeout(timeout), ...(signal ? [signal] : [])]),
      })
      return response.ok && (name === 'frontend' || (await response.json()).status === 'UP')
    } catch { return false }
  }))
  return results.some(Boolean)
}

export async function requireFreePorts(ports) {
  if (new Set(Object.values(ports)).size !== Object.values(ports).length) throw new Error('应用端口不能重复。')
  for (const [name, value] of Object.entries(ports)) {
    if (!await available(value)) throw new Error(`${name} 端口 ${value} 已占用；停止已有实例或设置 AICOSTOPS_${name.toUpperCase()}_PORT。脚本不会终止已有实例。`)
  }
}

export function isolatedPom(root, module, outputRoot) {
  const source = readFileSync(join(root, module, 'pom.xml'), 'utf8')
  if (!source.includes('<build>') || /<directory>/.test(source)) throw new Error(`${module} POM 构建目录需要显式审查，未执行构建。`)
  const directory = join(outputRoot, module, 'target').replaceAll('\\', '/')
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  mkdirSync(join(outputRoot, module, 'target'), { recursive: true })
  const path = join(root, module, `.task-build-${randomUUID()}.pom.xml`)
  writeFileSync(path, source.replace('<build>', `<build>\n    <directory>${directory}</directory>`), 'utf8')
  return { path, cleanup: () => { if (existsSync(path)) unlinkSync(path) } }
}
