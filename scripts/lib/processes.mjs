import { spawn, execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, join } from 'node:path'

export const isWindows = process.platform === 'win32'

export function npmCommand(args) {
  let cli = process.env.npm_execpath
  if (!cli && isWindows) {
    const executable = execFileSync('where.exe', ['npm.cmd'], { encoding: 'utf8', windowsHide: true }).trim().split(/\r?\n/)[0]
    cli = join(dirname(executable), 'node_modules', 'npm', 'bin', 'npm-cli.js')
  }
  return cli && existsSync(cli) ? { command: process.execPath, args: [cli, ...args] } : { command: 'npm', args }
}

export function spawnCommand(command, args, options = {}) {
  const { group = false, ...spawnOptions } = options
  if (isWindows && (command.endsWith('.cmd') || command.endsWith('.bat'))) {
    // cmd /s /c requires outer quotes around the entire invocation and separate
    // quotes around every token. Reject expansion/quote characters, disable
    // delayed expansion, and bypass Node's second Windows quoting pass.
    if ([command, ...args].some(value => /[%"\r\n]/.test(value))) throw new Error('Windows batch 参数包含不支持的字符。')
    const invocation = `"${[command, ...args].map(value => `"${value}"`).join(' ')}"`
    // Keep an owned Node parent for batch process-tree cleanup and pipe forwarding.
    // Windows cleanup uses taskkill /T, so a detached console is unnecessary.
    const launcher = `const {spawn}=require('node:child_process');
      const child=spawn(process.argv[1],process.argv.slice(2),{stdio:'inherit',windowsHide:true,windowsVerbatimArguments:true});
      child.on('error',error=>{console.error(error.message);process.exitCode=1});
      child.on('exit',code=>{process.exitCode=code??1});`
    return spawn(process.execPath, ['-e', launcher, '--', process.env.ComSpec ?? 'cmd.exe', '/d', '/v:off', '/s', '/c', invocation],
      { ...spawnOptions, shell: false, windowsHide: true, detached: false })
  }
  // POSIX cleanup needs a process group. Windows taskkill /T already follows
  // descendants; detaching there creates a new console and can flash windows.
  const detached = group && !isWindows
  return spawn(command, args, { ...spawnOptions, shell: false, windowsHide: true, detached })
}

function outcome(child, label) {
  return new Promise(resolve => {
    child.once('error', error => resolve(new Error(`${label}: ${error.message}`)))
    child.once('close', (code, signal) => resolve(code === 0 ? null : new Error(`${label}: ${signal ?? `exit ${code}`}`)))
  })
}

export async function terminate(child) {
  if (!child.pid) return
  if (isWindows) {
    if (child.exitCode !== null || child.signalCode !== null) return
    await new Promise(resolve => {
      const killer = spawn('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true })
      killer.once('error', resolve)
      killer.once('close', resolve)
    })
  } else {
    try { process.kill(-child.pid, 'SIGTERM') } catch { return }
    await new Promise(resolve => setTimeout(resolve, 150))
    try { process.kill(-child.pid, 'SIGKILL') } catch { /* group already exited */ }
  }
}

export async function withSignals(work) {
  const controller = new AbortController()
  const stop = () => controller.abort()
  process.on('SIGINT', stop)
  process.on('SIGTERM', stop)
  try { await work(controller.signal); return controller.signal.aborted ? 130 : 0 }
  catch (error) { if (controller.signal.aborted) return 130; throw error }
  finally { process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop) }
}

export async function supervise(specs, { signal = new AbortController().signal, onStarted = async () => {}, allowExit = false } = {}) {
  signal.throwIfAborted()
  const children = []
  const readiness = new AbortController()
  let stop
  const stopped = new Promise(resolve => { stop = () => resolve({ stopped: true }) })
  signal.addEventListener('abort', stop, { once: true })
  try {
    const exits = []
    for (const spec of specs) {
      // Pipe explicitly: detached Windows wrappers can otherwise write to an
      // invisible console rather than the caller's redirected log file.
      const child = spawnCommand(spec.command, spec.args, { cwd: spec.cwd, env: spec.env ?? process.env, stdio: spec.stdio ?? ['inherit', 'pipe', 'pipe'], group: true })
      if (!spec.stdio) {
        child.stdout.pipe(process.stdout, { end: false })
        child.stderr.pipe(process.stderr, { end: false })
      }
      children.push(child)
      exits.push(outcome(child, spec.label ?? spec.command).then(error => ({ error: error ?? (allowExit ? null : new Error(`${spec.label ?? spec.command} 提前退出。`)) })))
    }
    const ready = Promise.resolve().then(() => onStarted(AbortSignal.any([signal, readiness.signal])))
      .then(() => stopped, error => ({ error }))
    const result = await Promise.race([...exits, stopped, ready])
    if (result.error) throw result.error
  } finally {
    readiness.abort()
    await Promise.all(children.map(terminate))
    signal.removeEventListener('abort', stop)
  }
}

export function run(spec, signal) { return supervise([spec], { signal, allowExit: true }) }

export async function capture(command, args, { signal, cwd, env = process.env, timeout = 15_000 } = {}) {
  signal?.throwIfAborted()
  const child = spawnCommand(command, args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'], group: true })
  let output = ''
  child.stdout.on('data', chunk => { output += chunk })
  child.stderr.on('data', chunk => { output += chunk })
  const completion = outcome(child, command)
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  const cancelled = AbortSignal.any([controller.signal, ...(signal ? [signal] : [])])
  let stop
  try {
    const interrupted = new Promise(resolve => {
      stop = () => resolve(new Error(`${command}: 检查取消或超时。`))
      cancelled.addEventListener('abort', stop, { once: true })
      if (cancelled.aborted) stop()
    })
    const error = await Promise.race([completion, interrupted])
    if (error) { error.output = output; throw error }
    return output
  } finally { clearTimeout(timer); cancelled.removeEventListener('abort', stop); await terminate(child) }
}
