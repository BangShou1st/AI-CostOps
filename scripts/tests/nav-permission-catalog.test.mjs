import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// src/app/layout/appNavigation.tsx declares one readPermission per nav entry.
// Every such code must come from the permission table seeded by Flyway
// migrations (the data behind GET /permissions); a code that exists only in
// the frontend silently hides its entry (or worse, admits nobody).
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const MIGRATION_DIR = join(REPO_ROOT, 'backend', 'src', 'main', 'resources', 'db', 'migration')
const NAV_SOURCE = join(REPO_ROOT, 'frontend', 'src', 'app', 'layout', 'appNavigation.tsx')

const INSERT_STATEMENT = /INSERT\s+INTO\s+`?(\w+)`?\s*(?:\([^)]*\))?\s*VALUES([\s\S]*?);/gi
const TUPLE_FIRST_VALUE = /\(\s*'([^']*)'\s*,/g

function navigationPermissionCodes() {
  return [...readFileSync(NAV_SOURCE, 'utf8').matchAll(/readPermission:\s*'([^']*)'/g)].map((match) => match[1])
}

function migrationPermissionCodes() {
  const codes = new Set()
  for (const file of readdirSync(MIGRATION_DIR)) {
    if (!file.endsWith('.sql')) continue
    const sql = readFileSync(join(MIGRATION_DIR, file), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/--[^\n]*/g, '')
    for (const statement of sql.matchAll(INSERT_STATEMENT)) {
      if (statement[1].toLowerCase() !== 'permission') continue
      for (const tuple of statement[2].matchAll(TUPLE_FIRST_VALUE)) codes.add(tuple[1])
    }
  }
  return codes
}

test('every navigation read permission exists in the migration permission catalogue', () => {
  const navCodes = navigationPermissionCodes()
  assert.ok(navCodes.length > 0, 'no navigation readPermission values were found')
  const catalogue = migrationPermissionCodes()
  assert.ok(catalogue.size > 0, 'no permission codes were parsed from the migration directory')
  const missing = navCodes.filter((code) => !catalogue.has(code))
  assert.deepEqual(missing, [],
    `navigation readPermission codes missing from Flyway permission seeds: ${missing.join(', ')}`)
})
