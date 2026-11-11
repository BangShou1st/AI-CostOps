import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  OWNER_LABEL, buildPidRecord, commandToken, decideContainer, decideProcess, labelsFor, newOwnerId,
  ownerOf, parsePidRecord,
} from '../perf/hc03/ownership.mjs'

const OWNER = 'hc03-owner-test-aaaa'
const OTHER = 'hc03-owner-test-bbbb'
const NAME = 'ai-costops-hc03-mysql'

function labeled(owner, resource = 'mysql') {
  return { labels: labelsFor(owner, 'run-1', resource) }
}

test('create: a missing fixed-name container is allowed', () => {
  const decision = decideContainer({ mode: 'create', name: NAME, expectedOwner: OWNER, existing: null })
  assert.equal(decision.allow, true)
})

test('create: a container from the recorded owner is refused with an explicit --cleanup instruction', () => {
  const decision = decideContainer({
    mode: 'create', name: NAME, expectedOwner: OWNER, existing: labeled(OWNER),
  })
  assert.equal(decision.allow, false)
  assert.equal(decision.reason, 'OWNED_LEFTOVER')
  assert.match(decision.message, /--cleanup/)
  assert.doesNotMatch(decision.message, /docker rm -f/) // never instruct implicit deletion
})

test('create: an unlabeled container is refused and requires manual removal', () => {
  const decision = decideContainer({
    mode: 'create', name: NAME, expectedOwner: OWNER, existing: { labels: {} },
  })
  assert.equal(decision.allow, false)
  assert.equal(decision.reason, 'FOREIGN_RESOURCE')
  assert.match(decision.message, /no HC-03 owner label/)
  assert.equal(decision.manualCommand, `docker rm -f ${NAME}`)
})

test('create: a container owned by someone else is refused', () => {
  const decision = decideContainer({
    mode: 'create', name: NAME, expectedOwner: OWNER, existing: labeled(OTHER),
  })
  assert.equal(decision.allow, false)
  assert.equal(decision.reason, 'FOREIGN_RESOURCE')
})

test('attach: missing or foreign resources are refused, matching owner is allowed', () => {
  assert.equal(decideContainer({ mode: 'attach', name: NAME, expectedOwner: OWNER, existing: null }).allow, false)
  assert.equal(decideContainer({
    mode: 'attach', name: NAME, expectedOwner: OWNER, existing: labeled(OTHER),
  }).allow, false)
  assert.equal(decideContainer({
    mode: 'attach', name: NAME, expectedOwner: OWNER, existing: labeled(OWNER),
  }).allow, true)
})

test('operate: stop/pause/start refuse missing, unlabeled and foreign containers', () => {
  assert.equal(decideContainer({ mode: 'operate', name: NAME, expectedOwner: OWNER, existing: null }).allow, false)
  assert.equal(decideContainer({
    mode: 'operate', name: NAME, expectedOwner: OWNER, existing: { labels: {} },
  }).allow, false)
  assert.equal(decideContainer({
    mode: 'operate', name: NAME, expectedOwner: OWNER, existing: labeled(OTHER),
  }).allow, false)
  assert.equal(decideContainer({
    mode: 'operate', name: NAME, expectedOwner: OWNER, existing: labeled(OWNER),
  }).allow, true)
})

test('delete: only an exactly matching owner may be removed; foreign resources are refused', () => {
  const missing = decideContainer({ mode: 'delete', name: NAME, expectedOwner: OWNER, existing: null })
  assert.equal(missing.allow, true)
  assert.equal(missing.noop, true)

  const unlabeled = decideContainer({
    mode: 'delete', name: NAME, expectedOwner: OWNER, existing: { labels: {} },
  })
  assert.equal(unlabeled.allow, false)
  assert.equal(unlabeled.reason, 'UNLABELED_RESOURCE')
  assert.match(unlabeled.message, /Refusing to delete/)

  const foreign = decideContainer({
    mode: 'delete', name: NAME, expectedOwner: OWNER, existing: labeled(OTHER),
  })
  assert.equal(foreign.allow, false)
  assert.equal(foreign.reason, 'OWNER_MISMATCH')

  const owned = decideContainer({
    mode: 'delete', name: NAME, expectedOwner: OWNER, existing: labeled(OWNER),
  })
  assert.equal(owned.allow, true)
})

test('process: a missing record is a no-op, a legacy bare PID is refused', () => {
  assert.equal(decideProcess({ mode: 'kill', name: 'backend', expectedOwner: OWNER, record: null, actual: null }).noop, true)

  const legacy = decideProcess({
    mode: 'kill', name: 'backend', expectedOwner: OWNER,
    record: { legacy: true, pid: 1234 },
    actual: { alive: true, commandLine: 'java -jar backend.jar', startedAtEpochMs: Date.now() },
  })
  assert.equal(legacy.allow, false)
  assert.equal(legacy.reason, 'LEGACY_PID_FILE')
})

test('process: owner, command token and start time must all match', () => {
  const now = Date.now()
  const record = buildPidRecord({
    owner: OWNER, name: 'backend', pid: 4242, token: 'backend-1.1.0.jar',
    commandLine: 'java -jar backend-1.1.0.jar', startedAtEpochMs: now,
  })

  assert.equal(decideProcess({
    mode: 'kill', name: 'backend', expectedOwner: OWNER, record,
    actual: { alive: true, commandLine: 'java -jar backend-1.1.0.jar', startedAtEpochMs: now + 500 },
  }).allow, true)

  const foreignOwner = decideProcess({
    mode: 'kill', name: 'backend', expectedOwner: OTHER, record,
    actual: { alive: true, commandLine: 'java -jar backend-1.1.0.jar', startedAtEpochMs: now },
  })
  assert.equal(foreignOwner.allow, false)
  assert.equal(foreignOwner.reason, 'OWNER_MISMATCH')

  const reusedPid = decideProcess({
    mode: 'kill', name: 'backend', expectedOwner: OWNER, record,
    actual: { alive: true, commandLine: 'node unrelated.js', startedAtEpochMs: now },
  })
  assert.equal(reusedPid.allow, false)
  assert.equal(reusedPid.reason, 'COMMAND_MISMATCH')

  const oldProcess = decideProcess({
    mode: 'kill', name: 'backend', expectedOwner: OWNER, record,
    actual: { alive: true, commandLine: 'java -jar backend-1.1.0.jar', startedAtEpochMs: now - 120_000 },
  })
  assert.equal(oldProcess.allow, false)
  assert.equal(oldProcess.reason, 'START_TIME_MISMATCH')
})

test('process: a dead verified process allows record cleanup but no signal', () => {
  const record = buildPidRecord({
    owner: OWNER, name: 'backend', pid: 7, token: 'backend-1.1.0.jar',
    commandLine: 'java -jar backend-1.1.0.jar', startedAtEpochMs: Date.now(),
  })
  const decision = decideProcess({
    mode: 'stop', name: 'backend', expectedOwner: OWNER, record, actual: { alive: false },
  })
  assert.equal(decision.allow, true)
  assert.equal(decision.noop, true)
  assert.equal(decision.removeRecord, true)
})

test('parsePidRecord distinguishes legacy, corrupt and valid records', () => {
  assert.equal(parsePidRecord('12345').legacy, true)
  assert.equal(parsePidRecord('{"version":2}').corrupt, true)
  assert.equal(parsePidRecord('not json').corrupt, true)
  const record = buildPidRecord({
    owner: OWNER, name: 'backend', pid: 12, token: 'backend.jar',
    commandLine: 'java -jar backend.jar', startedAtEpochMs: 1700000000000,
  })
  const parsed = parsePidRecord(JSON.stringify(record))
  assert.equal(parsed.owner, OWNER)
  assert.equal(parsed.pid, 12)
  assert.equal(parsed.token, 'backend.jar')
})

test('commandToken picks the stable script name for diagnostics', () => {
  assert.equal(commandToken('java', ['-jar', 'E:/x/backend-1.1.0.jar']), 'backend-1.1.0.jar')
  assert.equal(commandToken('python', ['scripts/perf/hc01/mock_provider.py']), 'mock_provider.py')
  assert.equal(commandToken('C:/Windows/java.exe', ['-version']), 'java.exe')
})

test('owner labels round-trip and unknown labels are treated as unowned', () => {
  const labels = labelsFor(OWNER, 'run-9', 'mysql')
  assert.equal(labels[OWNER_LABEL], OWNER)
  assert.equal(ownerOf(labels), OWNER)
  assert.equal(ownerOf({}), null)
  assert.equal(ownerOf(null), null)
  assert.notEqual(newOwnerId('run'), newOwnerId('run'))
})
