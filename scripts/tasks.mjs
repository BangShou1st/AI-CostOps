#!/usr/bin/env node
// Single implementation behind package.json and scripts/dev wrappers.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'
import { capture, npmCommand, run, supervise, withSignals, isWindows } from './lib/processes.mjs'
import { configuration, appPorts, nativeEnvironment, available, httpHealthy, requireFreePorts, isolatedPom, port } from './lib/workspace.mjs'

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const COMMANDS = ['help', 'setup', 'maven:settings', 'doctor', 'status', 'dev', 'build', 'test', 'verify', 'infra:up', 'infra:down', 'infra:status']
const [command = 'help', ...options] = process.argv.slice(2)
const dryRun = options.includes('--dry-run')
const poms = []

async function execute(label, executable, args, cwd, signal, env = process.env) {
  signal.throwIfAborted()
  console.log(`\n[${label}] ${executable} ${args.join(' ')}`)
  if (!dryRun) await run({ command: executable, args, cwd, env, label }, signal)
}

async function dockerReady(signal) {
  try { await capture('docker', ['info', '--format', '{{.ServerVersion}}'], { signal }) }
  catch { throw new Error('Docker 引擎不可用。请启动 Docker Desktop 主程序并等待引擎就绪。') }
}

async function applicationStatus(ports) {
  for (const [name, value] of Object.entries(ports)) {
    if (await available(value)) { console.log(`${name}: ${value} 空闲`); continue }
    const healthy = await httpHealthy(name, value)
    console.log(`${name}: ${value} ${healthy ? 'HTTP 检查通过' : '已占用，健康状态未确认'}`)
  }
}

async function waitUntilReady(ports, signal) {
  const deadline = Date.now() + 180_000
  while (Date.now() < deadline) {
    signal.throwIfAborted()
    const ready = await Promise.all(Object.entries(ports).map(([name, value]) => httpHealthy(name, value, signal, 1000)))
    if (ready.every(Boolean)) {
      console.log(`\n三端已就绪：Frontend ${ports.frontend} / Backend ${ports.backend} / Gateway ${ports.gateway}。Ctrl+C 停止本次启动的应用。`)
      return
    }
    await new Promise(resolve => {
      const stop = () => { clearTimeout(timer); resolve() }
      const timer = setTimeout(() => { signal.removeEventListener('abort', stop); resolve() }, 1000)
      signal.addEventListener('abort', stop, { once: true })
      if (signal.aborted) stop()
    })
  }
  throw new Error('应用启动超过 180 秒，未全部通过 HTTP 健康检查。')
}

async function main(signal) {
  if (!COMMANDS.includes(command) || options.some(value => value !== '--dry-run')) throw new Error('未知命令或参数。运行 npm run task -- help 查看入口。')
  if (command === 'help') {
    console.log(`Usage: node scripts/tasks.mjs <${COMMANDS.join('|')}> [--dry-run]\n详见 scripts/README.md；dev 使用 local profile，Ctrl+C 停止本次启动的三端。`)
    return
  }
  const env = configuration(ROOT)
  const ports = appPorts(env)
  const output = join(ROOT, '.local-dev', 'builds', `${command}-${Date.now()}-${randomUUID()}`)
  const maven = (module, goals) => {
    const pom = dryRun ? '<isolated-pom>' : isolatedPom(ROOT, module, output)
    if (!dryRun) poms.push(pom)
    return { command: join(ROOT, module, isWindows ? 'mvnw.cmd' : 'mvnw'),
      args: ['-B', '-f', dryRun ? pom : pom.path, ...goals], cwd: join(ROOT, module), label: module }
  }
  const frontend = args => ({ ...npmCommand(args), cwd: join(ROOT, 'frontend'), label: 'frontend' })
  const step = spec => execute(spec.label, spec.command, spec.args, spec.cwd, signal, spec.env)

  if (command === 'doctor') {
    let failed = false
    const checks = [
      ['Node >=24', async () => { if (Number(process.versions.node.split('.')[0]) < 24) throw new Error('需要 Node 24 或更新版本'); return process.version }],
      ['Java 21', async () => {
        const result = await capture('java', ['-version'], { signal })
        if (!/version "21(?:[.\"])/.test(result)) throw new Error('需要 JDK 21')
        return result.split(/\r?\n/)[0]
      }],
      ['npm', async () => { const spec = npmCommand(['--version']); return (await capture(spec.command, spec.args, { signal })).trim() }],
      ['Docker', async () => { await dockerReady(signal); return '引擎已就绪' }],
    ]
    for (const [name, check] of checks) {
      try { console.log(`${name}: OK ${await check()}`) }
      catch (error) { failed = true; console.log(`${name}: FAIL ${error.message}`) }
    }
    for (const module of ['backend', 'gateway']) {
      const present = existsSync(join(ROOT, module, isWindows ? 'mvnw.cmd' : 'mvnw'))
      console.log(`${module} Maven wrapper: ${present ? 'OK' : '缺失'}`)
      failed ||= !present
    }
    const dependencies = existsSync(join(ROOT, 'frontend', 'node_modules'))
    console.log(`frontend 依赖: ${dependencies ? '存在' : '缺失，请运行 npm run setup'}`)
    failed ||= !dependencies
    console.log(`.env: ${existsSync(join(ROOT, '.env')) ? '存在（不输出配置值）' : '不存在；native local 可用默认配置，infra:* 需要配置文件'}`)
    await applicationStatus(ports)
    if (failed) throw new Error('环境检查未全部通过。')
    return
  }
  if (command === 'status') return applicationStatus(ports)
  if (command === 'setup') return step(frontend(['ci']))

  if (command === 'maven:settings') {
    // Optional mirrors/proxies for container builds; public JitPack libraries need no credentials.
    const source = env.ZEN_MAVEN_SETTINGS_SOURCE ?? join(homedir(), '.m2', 'settings.xml')
    if (!existsSync(source)) {
      throw new Error(`未找到 Maven settings：${source}。如需配置镜像或代理，请用 ZEN_MAVEN_SETTINGS_SOURCE 指定来源。`)
    }
    const target = join(ROOT, '.local-dev', 'maven', 'docker-settings.xml')
    const rewritten = readFileSync(source, 'utf8')
      .replaceAll(/(<host>)\s*(?:127\.0\.0\.1|localhost)\s*(<\/host>)/gi, '$1host.docker.internal$2')
    if (dryRun) { console.log(`[maven:settings] 将 ${source} 的容器安全副本写入 ${target}`); return }
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, rewritten, 'utf8')
    console.log(`[maven:settings] 已生成容器安全 settings：${target}`)
    console.log(`如需镜像或代理，docker build 可通过 --secret id=zen-maven-settings,src=${target} 挂载；默认构建无需 settings。`)
    return
  }

  if (command.startsWith('infra:')) {
    const compose = ['compose', '--env-file', join(ROOT, '.env'), '-f', join(ROOT, 'compose.yaml'), '-f', join(ROOT, 'compose.dev.yaml')]
    if (!dryRun) {
      if (!existsSync(join(ROOT, '.env'))) throw new Error('缺少 .env：先复制 .env.example 并配置本地参数；脚本不会自动创建或覆盖。')
      await dockerReady(signal)
      if (command === 'infra:up') {
        // Repeated up is allowed for this project's services. Foreign listeners
        // and standalone containers must never be replaced or stopped.
        const result = await capture('docker', [...compose, 'ps', '--format', 'json'], { signal, cwd: ROOT })
        const containers = result.trim().startsWith('[') ? JSON.parse(result) : result.trim().split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line))
        const infrastructurePorts = { mysql: port(env.MYSQL_PORT, 3307, 'MYSQL_PORT'), redis: port(env.REDIS_PORT, 6379, 'REDIS_PORT'),
          minio: port(env.MINIO_API_PORT, 9000, 'MINIO_API_PORT'), minioConsole: port(env.MINIO_CONSOLE_PORT, 9001, 'MINIO_CONSOLE_PORT') }
        if (new Set(Object.values(infrastructurePorts)).size !== 4) throw new Error('基础设施端口不能重复。')
        for (const [name, value] of Object.entries(infrastructurePorts)) {
          const own = containers.some(row => row.Service === (name === 'minioConsole' ? 'minio' : name)
            && row.State === 'running' && row.Publishers?.some(publisher => publisher.PublishedPort === value))
          if (!own && !await available(value)) throw new Error(`${name} 端口 ${value} 已被当前 Compose 项目之外的进程占用。请先确认运行方式。`)
        }
      }
    }
    const args = command === 'infra:up' ? ['up', '-d', '--wait', 'mysql', 'redis', 'minio']
      : command === 'infra:down' ? ['stop', 'mysql', 'redis', 'minio'] : ['ps', 'mysql', 'redis', 'minio']
    return execute(command, 'docker', [...compose, ...args], ROOT, signal)
  }

  if (command === 'dev') {
    if (!dryRun) {
      await requireFreePorts(ports)
      await dockerReady(signal)
      if (!existsSync(join(ROOT, 'frontend', 'node_modules'))) throw new Error('缺少 frontend 依赖，请先运行 npm run setup。')
    }
    const localEnv = nativeEnvironment(env, ports)
    const specs = ['backend', 'gateway'].map(module => ({ ...maven(module, [
      'spring-boot:run', '-Dspring-boot.run.profiles=local', `-Dspring-boot.run.arguments=--server.port=${ports[module]}`,
    ]), env: localEnv }))
    specs.push({ ...frontend(['run', 'dev', '--', '--host', '127.0.0.1', '--port', String(ports.frontend), '--strictPort']), env: localEnv })
    if (dryRun) { for (const spec of specs) await step(spec); return }
    console.log(`Maven 隔离输出：${output}`)
    return supervise(specs, { signal, onStarted: readinessSignal => waitUntilReady(ports, readinessSignal) })
  }

  if (!dryRun && command === 'verify') await dockerReady(signal)
  if (command !== 'build') await execute('script tests', process.execPath, ['--test', join(ROOT, 'scripts', 'tests', '*.test.mjs')], ROOT, signal)
  if (command === 'verify') await execute('script lifecycle tests', process.execPath, ['--test', join(ROOT, 'scripts', 'tests', '*.integration.mjs')], ROOT, signal)
  if (!dryRun) console.log(`Maven 隔离输出与测试报告：${output}`)
  for (const module of ['backend', 'gateway']) await step(maven(module, command === 'build' ? ['-DskipTests', 'package'] : [command === 'test' ? 'test' : 'verify']))
  if (command !== 'build') await step(frontend(['run', 'test', '--', '--run']))
  if (command === 'verify') await step(frontend(['run', 'lint']))
  if (command !== 'test') await step(frontend(['run', 'build']))
}

try { process.exitCode = await withSignals(main) }
catch (error) { console.error(`[task failed] ${error.message}`); process.exitCode = 1 }
finally { for (const pom of poms) pom.cleanup() }
