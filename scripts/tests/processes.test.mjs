import test from 'node:test'
import assert from 'node:assert/strict'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { capture, supervise, withSignals, isWindows } from '../lib/processes.mjs'
import { temporary, waitForFile, alive } from './helpers.mjs'

function worker(root, name) {
  const path = join(root, `${name}.json`)
  const code = `const {spawn}=require('node:child_process'); const fs=require('node:fs');
    const grandchild=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
    fs.writeFileSync(${JSON.stringify(path)},JSON.stringify([process.pid,grandchild.pid])); setInterval(()=>{},1000);`
  return { path, spec: { command: process.execPath, args: ['-e', code], stdio: 'ignore', label: name } }
}

test('missing executable and nonzero status propagate; capture is bounded', async () => {
  await assert.rejects(capture('aicostops-does-not-exist', []), /ENOENT/)
  await assert.rejects(capture(process.execPath, ['-e', 'process.exit(17)']), /exit 17/)
  await assert.rejects(capture(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { timeout: 100 }), /超时/)
})

test('Windows batch preserves paths with spaces and reports wrapper exit code', { skip: !isWindows }, async t => {
  const root = temporary(t)
  const path = join(root, 'wrapper with space & 中文.cmd')
  writeFileSync(path, '@echo off\r\nexit /b 19\r\n')
  await assert.rejects(capture(path, []), /exit 19/)
})

test('Windows nested batch output and literal argument boundaries survive the launcher', { skip: !isWindows }, async t => {
  const root = temporary(t)
  const script = join(root, 'echo arguments.mjs')
  const wrapper = join(root, 'nested wrapper & 中文.cmd')
  writeFileSync(script, 'console.log(JSON.stringify(process.argv.slice(2)))\n')
  writeFileSync(wrapper, `@echo off\r\n"${process.execPath}" "${script}" %*\r\n`)
  const args = ['space value', 'literal & value', '$not-a-variable', 'semi;colon', '中文']
  const output = await capture(wrapper, args)
  assert.deepEqual(JSON.parse(output.trim()), args)
})

test('cancel shuts down owned child and grandchild processes and cancels readiness', async t => {
  const root = temporary(t)
  const workers = [worker(root, 'backend'), worker(root, 'gateway'), worker(root, 'frontend')]
  const controller = new AbortController()
  t.after(() => controller.abort())
  let pids
  let readinessCancelled = false
  await supervise(workers.map(item => item.spec), { signal: controller.signal, onStarted: async signal => {
    pids = (await Promise.all(workers.map(item => waitForFile(item.path, signal)))).flat()
    assert.equal(pids.every(alive), true)
    signal.addEventListener('abort', () => { readinessCancelled = true }, { once: true })
    controller.abort()
    if (!signal.aborted) await new Promise(resolve => signal.addEventListener('abort', resolve, { once: true }))
  } })
  assert.equal(readinessCancelled, true)
  assert.equal(pids.some(alive), false)
})

test('one failed process cleans up the other process tree', async t => {
  const root = temporary(t)
  const item = worker(root, 'sibling')
  let pids
  await assert.rejects(supervise([item.spec, { command: process.execPath, args: ['-e', 'setTimeout(()=>process.exit(7),600)'], stdio: 'ignore', label: 'failed' }], {
    onStarted: async signal => { pids = await waitForFile(item.path, signal) },
  }), /failed: exit 7/)
  assert.equal(pids.some(alive), false)
})

test('startup error cancels readiness and cleans up already-started processes', async t => {
  const root = temporary(t)
  const item = worker(root, 'started')
  await assert.rejects(supervise([item.spec, { command: 'aicostops-does-not-exist', args: [], stdio: 'ignore' }], {
    onStarted: signal => waitForFile(item.path, signal),
  }), /ENOENT/)
})

test('readiness failure cleans up live tree', async t => {
  const root = temporary(t)
  const item = worker(root, 'unhealthy')
  let pids
  await assert.rejects(supervise([item.spec], { onStarted: async signal => {
    pids = await waitForFile(item.path, signal)
    throw new Error('HTTP readiness failed')
  } }), /HTTP readiness failed/)
  assert.equal(pids.some(alive), false)
})

test('SIGINT uses the same cleanup path and produces cancellation exit status', async t => {
  const root = temporary(t)
  const item = worker(root, 'interrupted')
  let pids
  const status = await withSignals(signal => supervise([item.spec], { signal, onStarted: async readiness => {
    pids = await waitForFile(item.path, readiness)
    process.emit('SIGINT')
  } }))
  assert.equal(status, 130)
  assert.equal(pids.some(alive), false)
})
