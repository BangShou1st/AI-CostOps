import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  HC01_OWNER_LABEL, HC01_RESOURCE_LABEL, HC01_RUN_LABEL,
  decideRedisRemoval, labelsForRedis,
} from '../perf/hc01/ownership.mjs'
import { labelsFor as hc03LabelsFor } from '../perf/hc03/ownership.mjs'

const NAME = 'ai-costops-hc01-redis'
const OWNER = 'hc01-owner-test-aaaa'
const OTHER = 'hc01-owner-test-bbbb'

function labeled(owner, runId = 'run-1') {
  return labelsForRedis(owner, runId)
}

test('redis: a missing container is a removal no-op', () => {
  const decision = decideRedisRemoval({ name: NAME, expectedOwner: OWNER, labels: null })
  assert.equal(decision.allow, true)
  assert.equal(decision.noop, true)
})

test('redis: an unlabeled container is refused with an HC-01 manual removal command', () => {
  const decision = decideRedisRemoval({ name: NAME, expectedOwner: OWNER, labels: {} })
  assert.equal(decision.allow, false)
  assert.equal(decision.reason, 'UNLABELED_RESOURCE')
  assert.match(decision.message, /HC-01/)
  assert.match(decision.message, /Refusing to delete/)
  assert.match(decision.message, new RegExp(`docker rm -f ${NAME}`))
  assert.equal(decision.manualCommand, `docker rm -f ${NAME}`)
})

test('redis: a container owned by another run is refused', () => {
  const decision = decideRedisRemoval({ name: NAME, expectedOwner: OWNER, labels: labeled(OTHER) })
  assert.equal(decision.allow, false)
  assert.equal(decision.reason, 'OWNER_MISMATCH')
  assert.match(decision.message, /HC-01/)
})

test('redis: a container owned by this run may be removed', () => {
  const decision = decideRedisRemoval({ name: NAME, expectedOwner: OWNER, labels: labeled(OWNER) })
  assert.equal(decision.allow, true)
  assert.equal(decision.noop, undefined)
})

test('redis: hc03 owner labels do not authorize an hc01 container', () => {
  const decision = decideRedisRemoval({
    name: NAME, expectedOwner: OWNER, labels: hc03LabelsFor(OWNER, 'run-1', 'redis'),
  })
  assert.equal(decision.allow, false)
  assert.equal(decision.reason, 'UNLABELED_RESOURCE')
  assert.match(decision.message, /HC-01/)
})

test('redis: labels carry the hc01 owner, run and resource namespaces', () => {
  const labels = labelsForRedis(OWNER, 'run-9')
  assert.equal(labels[HC01_OWNER_LABEL], OWNER)
  assert.equal(labels[HC01_RUN_LABEL], 'run-9')
  assert.equal(labels[HC01_RESOURCE_LABEL], 'redis')
  assert.equal(labels['aicostops.hc01.owner'], OWNER)
})
