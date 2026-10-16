import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, writeFileSync, readFileSync, readdirSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'node:http'
import { capture, isWindows } from '../lib/processes.mjs'
import { projectFixture as fixture } from './helpers.mjs'

const ROOT = dirname(dirname(dirname(fileURLToPath(import.meta.url))))
const invoke = (root, command, args = [], env = process.env) => capture(process.execPath,
  [join(root, 'scripts', 'tasks.mjs'), command, ...args], { cwd: root, env })
  .catch(error => { error.message += `\n${error.output ?? ''}`; throw error })

test('dry-run covers all three native processes with local profile and strict frontend port', async t => {
  const root = fixture(t)
  const output = await invoke(root, 'dev', ['--dry-run'])
  assert.match(output, /\[backend\].*spring-boot:run.*run.profiles=local/)
  assert.match(output, /\[gateway\].*spring-boot:run.*run.profiles=local/)
  assert.match(output, /\[frontend\].*run dev.*--strictPort/)
  assert.equal(existsSync(join(root, '.local-dev')), false)
  assert.equal(existsSync(join(root, '.env')), false)
})

test('verify runs scripts, both Maven verify lifecycles, frontend tests/lint/production build', async t => {
  const root = fixture(t)
  const output = await invoke(root, 'verify', ['--dry-run'])
  assert.match(output, /\[script tests\]/)
  assert.match(output, /\[backend\].* verify/)
  assert.match(output, /\[gateway\].* verify/)
  assert.match(output, /run test -- --run/)
  assert.match(output, /run lint/)
  assert.match(output, /run build/)
  assert.doesNotMatch(output, /excludedGroups|skipTests/)
})

test('verify runs the shared OpenCode Zen Gradle tests when the snapshot wrapper exists', async t => {
  const root = fixture(t)
  const shared = join(root, 'shared-libs', 'opencode-zen')
  mkdirSync(shared, { recursive: true })
  writeFileSync(join(shared, isWindows ? 'gradlew.bat' : 'gradlew'),
    isWindows ? '@echo off\r\nexit /b 0\r\n' : '#!/bin/sh\nexit 0\n', { mode: 0o755 })
  const output = await invoke(root, 'verify', ['--dry-run'])
  assert.match(output, /\[shared-lib tests\].*gradlew.* test/)
  // Without the snapshot the step is absent, so non-OpenCode checkouts are unaffected.
  assert.doesNotMatch(await invoke(fixture(t), 'verify', ['--dry-run']), /shared-lib tests/)
})

test('test keeps architecture checks; build packages both Maven modules and frontend', async t => {
  const root = fixture(t)
  const unit = await invoke(root, 'test', ['--dry-run'])
  assert.match(unit, /\[gateway\].* test/)
  assert.doesNotMatch(unit, /excludedGroups|skipTests| verify/)
  const build = await invoke(root, 'build', ['--dry-run'])
  assert.match(build, /\[backend\].*-DskipTests package/)
  assert.match(build, /\[gateway\].*-DskipTests package/)
  assert.match(build, /run build/)
})

test('infra plans only three named services; missing .env fails without creating one', async t => {
  const root = fixture(t)
  const up = await invoke(root, 'infra:up', ['--dry-run'])
  assert.match(up, /up -d --wait mysql redis minio/)
  assert.doesNotMatch(up, /stop backend|down|prune|--build/)
  const down = await invoke(root, 'infra:down', ['--dry-run'])
  assert.match(down, /stop mysql redis minio/)
  await assert.rejects(invoke(root, 'infra:up'), /exit 1/)
  assert.equal(existsSync(join(root, '.env')), false)
})

test('unknown commands, unknown flags and invalid ports fail', async t => {
  const root = fixture(t)
  await assert.rejects(invoke(root, 'unknown'), /exit 1/)
  await assert.rejects(invoke(root, 'build', ['--unknown']), /exit 1/)
  await assert.rejects(invoke(root, 'dev', ['--dry-run'], { ...process.env, AICOSTOPS_GATEWAY_PORT: 'zero' }), /exit 1/)
})

test('setup delegates npm ci to frontend without modifying backend or root configuration', async t => {
  const root = fixture(t)
  mkdirSync(join(root, 'frontend'))
  const project = { name: 'aicostops-task-fixture', version: '1.0.0', private: true }
  const lock = JSON.stringify({ name: project.name, version: project.version, lockfileVersion: 3,
    packages: { '': { name: project.name, version: project.version } } })
  writeFileSync(join(root, 'frontend', 'package.json'), JSON.stringify(project))
  writeFileSync(join(root, 'frontend', 'package-lock.json'), lock)
  const output = await invoke(root, 'setup')
  assert.match(output, /\[frontend\].* ci/)
  assert.equal(readFileSync(join(root, 'frontend', 'package-lock.json'), 'utf8'), lock)
  assert.equal(existsSync(join(root, 'backend')), false)
  assert.equal(existsSync(join(root, '.env')), false)
})

test('native build failure stops later steps and cleans its temporary POM', async t => {
  const root = fixture(t)
  const source = '<project><build><plugins/></build></project>'
  for (const module of ['backend', 'gateway']) {
    mkdirSync(join(root, module))
    writeFileSync(join(root, module, 'pom.xml'), source)
    writeFileSync(join(root, module, isWindows ? 'mvnw.cmd' : 'mvnw'),
      isWindows ? '@echo off\r\nexit /b 23\r\n' : '#!/bin/sh\nexit 23\n', { mode: 0o755 })
  }
  await assert.rejects(invoke(root, 'build'), /exit 1/)
  assert.equal(readdirSync(join(root, 'backend')).some(name => name.startsWith('.task-build-')), false)
  assert.equal(existsSync(join(root, '.local-dev', 'builds')), true)
  for (const run of readdirSync(join(root, '.local-dev', 'builds'))) assert.equal(existsSync(join(root, '.local-dev', 'builds', run, 'gateway')), false)
  assert.equal(readFileSync(join(root, 'backend', 'pom.xml'), 'utf8'), source)
})

test('PowerShell wrappers share the runner and propagate failure from another cwd', { skip: !isWindows }, async t => {
  const root = fixture(t)
  for (const name of ['start-infra', 'stop-infra', 'status']) {
    const path = join(root, 'scripts', 'dev', `${name}.ps1`)
    await assert.rejects(capture('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', path], { cwd: ROOT, timeout: 5000 }), /exit 1/)
  }
})

test('status checks three configured HTTP endpoints without stopping them', async t => {
  const root = fixture(t)
  const env = { ...process.env }
  const servers = []
  t.after(async () => { await Promise.all(servers.map(server => new Promise(resolve => server.close(resolve)))) })
  for (const name of ['backend', 'gateway', 'frontend']) {
    const server = createServer((_, response) => { response.setHeader('Content-Type', 'application/json'); response.end('{"status":"UP"}') })
    servers.push(server)
    await new Promise(resolve => server.listen(0, name === 'frontend' ? '::1' : '127.0.0.1', resolve))
    env[`AICOSTOPS_${name.toUpperCase()}_PORT`] = String(server.address().port)
  }
  const output = await invoke(root, 'status', [], env)
  assert.equal((output.match(/HTTP 检查通过/g) ?? []).length, 3)
  assert.equal(servers.every(server => server.listening), true)
})
