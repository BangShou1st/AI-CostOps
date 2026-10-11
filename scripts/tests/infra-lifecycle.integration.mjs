// Isolated Compose control test: three tiny Redis fixtures exercise service
// selection/ownership/volume preservation. This is not a MySQL/MinIO smoke test.
import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:net'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { capture } from '../lib/processes.mjs'
import { projectFixture, writeArtifact } from './helpers.mjs'

test('infra CLI rejects foreign ports, supports repeated up and stops without removing volumes', { timeout: 120_000 }, async t => {
  const project = `aicostops-tasks-test-${randomUUID()}`
  let root
  let output = ''
  t.after(async () => {
    if (!root) return
    if (!/^aicostops-tasks-test-[a-f0-9-]+$/.test(project)) throw new Error('Unsafe Compose cleanup project')
    try {
      if (process.env.AICOSTOPS_TASK_TEST_ARTIFACT_DIR) {
        try {
          output += await capture('docker', ['compose', '-p', project, '-f', join(root, 'compose.yaml'), 'logs', '--no-color', '--tail', '200'], { cwd: root, timeout: 15_000 })
        } catch (error) { output += `\nFixture logs: ${error.output || error.message}\n` }
      }
    } finally {
      try {
        await capture('docker', ['compose', '-p', project, '-f', join(root, 'compose.yaml'), 'down', '--volumes'], { cwd: root, timeout: 30_000 })
      } finally { writeArtifact('infra-lifecycle.log', output) }
    }
  })
  root = projectFixture(t)
  await capture('docker', ['info', '--format', '{{.ServerVersion}}'])
  const env = { ...process.env, COMPOSE_PROJECT_NAME: project }
  const sockets = []
  t.after(async () => { for (const socket of sockets) if (socket.listening) await new Promise(resolve => socket.close(resolve)) })
  for (const name of ['MYSQL_PORT', 'REDIS_PORT', 'MINIO_API_PORT', 'MINIO_CONSOLE_PORT']) {
    const socket = createServer()
    await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve))
    sockets.push(socket)
    env[name] = String(socket.address().port)
  }
  for (const socket of sockets.slice(1)) await new Promise(resolve => socket.close(resolve))
  const services = ['mysql', 'redis', 'minio'].map((name, index) => `  ${name}:
    image: redis:8.8.1-alpine
    command: ["redis-server", "--appendonly", "yes"]
    ports:
      - "127.0.0.1:${env[['MYSQL_PORT', 'REDIS_PORT', 'MINIO_API_PORT'][index]]}:6379"
${name === 'minio' ? `      - "127.0.0.1:${env.MINIO_CONSOLE_PORT}:6380"\n` : ''}    volumes:
      - ${name}-test-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 1s
      timeout: 1s
      retries: 20`).join('\n')
  writeFileSync(join(root, 'compose.yaml'), `name: ${project}\nservices:\n${services}\nvolumes:\n  mysql-test-data:\n  redis-test-data:\n  minio-test-data:\n`)
  writeFileSync(join(root, 'compose.dev.yaml'), 'services: {}\n')
  writeFileSync(join(root, '.env'), ['MYSQL_PORT', 'REDIS_PORT', 'MINIO_API_PORT', 'MINIO_CONSOLE_PORT'].map(name => `${name}=${env[name]}`).join('\n'))
  const invoke = async command => {
    output += `\n$ tasks.mjs ${command}\n`
    try {
      const result = await capture(process.execPath, [join(root, 'scripts', 'tasks.mjs'), command], { cwd: root, env, timeout: 45_000 })
      output += result
      return result
    } catch (error) {
      output += error.output || error.message
      throw error
    }
  }
  const containerIds = async () => (await capture('docker', ['ps', '-a', '-q', '--filter', `label=com.docker.compose.project=${project}`])).trim().split(/\r?\n/).filter(Boolean).sort()
  const volumes = async () => (await capture('docker', ['volume', 'ls', '-q', '--filter', `label=com.docker.compose.project=${project}`])).trim().split(/\r?\n/).filter(Boolean).sort()
  await assert.rejects(invoke('infra:up'), /exit 1/)
  assert.equal(sockets[0].listening, true)
  assert.deepEqual(await containerIds(), [])
  await new Promise(resolve => sockets[0].close(resolve))
  await invoke('infra:up')
  const ids = await containerIds()
  const dataVolumes = await volumes()
  assert.equal(ids.length, 3)
  assert.equal(dataVolumes.length, 3)
  await invoke('infra:up')
  assert.deepEqual(await containerIds(), ids)
  const status = await invoke('infra:status')
  for (const name of ['mysql', 'redis', 'minio']) assert.ok(status.includes(name))
  await invoke('infra:down')
  assert.deepEqual(await containerIds(), ids)
  assert.deepEqual(await volumes(), dataVolumes)
  const states = await capture('docker', ['ps', '-a', '--filter', `label=com.docker.compose.project=${project}`, '--format', '{{.State}}'])
  assert.deepEqual(states.trim().split(/\r?\n/), ['exited', 'exited', 'exited'])
})
