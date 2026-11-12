// CI helper: enumerate Maven Failsafe integration tests and select one shard.
//
// The previous inline `node -e` snippet in ci.yml silently produced an empty
// `-Dit.test=` value (with failIfNoSpecifiedTests=false), so a renamed or
// added `*IT.java` class could make the required integration job pass while
// running zero tests. This module makes the selection explicit and exits
// non-zero on an empty enumeration or an empty shard.
//
// Failsafe includes `**/*IntegrationTest.java`; keep this suffix in sync with
// backend/pom.xml and gateway/pom.xml.

import { readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

export const DEFAULT_TEST_ROOT = 'src/test/java'
export const INTEGRATION_TEST_SUFFIX = 'IntegrationTest.java'

/** Recursively collect integration-test class names (no `.java`), sorted. */
export function collectIntegrationTestNames(testRoot) {
  const names = []
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name)
      if (entry.isDirectory()) walk(path)
      else if (entry.isFile() && entry.name.endsWith(INTEGRATION_TEST_SUFFIX)) {
        names.push(entry.name.slice(0, -'.java'.length))
      }
    }
  }
  walk(testRoot)
  return names.sort()
}

/**
 * Deterministic round-robin shard assignment, identical to the old inline
 * filter (`index % shards === shard`) so the existing 198-class test set is
 * partitioned exactly as before.
 */
export function selectShard(names, shard, shards) {
  if (!Number.isInteger(shard) || shard < 0) {
    throw new Error(`shard must be a non-negative integer, got ${shard}`)
  }
  if (!Number.isInteger(shards) || shards < 1) {
    throw new Error(`shards must be a positive integer, got ${shards}`)
  }
  if (shard >= shards) {
    throw new Error(`shard ${shard} is outside 0..${shards - 1}`)
  }
  return names.filter((_, index) => index % shards === shard)
}

/**
 * Full selection used by CI. An empty test tree or an empty shard is an error:
 * the integration job must never report success after running zero tests.
 */
export function selectIntegrationTests({
  testRoot = DEFAULT_TEST_ROOT,
  shard = 0,
  shards = 1,
} = {}) {
  const all = collectIntegrationTestNames(resolve(testRoot))
  if (all.length === 0) {
    throw new Error(`no *${INTEGRATION_TEST_SUFFIX} files found under ${resolve(testRoot)}`)
  }
  const selected = selectShard(all, shard, shards)
  if (selected.length === 0) {
    throw new Error(
      `shard ${shard}/${shards} selected no tests out of ${all.length} `
        + `(check the shard count and the Failsafe include pattern)`,
    )
  }
  return selected
}

function parseInteger(value, flag) {
  if (!/^\d+$/.test(value)) throw new Error(`${flag} must be a non-negative integer`)
  return Number(value)
}

export function parseArguments(argv) {
  const options = { testRoot: DEFAULT_TEST_ROOT, shard: 0, shards: 1 }
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index]
    const value = argv[index + 1]
    if (value === undefined) throw new Error(`${flag} requires a value`)
    switch (flag) {
      case '--test-root':
        options.testRoot = value
        break
      case '--shard':
        options.shard = parseInteger(value, flag)
        break
      case '--shards':
        options.shards = parseInteger(value, flag)
        break
      default:
        throw new Error(`unknown argument ${flag}`)
    }
  }
  return options
}

function main() {
  try {
    const selected = selectIntegrationTests(parseArguments(process.argv.slice(2)))
    console.log(selected.join(','))
  } catch (error) {
    console.error(`[select-integration-tests] ${error.message}`)
    process.exitCode = 1
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
}
