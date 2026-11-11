/**
 * HC-03 resource ownership decisions.
 *
 * Deliberately pure: no Docker, no filesystem, no process APIs. `lib.mjs`
 * collects the observed facts and these functions decide whether the harness
 * may create, stop, kill or delete a resource. Every refusal carries a
 * human-readable message so an operator can act without guessing.
 *
 * Ownership is asserted, never assumed:
 * - containers carry `aicostops.hc03.owner` (and run/resource) labels;
 * - PID files carry the owner, PID, process start time and a command token;
 * - legacy numeric PID files and unlabeled containers are refused.
 */

export const OWNER_LABEL = 'aicostops.hc03.owner'
export const RUN_LABEL = 'aicostops.hc03.run'
export const RESOURCE_LABEL = 'aicostops.hc03.resource'
export const OWNER_FILE_NAME = 'run-owner.json'
export const PID_RECORD_VERSION = 2
export const START_TIME_TOLERANCE_MS = 15_000

export class OwnershipError extends Error {
  constructor(message, code = 'OWNERSHIP_MISMATCH') {
    super(message)
    this.name = 'OwnershipError'
    this.code = code
  }
}

export function newOwnerId(runNonce) {
  const suffix = Math.random().toString(36).slice(2, 10)
  return `hc03-owner-${runNonce}-${suffix}`
}

export function labelsFor(owner, runId, resource) {
  return {
    [OWNER_LABEL]: owner,
    [RUN_LABEL]: runId,
    [RESOURCE_LABEL]: resource,
  }
}

export function ownerOf(labels) {
  if (!labels || typeof labels !== 'object') return null
  const value = labels[OWNER_LABEL]
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function manualRemove(name) {
  return `docker rm -f ${name}`
}

/**
 * @param {object} input
 * @param {'create'|'attach'|'delete'} input.mode
 * @param {string} input.name
 * @param {string} input.expectedOwner
 * @param {null|{labels?: object}} input.existing
 * @returns {{allow: boolean, reason?: string, message?: string, manualCommand?: string}}
 */
export function decideContainer({ mode, name, expectedOwner, existing }) {
  const present = Boolean(existing)
  const owner = present ? ownerOf(existing.labels) : null

  if (mode === 'create') {
    if (!present) return { allow: true }
    if (owner && owner === expectedOwner) {
      return {
        allow: false,
        reason: 'OWNED_LEFTOVER',
        message: `HC-03 container ${name} is left over from owner ${owner}. `
          + 'Run `node scripts/perf/hc03/runner.mjs --cleanup` to remove it; the harness '
          + 'will not delete it implicitly.',
      }
    }
    return {
      allow: false,
      reason: 'FOREIGN_RESOURCE',
      message: `HC-03 refuses to replace container ${name}: it has no HC-03 owner label `
        + 'or belongs to a different owner. Confirm it is yours, then remove it manually: '
        + `\`${manualRemove(name)}\`.`,
      manualCommand: manualRemove(name),
    }
  }

  if (mode === 'attach') {
    if (!present) {
      return {
        allow: false,
        reason: 'MISSING_RESOURCE',
        message: `--attach expected HC-03 container ${name}, but it does not exist.`,
      }
    }
    if (!owner || owner !== expectedOwner) {
      return {
        allow: false,
        reason: 'OWNER_MISMATCH',
        message: `--attach refuses container ${name}: expected owner ${expectedOwner}, `
          + `found ${owner ?? 'none'}.`,
      }
    }
    return { allow: true }
  }

  if (mode === 'operate') {
    if (!present) {
      return {
        allow: false,
        reason: 'MISSING_RESOURCE',
        message: `Refusing to control container ${name}: it does not exist.`,
      }
    }
    if (!owner || owner !== expectedOwner) {
      return {
        allow: false,
        reason: owner ? 'OWNER_MISMATCH' : 'UNLABELED_RESOURCE',
        message: `Refusing to control container ${name}: expected owner ${expectedOwner}, `
          + `found ${owner ?? 'no owner label'}.`,
      }
    }
    return { allow: true }
  }

  if (mode === 'delete') {
    if (!present) return { allow: true, noop: true }
    if (owner && owner === expectedOwner) return { allow: true }
    return {
      allow: false,
      reason: owner ? 'OWNER_MISMATCH' : 'UNLABELED_RESOURCE',
      message: `Refusing to delete container ${name}: expected owner ${expectedOwner}, `
        + `found ${owner ?? 'no owner label'}. Inspect it with \`docker inspect ${name}\`; `
        + `if it is safe to remove, run \`${manualRemove(name)}\` explicitly.`,
      manualCommand: manualRemove(name),
    }
  }

  return {
    allow: false,
    reason: 'UNKNOWN_MODE',
    message: `Unknown ownership mode ${mode} for ${name}.`,
  }
}

/**
 * @param {object} input
 * @param {'stop'|'kill'} input.mode
 * @param {string} input.name
 * @param {string} input.expectedOwner
 * @param {null|object} input.record parsed PID record (or null)
 * @param {null|{alive: boolean, commandLine?: string, startedAtEpochMs?: number}} input.actual
 */
export function decideProcess({ mode, name, expectedOwner, record, actual }) {
  if (!record) return { allow: true, noop: true }
  if (record.legacy) {
    return {
      allow: false,
      reason: 'LEGACY_PID_FILE',
      message: `Refusing to ${mode} ${name}: PID file ${record.pid} uses the legacy bare-number `
        + 'format without an owner/start-time identity. Inspect the process manually, then '
        + 'delete the PID file if it is safe.',
    }
  }
  if (record.corrupt) {
    return {
      allow: false,
      reason: 'CORRUPT_PID_FILE',
      message: `Refusing to ${mode} ${name}: its PID record is unreadable or incomplete. `
        + 'Inspect it manually before removing anything.',
    }
  }
  if (!record.owner || record.owner !== expectedOwner) {
    return {
      allow: false,
      reason: 'OWNER_MISMATCH',
      message: `Refusing to ${mode} ${name}: PID record owner ${record.owner ?? 'none'} does not `
        + `match this harness owner ${expectedOwner}.`,
    }
  }
  if (!actual || actual.alive === false) {
    return { allow: true, noop: true, removeRecord: true }
  }
  const commandLine = String(actual.commandLine ?? '')
  if (!record.token || !commandLine.includes(record.token)) {
    return {
      allow: false,
      reason: 'COMMAND_MISMATCH',
      message: `Refusing to ${mode} ${name} (pid ${record.pid}): the running command line does `
        + `not contain the recorded token ${JSON.stringify(record.token)}. PID reuse or a foreign `
        + 'process is possible; not signalling it.',
    }
  }
  const started = Number(actual.startedAtEpochMs)
  const expectedStart = Number(record.startedAtEpochMs)
  if (!Number.isFinite(started) || !Number.isFinite(expectedStart)
      || Math.abs(started - expectedStart) > START_TIME_TOLERANCE_MS) {
    return {
      allow: false,
      reason: 'START_TIME_MISMATCH',
      message: `Refusing to ${mode} ${name} (pid ${record.pid}): process start time `
        + `${Number.isFinite(started) ? new Date(started).toISOString() : 'unknown'} does not `
        + `match the recorded ${Number.isFinite(expectedStart) ? new Date(expectedStart).toISOString() : 'unknown'}. `
        + 'PID reuse is possible; not signalling it.',
    }
  }
  return { allow: true }
}

export function parsePidRecord(rawText) {
  const text = String(rawText ?? '').trim()
  if (!text) return { corrupt: true }
  if (/^\d+$/.test(text)) {
    return { legacy: true, pid: Number(text) }
  }
  let parsed
  try {
    parsed = JSON.parse(text)
  } catch {
    return { corrupt: true }
  }
  if (!parsed || parsed.version !== PID_RECORD_VERSION) return { corrupt: true }
  const pid = Number(parsed.pid)
  if (!Number.isFinite(pid) || pid <= 0) return { corrupt: true }
  if (typeof parsed.owner !== 'string' || !parsed.owner) return { corrupt: true }
  if (typeof parsed.token !== 'string' || !parsed.token) return { corrupt: true }
  const startedAtEpochMs = Number(parsed.startedAtEpochMs)
  if (!Number.isFinite(startedAtEpochMs)) return { corrupt: true }
  return {
    version: parsed.version,
    name: parsed.name ?? null,
    owner: parsed.owner,
    pid,
    startedAtEpochMs,
    token: parsed.token,
    commandLine: parsed.commandLine ?? null,
  }
}

export function buildPidRecord({ owner, name, pid, token, commandLine, startedAtEpochMs }) {
  return {
    version: PID_RECORD_VERSION,
    owner,
    name,
    pid,
    token,
    commandLine,
    startedAtEpochMs,
  }
}

/** The token is the stable, human-recognisable part of the child command. */
export function commandToken(command, args) {
  const argList = Array.isArray(args) ? args.map(String) : []
  const script = argList.find(arg => /\.(jar|py|mjs)$/i.test(arg))
  if (script) {
    const base = script.split(/[\\/]/).pop()
    if (base) return base
  }
  return command.split(/[\\/]/).pop() || command
}
