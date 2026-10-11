import { mkdtempSync, rmSync, existsSync, readFileSync, cpSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve, relative, isAbsolute, basename, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

export function writeArtifact(name, content) {
  const directory = process.env.AICOSTOPS_TASK_TEST_ARTIFACT_DIR
  if (!directory) return
  if (!/^[a-z0-9-]+\.log$/.test(name)) throw new Error('Unsafe test artifact name')
  mkdirSync(resolve(directory), { recursive: true })
  writeFileSync(join(resolve(directory), name), content)
}

export function temporary(t) {
  const directory = mkdtempSync(join(tmpdir(), 'aicostops-tasks-test-'))
  t.after(() => {
    const target = resolve(directory)
    const inside = relative(resolve(tmpdir()), target)
    if (isAbsolute(inside) || inside.startsWith('..') || !basename(target).startsWith('aicostops-tasks-test-')) throw new Error('Unsafe test cleanup path')
    rmSync(target, { recursive: true, force: true })
  })
  return directory
}

export async function waitForFile(path, signal) {
  const deadline = Date.now() + 5000
  while (!existsSync(path)) {
    signal?.throwIfAborted()
    if (Date.now() > deadline) throw new Error('Fixture process did not start')
    await new Promise(resolve => setTimeout(resolve, 20))
  }
  return JSON.parse(readFileSync(path, 'utf8'))
}

export function alive(pid) {
  try { process.kill(pid, 0); return true } catch { return false }
}

export function projectFixture(t) {
  const source = dirname(dirname(dirname(fileURLToPath(import.meta.url))))
  const root = join(temporary(t), 'workspace with space & 中文')
  mkdirSync(join(root, 'scripts', 'lib'), { recursive: true })
  mkdirSync(join(root, 'scripts', 'dev'))
  cpSync(join(source, 'scripts', 'tasks.mjs'), join(root, 'scripts', 'tasks.mjs'))
  for (const name of ['processes.mjs', 'workspace.mjs']) cpSync(join(source, 'scripts', 'lib', name), join(root, 'scripts', 'lib', name))
  for (const name of ['start-infra', 'stop-infra', 'status']) {
    for (const extension of ['ps1', 'sh']) cpSync(join(source, 'scripts', 'dev', `${name}.${extension}`), join(root, 'scripts', 'dev', `${name}.${extension}`))
  }
  return root
}
