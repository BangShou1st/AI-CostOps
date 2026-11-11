import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { temporary } from './helpers.mjs'
import {
  collectIntegrationTestNames,
  selectIntegrationTests,
  selectShard,
} from '../ci/select-integration-tests.mjs'

const SCRIPT = fileURLToPath(new URL('../ci/select-integration-tests.mjs', import.meta.url))

function fixture(t, files) {
  const root = temporary(t)
  for (const file of files) {
    const path = join(root, file)
    mkdirSync(dirname(path), { recursive: true })
    writeFileSync(path, 'class Placeholder {}\n')
  }
  return root
}

function runCli(args) {
  return spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' })
}

test('collects every *IntegrationTest.java recursively and ignores other test names', t => {
  const root = fixture(t, [
    'com/example/FooIntegrationTest.java',
    'com/example/nested/BarIntegrationTest.java',
    'com/example/UnitTest.java',
    'com/example/BazIT.java',
    'com/example/IntegrationTestHelper.java',
  ])

  assert.deepEqual(collectIntegrationTestNames(root), [
    'BarIntegrationTest',
    'FooIntegrationTest',
  ])
})

test('an empty test tree fails instead of selecting zero tests', t => {
  const root = fixture(t, [])

  assert.throws(
    () => selectIntegrationTests({ testRoot: root, shard: 0, shards: 1 }),
    /no \*IntegrationTest\.java files found/,
  )

  const result = runCli(['--test-root', root])
  assert.equal(result.status, 1)
  assert.match(result.stderr, /no \*IntegrationTest\.java files found/)
  assert.equal(result.stdout, '')
})

test('an empty shard fails instead of letting the shard job pass with zero tests', t => {
  const root = fixture(t, ['com/example/OnlyIntegrationTest.java'])

  assert.throws(
    () => selectIntegrationTests({ testRoot: root, shard: 1, shards: 2 }),
    /selected no tests out of 1/,
  )

  const result = runCli(['--test-root', root, '--shard', '1', '--shards', '2'])
  assert.equal(result.status, 1)
  assert.match(result.stderr, /selected no tests out of 1/)
})

test('shard parity keeps the legacy round-robin partition unchanged', () => {
  const names = [
    'AlphaIntegrationTest',
    'BravoIntegrationTest',
    'CharlieIntegrationTest',
    'DeltaIntegrationTest',
    'EchoIntegrationTest',
  ]

  assert.deepEqual(selectShard(names, 0, 2), [
    'AlphaIntegrationTest',
    'CharlieIntegrationTest',
    'EchoIntegrationTest',
  ])
  assert.deepEqual(selectShard(names, 1, 2), [
    'BravoIntegrationTest',
    'DeltaIntegrationTest',
  ])
  assert.deepEqual(
    [...selectShard(names, 0, 2), ...selectShard(names, 1, 2)].sort(),
    names,
  )
})

test('invalid shard arguments are rejected', () => {
  assert.throws(() => selectShard([], -1, 2), /non-negative integer/)
  assert.throws(() => selectShard([], 0, 0), /positive integer/)
  assert.throws(() => selectShard([], 2, 2), /outside 0\.\.1/)
})

test('CLI prints the comma-separated selection for the requested shard', t => {
  const root = fixture(t, [
    'com/example/AlphaIntegrationTest.java',
    'com/example/BravoIntegrationTest.java',
    'com/example/CharlieIntegrationTest.java',
  ])

  const result = runCli(['--test-root', root, '--shard', '1', '--shards', '2'])

  assert.equal(result.status, 0)
  assert.equal(result.stdout.trim(), 'BravoIntegrationTest')
})
