import test from 'node:test'
import assert from 'node:assert/strict'
import { writeFileSync, readFileSync, mkdirSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { createServer } from 'node:net'
import { configuration, appPorts, nativeEnvironment, available, requireFreePorts, isolatedPom, port } from '../lib/workspace.mjs'
import { temporary } from './helpers.mjs'

test('shell environment overrides .env, while quoted passwords remain literal', t => {
  const root = temporary(t)
  writeFileSync(join(root, '.env'), 'MYSQL_USER=file-user\nMYSQL_PASSWORD="test #$ value"\nMYSQL_PORT=3308\n')
  const config = configuration(root, { MYSQL_USER: 'shell-user' })
  assert.equal(config.MYSQL_USER, 'shell-user')
  assert.equal(config.MYSQL_PASSWORD, 'test #$ value')
  assert.equal(config.MYSQL_PORT, '3308')
})

test('native ports/profile/origins do not reuse Compose frontend settings', () => {
  const env = { MYSQL_DATABASE: 'db', MYSQL_USER: 'user', MYSQL_PASSWORD: 'pass', MYSQL_PORT: '3308',
    REDIS_PORT: '6380', REDIS_PASSWORD: 'redis', MINIO_API_PORT: '9010', MINIO_ROOT_USER: 'access',
    MINIO_ROOT_PASSWORD: 'secret', MINIO_BUCKET: 'bucket', FRONTEND_PORT: '8080', BACKEND_PORT: '9008',
    SPRING_PROFILES_ACTIVE: 'prod', AICOSTOPS_ALLOWED_ORIGINS: 'http://localhost:8080',
    AICOSTOPS_FRONTEND_PORT: '5180', AICOSTOPS_BACKEND_PORT: '8090', AICOSTOPS_GATEWAY_PORT: '8091' }
  const ports = appPorts(env)
  const result = nativeEnvironment(env, ports)
  assert.deepEqual(ports, { backend: 8090, gateway: 8091, frontend: 5180 })
  assert.equal(result.SPRING_PROFILES_ACTIVE, 'local')
  assert.equal(result.BACKEND_PORT, '8090')
  assert.equal(result.AICOSTOPS_ALLOWED_ORIGINS, 'http://localhost:5180,http://127.0.0.1:5180')
  assert.match(result.SPRING_DATASOURCE_URL, /localhost:3308\/db\?/)
  assert.equal(result.SPRING_DATASOURCE_PASSWORD, 'pass')
  assert.equal(result.AICOSTOPS_REDIS_PASSWORD, 'redis')
  assert.equal(result.AICOSTOPS_REDIS_PORT, '6380')
  assert.equal(result.AICOSTOPS_STORAGE_ENDPOINT, 'http://localhost:9010')
  assert.equal(result.AICOSTOPS_STORAGE_SECRET_KEY, 'secret')
  assert.equal(result.AICOSTOPS_DEV_RESET_PAGE_URL, 'http://localhost:5180/reset-password')
  assert.equal(env.BACKEND_PORT, '9008')
})

test('explicit native service endpoints and credentials take precedence', () => {
  const result = nativeEnvironment({ MYSQL_PASSWORD: 'compose', SPRING_DATASOURCE_PASSWORD: 'native',
    SPRING_DATASOURCE_URL: 'jdbc:test', MYSQL_DATABASE: 'db', AICOSTOPS_STORAGE_ENDPOINT: 'http://storage',
    AICOSTOPS_REDIS_HOST: 'redis-host', AICOSTOPS_LOCAL_ALLOWED_ORIGINS: 'http://custom' }, appPorts({}))
  assert.equal(result.SPRING_DATASOURCE_PASSWORD, 'native')
  assert.equal(result.SPRING_DATASOURCE_URL, 'jdbc:test')
  assert.equal(result.AICOSTOPS_STORAGE_ENDPOINT, 'http://storage')
  assert.equal(result.AICOSTOPS_REDIS_HOST, 'redis-host')
  assert.equal(result.AICOSTOPS_ALLOWED_ORIGINS, 'http://custom')
})

test('invalid and duplicate ports fail before startup', async () => {
  for (const value of ['', '0', '65536', '-1', '1.5', '5000;command', 'abc']) assert.throws(() => port(value, 8080, 'TEST_PORT'), /TEST_PORT/)
  await assert.rejects(requireFreePorts({ backend: 8080, gateway: 8080 }), /不能重复/)
})

test('occupied listener is reported and remains untouched', async t => {
  const server = createServer()
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise(resolve => server.close(resolve)))
  const value = server.address().port
  assert.equal(await available(value), false)
  await assert.rejects(requireFreePorts({ backend: value }), /已占用/)
  assert.equal(server.listening, true)
})

test('isolated POM changes only output, retains module basedir and standard target shape', t => {
  const root = temporary(t)
  mkdirSync(join(root, 'backend'))
  const source = '<project><build><plugins><plugin>unchanged</plugin></plugins></build></project>'
  writeFileSync(join(root, 'backend', 'pom.xml'), source)
  const pom = isolatedPom(root, 'backend', join(root, 'output & space'))
  const generated = readFileSync(pom.path, 'utf8')
  assert.match(generated, /output &amp; space\/backend\/target<\/directory>/)
  assert.equal(generated.replace(/\n    <directory>.*<\/directory>/, ''), source)
  assert.equal(readFileSync(join(root, 'backend', 'pom.xml'), 'utf8'), source)
  pom.cleanup()
  assert.equal(existsSync(pom.path), false)
})

test('isolated POM tolerates resource directories but still refuses a top-level build directory', t => {
  const root = temporary(t)
  mkdirSync(join(root, 'backend'))
  const withResource = '<project><build><resources><resource><directory>/shared/res</directory></resource></resources>'
    + '<plugins><plugin>unchanged</plugin></plugins></build></project>'
  writeFileSync(join(root, 'backend', 'pom.xml'), withResource)
  const pom = isolatedPom(root, 'backend', root)
  assert.match(readFileSync(pom.path, 'utf8'), /<directory>.*\/backend\/target<\/directory>/)
  pom.cleanup()

  const withBuildDirectory = '<project><build><directory>custom</directory><plugins/></build></project>'
  writeFileSync(join(root, 'backend', 'pom.xml'), withBuildDirectory)
  assert.throws(() => isolatedPom(root, 'backend', root), /需要显式审查/)
})
