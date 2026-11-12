/**
 * HC-01 Redis container ownership decisions.
 *
 * Deliberately pure: no Docker, no filesystem, no process APIs. HC-01 reuses
 * the HC-03 container decision so an unlabeled or foreign container is never
 * deleted implicitly. Only labels in the HC-01 namespace authorize removal;
 * HC-03 labels (and any other namespace) are treated as no owner at all.
 */

import { decideContainer } from '../hc03/ownership.mjs'

export const HC01_OWNER_LABEL = 'aicostops.hc01.owner'
export const HC01_RUN_LABEL = 'aicostops.hc01.run'
export const HC01_RESOURCE_LABEL = 'aicostops.hc01.resource'
export const HC01_HARNESS = 'HC-01'
export const HC01_REDIS_RESOURCE = 'redis'

export function labelsForRedis(owner, runId) {
  return {
    [HC01_OWNER_LABEL]: owner,
    [HC01_RUN_LABEL]: runId,
    [HC01_RESOURCE_LABEL]: HC01_REDIS_RESOURCE,
  }
}

/**
 * @param {object} input
 * @param {string} input.name
 * @param {string} input.expectedOwner
 * @param {null|object} input.labels observed container labels (null when absent)
 */
export function decideRedisRemoval({ name, expectedOwner, labels }) {
  return decideContainer({
    mode: 'delete',
    name,
    expectedOwner,
    existing: labels ? { labels } : null,
    labelKey: HC01_OWNER_LABEL,
    harness: HC01_HARNESS,
  })
}
