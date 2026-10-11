// Exercises the real CLI and process manager with fake HTTP apps, not real
// database/Provider traffic. Requires only a readable Docker engine.
import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:net'
import { mkdirSync, writeFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { spawnCommand, terminate, capture, isWindows } from '../lib/processes.mjs'
import { available } from '../lib/workspace.mjs'
import { projectFixture, waitForFile, alive } from './helpers.mjs'

test('real dev CLI starts three HTTP apps, detects readiness and stops all owned process trees', { timeout: 60_000 }, async t => {
  let child
  t.after(() => child && terminate(child))
  await capture('docker', ['info', '--format', '{{.ServerVersion}}'])
  const root = projectFixture(t)
  const env = { ...process.env }
  const sockets = []
  const ports = {}
  for (const name of ['backend', 'gateway', 'frontend']) {
    const socket = createServer()
    await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve))
    sockets.push(socket)
    ports[name] = socket.address().port
    env[`AICOSTOPS_${name.toUpperCase()}_PORT`] = String(ports[name])
  }
  await Promise.all(sockets.map(socket => new Promise(resolve => socket.close(resolve))))
  const serverCode = `import {createServer} from 'node:http'; import {spawn} from 'node:child_process';
    import {writeFileSync} from 'node:fs'; import {basename,join} from 'node:path';
    const name=basename(process.cwd()); const port=Number(process.env['AICOSTOPS_'+name.toUpperCase()+'_PORT']);
    const grandchild=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
    const server=createServer((req,res)=>{res.setHeader('Content-Type','application/json');res.end('{"status":"UP"}')});
    server.listen(port,'127.0.0.1',()=>writeFileSync(join(process.cwd(),'started.json'),JSON.stringify({
      pids:[process.pid,grandchild.pid],args:process.argv.slice(2),profile:process.env.SPRING_PROFILES_ACTIVE,port})));`
  for (const name of Object.keys(ports)) {
    mkdirSync(join(root, name))
    writeFileSync(join(root, name, 'fixture-server.mjs'), serverCode)
    if (name === 'frontend') {
      mkdirSync(join(root, name, 'node_modules'))
      writeFileSync(join(root, name, 'package.json'), JSON.stringify({ scripts: { dev: 'node fixture-server.mjs' } }))
    } else {
      writeFileSync(join(root, name, 'pom.xml'), '<project><build><plugins/></build></project>')
      const wrapper = isWindows ? `@echo off\r\n"${process.execPath}" "%~dp0fixture-server.mjs" %*\r\n`
        : `#!/bin/sh\nexec '${process.execPath}' "$(dirname "$0")/fixture-server.mjs" "$@"\n`
      writeFileSync(join(root, name, isWindows ? 'mvnw.cmd' : 'mvnw'), wrapper, { mode: 0o755 })
    }
  }
  // IPC triggers Node's real SIGINT listener on Windows, where kill(SIGINT)
  // forcibly terminates a process instead of delivering a console event.
  writeFileSync(join(root, 'entry.mjs'), `process.on('message',()=>process.emit('SIGINT'));
    import('./scripts/tasks.mjs').then(()=>{if(process.connected)process.disconnect()});`)
  child = spawnCommand(process.execPath, [join(root, 'entry.mjs'), 'dev'], { cwd: root, env,
    stdio: ['ignore', 'pipe', 'pipe', 'ipc'], group: true })
  let output = ''
  const closed = new Promise(resolve => child.once('close', code => resolve(code)))
  await new Promise((resolve, reject) => {
    child.stdout.on('data', chunk => { output += chunk; if (output.includes('三端已就绪')) resolve() })
    child.stderr.on('data', chunk => { output += chunk })
    child.once('error', reject)
    child.once('exit', code => reject(new Error(`CLI exited ${code} before readiness: ${output}`)))
  })
  const pids = []
  for (const name of Object.keys(ports)) {
    const info = await waitForFile(join(root, name, 'started.json'))
    assert.equal(info.profile, 'local')
    assert.equal(info.port, ports[name])
    assert.equal(info.pids.every(alive), true)
    pids.push(...info.pids)
    if (name !== 'frontend') {
      assert.ok(info.args.includes('-Dspring-boot.run.profiles=local'))
      assert.ok(info.args.includes(`-Dspring-boot.run.arguments=--server.port=${ports[name]}`))
    } else {
      assert.ok(info.args.includes('--strictPort'))
      assert.equal(info.args[info.args.indexOf('--port') + 1], String(ports.frontend))
    }
  }
  child.send('stop')
  assert.equal(await closed, 130)
  assert.equal(pids.some(alive), false)
  for (const value of Object.values(ports)) assert.equal(await available(value), true)
  for (const name of ['backend', 'gateway']) assert.equal(readdirSync(join(root, name)).some(file => file.startsWith('.task-build-')), false)
})
