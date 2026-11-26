#!/usr/bin/env node
/**
 * HC-04 ABAB interleaved benchmark orchestrator.
 *
 * Executes baseline (hc04-baseline) and optimized (current workspace) in strict
 * A1 -> B1 -> A2 -> B2 -> A3 -> B3 order with full DB/Redis/process reset
 * between runs, enforcing:
 *   1. Zero unexpected HTTP 502s (any 502 fails the run immediately).
 *   2. Autocommit drift <= 30% (P50 and mean) against the preceding valid run;
 *      exceeded attempts are recorded in voidedRuns and retried after settling.
 *   3. S6 (redis_paused_30s_c50) & S7 (expiry_probe) in pair 1 (A1, B1), and
 *      S1, S2, S3, S8 across all 3 pairs (3 interleaved rounds each).
 */
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { isolatedPom } from '../../lib/workspace.mjs'
import { isWindows } from '../../lib/processes.mjs'

const ROOT = resolve(fileURLToPath(new URL('../../..', import.meta.url)))

const argv = process.argv.slice(2)
const flags = new Set(argv.filter(v => v.startsWith('--') && !v.includes('=')))
const options = Object.fromEntries(
  argv.filter(v => v.startsWith('--') && v.includes('='))
    .map(v => {
      const idx = v.indexOf('=')
      return [v.slice(2, idx), v.slice(idx + 1)]
    }),
)

const PAIRS = Number(options.pairs ?? 3)
const WARMUP_SEC = Number(options.warmup ?? 8)
const MEASURE_SEC = Number(options.measure ?? 20)
const MAX_AUTOCOMMIT_DRIFT = Number(options['max-autocommit-drift'] ?? 0.30)
const SETTLE_WAIT_SEC = Number(options['settle-wait-sec'] ?? 25)
const MAX_AUTOCOMMIT_RETRIES = Number(options['max-autocommit-retries'] ?? 6)
const FORCE_BUILD = flags.has('--build')
const SKIP_BUILD = flags.has('--skip-build')
const RESUME = flags.has('--resume')
const BASELINE_REF = options['baseline-ref'] ?? 'hc04-baseline'

const WORK_ROOT = join(ROOT, '.local-dev', 'hc04', 'abab')
const BUILD_ROOT = join(ROOT, '.local-dev', 'builds', 'hc04-abab')
const MYSQL_CONTAINER = 'ai-costops-hc04-mysql'
const MINIO_CONTAINER = 'ai-costops-hc04-minio'

function log(msg) {
  console.log(`[HC-04-ABAB ${new Date().toISOString().slice(11, 19)}] ${msg}`)
}

function inspectContainerEnv(name) {
  const result = spawnSync('docker', ['inspect', '--format', '{{json .Config.Env}}', name], {
    cwd: ROOT, encoding: 'utf8', windowsHide: true,
  })
  if (result.status !== 0) {
    throw new Error(`docker inspect ${name} failed: ${result.stderr || result.stdout}`)
  }
  const list = JSON.parse(result.stdout.trim())
  const map = {}
  for (const item of list) {
    const idx = item.indexOf('=')
    if (idx > 0) map[item.slice(0, idx)] = item.slice(idx + 1)
  }
  return map
}

function ensureContainers() {
  for (const name of [MYSQL_CONTAINER, MINIO_CONTAINER]) {
    const state = spawnSync('docker', ['inspect', '--format', '{{.State.Running}}', name], {
      cwd: ROOT, encoding: 'utf8', windowsHide: true,
    })
    if (state.status !== 0) {
      throw new Error(`Required container ${name} does not exist`)
    }
    if (state.stdout.trim() !== 'true') {
      log(`starting ${name}`)
      const start = spawnSync('docker', ['start', name], { cwd: ROOT, encoding: 'utf8', windowsHide: true })
      if (start.status !== 0) throw new Error(`Failed to start ${name}: ${start.stderr}`)
    }
  }
  // Wait for MySQL readiness
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    const ping = spawnSync('docker', [
      'exec', MYSQL_CONTAINER, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin -u root ping --silent',
    ], { cwd: ROOT, encoding: 'utf8', windowsHide: true })
    if (ping.status === 0) break
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1000)
  }
}

function sha256File(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex')
}

function findMainJar(targetDir, prefix) {
  if (!existsSync(targetDir)) return null
  for (const name of readdirSync(targetDir)) {
    if (name.startsWith(prefix) && name.endsWith('.jar') && !name.endsWith('-sources.jar') && !name.endsWith('-original.jar')) {
      return join(targetDir, name)
    }
  }
  return null
}

function buildModuleWithIsolatedPom(sourceRoot, module, destDir) {
  const buildTemp = join(BUILD_ROOT, 'tmp-build')
  const pom = isolatedPom(sourceRoot, module, buildTemp)
  try {
    const mvnw = join(sourceRoot, module, isWindows ? 'mvnw.cmd' : 'mvnw')
    log(`building ${module} from ${sourceRoot}`)
    const res = spawnSync(mvnw, ['-B', '-f', pom.path, '-DskipTests', 'package'], {
      cwd: join(sourceRoot, module),
      shell: isWindows,
      windowsHide: true,
      stdio: 'inherit',
      timeout: 15 * 60_000,
    })
    if (res.status !== 0) {
      throw new Error(`Maven build failed for ${module} (${res.status})`)
    }
    const builtJar = findMainJar(join(buildTemp, module, 'target'), `${module}-`)
    if (!builtJar) throw new Error(`Built JAR not found in ${join(buildTemp, module, 'target')}`)
    mkdirSync(destDir, { recursive: true })
    const outJar = join(destDir, `${module}.jar`)
    copyFileSync(builtJar, outJar)
    return outJar
  } finally {
    pom.cleanup()
    rmSync(buildTemp, { recursive: true, force: true })
  }
}

function prepareJars(gitValue) {
  const baselineSha = gitValue(['rev-parse', BASELINE_REF])
  if (!baselineSha) throw new Error(`Cannot resolve baseline ref ${BASELINE_REF}`)
  const headSha = gitValue(['rev-parse', 'HEAD'])

  const baseDir = join(BUILD_ROOT, 'baseline')
  const baseBackend = join(baseDir, 'backend.jar')
  const baseGateway = join(baseDir, 'gateway.jar')

  const legacyBaseBackend = join(ROOT, '.local-dev', 'builds', 'hc04-baseline-source', '.local-dev', 'builds', 'hc04-ab-backend', 'backend', 'target', 'backend-1.1.0.jar')
  const legacyBaseGateway = join(ROOT, '.local-dev', 'builds', 'hc04-baseline-source', '.local-dev', 'builds', 'hc04-ab-gateway', 'gateway', 'target', 'gateway-1.1.0.jar')

  if (!FORCE_BUILD && existsSync(baseBackend) && existsSync(baseGateway)) {
    log(`reusing cached baseline jars (${baselineSha.slice(0, 7)})`)
  } else if (!FORCE_BUILD && !SKIP_BUILD && existsSync(legacyBaseBackend) && existsSync(legacyBaseGateway)) {
    mkdirSync(baseDir, { recursive: true })
    copyFileSync(legacyBaseBackend, baseBackend)
    copyFileSync(legacyBaseGateway, baseGateway)
    log(`copied prebuilt baseline jars (${baselineSha.slice(0, 7)})`)
  } else if (SKIP_BUILD) {
    throw new Error('Missing baseline jars under .local-dev/builds/hc04-abab/baseline; omit --skip-build')
  } else {
    const wtDir = join(BUILD_ROOT, 'baseline-worktree')
    rmSync(wtDir, { recursive: true, force: true })
    const add = spawnSync('git', ['worktree', 'add', '--detach', wtDir, baselineSha], {
      cwd: ROOT, encoding: 'utf8', windowsHide: true,
    })
    if (add.status !== 0) throw new Error(`git worktree add failed: ${add.stderr || add.stdout}`)
    try {
      buildModuleWithIsolatedPom(wtDir, 'backend', baseDir)
      buildModuleWithIsolatedPom(wtDir, 'gateway', baseDir)
    } finally {
      spawnSync('git', ['worktree', 'remove', '--force', wtDir], {
        cwd: ROOT, encoding: 'utf8', windowsHide: true,
      })
      rmSync(wtDir, { recursive: true, force: true })
    }
  }

  const optDir = join(BUILD_ROOT, 'optimized')
  const optBackend = join(optDir, 'backend.jar')
  const optGateway = join(optDir, 'gateway.jar')
  if (SKIP_BUILD) {
    if (!existsSync(optBackend) || !existsSync(optGateway)) {
      throw new Error('Missing optimized jars under .local-dev/builds/hc04-abab/optimized; omit --skip-build')
    }
    log(`reusing cached optimized jars (${headSha.slice(0, 7)})`)
  } else {
    buildModuleWithIsolatedPom(ROOT, 'backend', optDir)
    buildModuleWithIsolatedPom(ROOT, 'gateway', optDir)
  }

  return {
    baseline: { sha: baselineSha, backend: baseBackend, gateway: baseGateway },
    optimized: { sha: headSha, backend: optBackend, gateway: optGateway },
  }
}

function buildRunnerEnv(mysqlEnv, minioEnv) {
  return {
    ...process.env,
    HC01_DB: 'aicostops_hc04',
    HC01_WORK_DIR: 'hc04',
    HC01_MYSQL_CONTAINER: MYSQL_CONTAINER,
    HC01_MYSQL_PORT: '14307',
    HC01_BACKEND_PORT: '18480',
    HC01_GATEWAY_PORT: '18481',
    HC01_GATEWAY_PORT_2: '18482',
    HC01_GATEWAY_PROBE_PORT: '18483',
    HC01_MOCK_PORT: '18489',
    MINIO_API_PORT: '14900',
    MYSQL_USER: mysqlEnv.MYSQL_USER,
    MYSQL_PASSWORD: mysqlEnv.MYSQL_PASSWORD,
    MYSQL_ROOT_PASSWORD: mysqlEnv.MYSQL_ROOT_PASSWORD,
    MINIO_ROOT_USER: minioEnv.MINIO_ROOT_USER,
    MINIO_ROOT_PASSWORD: minioEnv.MINIO_ROOT_PASSWORD,
  }
}

function median(values) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

function aggregateRuns(runs) {
  const byScenario = new Map()
  for (const run of runs) {
    for (const sc of run.data.scenarios ?? []) {
      if (!byScenario.has(sc.id)) byScenario.set(sc.id, [])
      byScenario.get(sc.id).push({ roundTag: run.tag, pair: run.pair, autocommit: run.data.configuration?.autocommitInsertMs, ...sc })
    }
  }
  const scenarios = {}
  for (const [id, items] of byScenario) {
    const loadItems = items.filter(x => x.load?.latencyMs && x.count === undefined)
    if (loadItems.length) {
      const statusTotals = {}
      const redisCmdTotals = {}
      let totalSuccess = 0
      let totalQueries = 0
      let totalStatements = 0
      let totalReadOnly = 0
      let totalRowLockWaits = 0
      let totalRowLockTimeMs = 0
      let totalBudgetStmts = 0
      let totalBudgetStmtMs = 0
      let totalBudgetLockMs = 0
      let totalAppRedisCalls = 0
      for (const item of loadItems) {
        for (const [k, v] of Object.entries(item.load.statusCounts ?? {})) {
          statusTotals[k] = (statusTotals[k] ?? 0) + Number(v)
        }
        const ok = Number(item.load.successCount ?? 0)
        totalSuccess += ok
        totalQueries += Number(item.sql?.queriesDelta ?? item.resources?.mysql?.statusDelta?.Queries ?? 0)
        totalStatements += Number(item.sql?.statementCount ?? 0)
        totalReadOnly += Number(item.sql?.sessionReadOnlyCount ?? 0)
        totalRowLockWaits += Number(item.resources?.mysql?.statusDelta?.Innodb_row_lock_waits ?? 0)
        totalRowLockTimeMs += Number(item.resources?.mysql?.statusDelta?.Innodb_row_lock_time ?? 0)
        const bCount = Number(item.sql?.budgetStatements ?? 0)
        totalBudgetStmts += bCount
        if (bCount > 0 && item.sql?.budgetStatementTotalMs != null) {
          totalBudgetStmtMs += Number(item.sql.budgetStatementTotalMs)
        }
        if (bCount > 0 && item.sql?.budgetStatementLockMeanMs != null) {
          totalBudgetLockMs += Number(item.sql.budgetStatementLockMeanMs) * bCount
        }
        const cmds = item.resources?.redis?.commands ?? {}
        for (const [cmd, info] of Object.entries(cmds)) {
          if (cmd === 'info' || cmd === 'auth') continue
          if (!redisCmdTotals[cmd]) redisCmdTotals[cmd] = { calls: 0, usec: 0 }
          redisCmdTotals[cmd].calls += Number(info.calls ?? 0)
          redisCmdTotals[cmd].usec += Number(info.usec ?? 0)
        }
        totalAppRedisCalls += Number(item.resources?.redis?.appCommands ?? 0)
      }
      const perPortMax = key => {
        const vals = loadItems.flatMap(it => Object.values(it.resources?.perPort ?? {}).map(p => p.maxGauges?.[key]))
          .filter(v => v !== null && v !== undefined)
        return vals.length ? Math.max(...vals) : null
      }
      const redisCmdPerSuccess = Object.fromEntries(
        Object.entries(redisCmdTotals).map(([cmd, d]) => [
          cmd,
          {
            calls: d.calls,
            perSuccess: totalSuccess > 0 ? Math.round((d.calls / totalSuccess) * 1000) / 1000 : null,
            usecPerCall: d.calls > 0 ? Math.round((d.usec / d.calls) * 100) / 100 : null,
          },
        ]),
      )
      scenarios[id] = {
        rounds: loadItems.length,
        successRpsMedian: median(loadItems.map(x => x.load.successRps)),
        p50Median: median(loadItems.map(x => x.load.latencyMs.p50)),
        p95Median: median(loadItems.map(x => x.load.latencyMs.p95)),
        p99Median: median(loadItems.map(x => x.load.latencyMs.p99)),
        statusTotals,
        http502Count: Number(statusTotals['502'] ?? 0),
        http503Count: Number(statusTotals['503'] ?? 0),
        queriesPerSuccess: totalSuccess > 0 ? Math.round((totalQueries / totalSuccess) * 1000) / 1000 : null,
        statementsPerSuccess: totalSuccess > 0 ? Math.round((totalStatements / totalSuccess) * 1000) / 1000 : null,
        sessionReadOnlyPerRoundMedian: median(loadItems.map(x => x.sql?.sessionReadOnlyCount ?? 0)),
        sessionReadOnlyTotal: totalReadOnly,
        rowLockWaitsPerRoundMedian: median(loadItems.map(x => x.resources?.mysql?.statusDelta?.Innodb_row_lock_waits ?? 0)),
        rowLockWaitMeanMs: totalRowLockWaits > 0 ? Math.round((totalRowLockTimeMs / totalRowLockWaits) * 100) / 100 : null,
        budgetStatementsTotal: totalBudgetStmts,
        budgetStatementMeanMs: totalBudgetStmts > 0 ? Math.round((totalBudgetStmtMs / totalBudgetStmts) * 1000) / 1000 : null,
        budgetStatementLockMeanMs: totalBudgetStmts > 0 ? Math.round((totalBudgetLockMs / totalBudgetStmts) * 1000) / 1000 : null,
        dataLockWaitsPeak: Math.max(...loadItems.map(x => x.resources?.mysql?.maxDataLockWaits ?? 0)),
        hikariActivePeak: perPortMax('hikaricp_connections_active'),
        hikariPendingPeak: perPortMax('hikaricp_connections_pending'),
        redisAppCommandsPerSuccess: totalSuccess > 0 ? Math.round((totalAppRedisCalls / totalSuccess) * 1000) / 1000 : null,
        redisCommandsPerSuccess: redisCmdPerSuccess,
        perRound: loadItems.map(x => ({
          roundTag: x.roundTag,
          pair: x.pair,
          successRps: x.load.successRps,
          p50: x.load.latencyMs.p50,
          p95: x.load.latencyMs.p95,
          p99: x.load.latencyMs.p99,
          statusCounts: x.load.statusCounts,
          queriesPerSuccess: x.sql?.queriesPerSuccess ?? null,
          statementsPerSuccess: x.sql?.statementsPerSuccess ?? null,
          budgetStatementMeanMs: x.sql?.budgetStatementMeanMs ?? null,
          budgetStatementLockMeanMs: x.sql?.budgetStatementLockMeanMs ?? null,
          rowLockWaits: x.resources?.mysql?.statusDelta?.Innodb_row_lock_waits ?? null,
          rowLockTimeMs: x.resources?.mysql?.statusDelta?.Innodb_row_lock_time ?? null,
          dataLockWaitsPeak: x.resources?.mysql?.maxDataLockWaits ?? null,
          redisAppCommands: x.resources?.redis?.appCommands ?? null,
          redisCommands: x.resources?.redis?.commands ?? null,
          topStatements: x.sql?.topStatements ?? null,
          failures: x.failures,
        })),
      }
    } else if (id === 'expiry_probe') {
      const sample = items[0]
      scenarios[id] = {
        rounds: items.length,
        expiryRecoveryMs: sample.expiryRecoveryMs,
        statusCounts: sample.load?.statusCounts ?? null,
        failures: sample.failures,
      }
    }
  }
  return scenarios
}

async function main() {
  mkdirSync(WORK_ROOT, { recursive: true })
  ensureContainers()
  const mysqlEnv = inspectContainerEnv(MYSQL_CONTAINER)
  const minioEnv = inspectContainerEnv(MINIO_CONTAINER)
  const runnerEnv = buildRunnerEnv(mysqlEnv, minioEnv)

  // Apply runnerEnv to current process before importing lib.mjs/hc04.mjs so DB_NAME and ports match HC-04.
  Object.assign(process.env, runnerEnv)
  const { gitValue, mysql, mysqlNumber, nowIso, sleep, writeJson } = await import('../hc01/lib.mjs')
  const { autocommitDrift, measureAutocommit } = await import('../hc01/hc04.mjs')

  if (mysqlNumber('SELECT @@global.innodb_flush_log_at_trx_commit', { database: null }) !== 1) {
    mysql('SET GLOBAL innodb_flush_log_at_trx_commit=1', { database: null })
  }

  const jars = prepareJars(gitValue)
  log(`baseline=${jars.baseline.sha.slice(0, 7)} optimized=${jars.optimized.sha.slice(0, 7)}`)

  const schedule = []
  for (let pair = 1; pair <= PAIRS; pair++) {
    schedule.push({ pair, label: `A${pair}`, variant: 'baseline', tag: `a${pair}-baseline`, skipS6S7: pair > 1 })
    schedule.push({ pair, label: `B${pair}`, variant: 'optimized', tag: `b${pair}-optimized`, skipS6S7: pair > 1 })
  }

  const progressPath = join(WORK_ROOT, 'abab-progress.json')
  let preWarmAutocommit
  let lastValidAutocommit
  const validRuns = { baseline: [], optimized: [] }
  const voidedRuns = []
  const orderTrace = []

  if (RESUME && existsSync(progressPath)) {
    const saved = JSON.parse(readFileSync(progressPath, 'utf8'))
    preWarmAutocommit = saved.preWarmAutocommit
    lastValidAutocommit = saved.lastValidAutocommit
    voidedRuns.push(...(saved.voidedRuns ?? []))
    orderTrace.push(...(saved.orderTrace ?? []))
    for (const done of orderTrace) {
      const stepMeta = schedule.find(s => s.tag === done.tag)
      if (!stepMeta) continue
      const resultPath = join(WORK_ROOT, done.tag, 'load-results.json')
      const data = JSON.parse(readFileSync(resultPath, 'utf8'))
      validRuns[done.variant].push({ ...stepMeta, resultPath, data })
    }
    log(`resumed from progress: completed=${orderTrace.map(x => x.label).join(',') || 'none'} voided=${voidedRuns.length}`)
  } else {
    // Pre-warm MySQL redo/buffer pool and measure resting autocommit before A1
    mysql(`DROP DATABASE IF EXISTS \`aicostops_hc04\`;
      CREATE DATABASE \`aicostops_hc04\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
      GRANT ALL PRIVILEGES ON \`aicostops_hc04\`.* TO '${mysqlEnv.MYSQL_USER}'@'%';
      FLUSH PRIVILEGES;`, { database: null, password: mysqlEnv.MYSQL_ROOT_PASSWORD })
    preWarmAutocommit = measureAutocommit()
    lastValidAutocommit = preWarmAutocommit
    log(`pre-warm autocommit: p50=${preWarmAutocommit.p50}ms mean=${preWarmAutocommit.mean}ms p99=${preWarmAutocommit.p99}ms`)
  }

  const saveProgress = () => writeJson(progressPath, {
    updatedAt: nowIso(),
    preWarmAutocommit,
    lastValidAutocommit,
    orderTrace,
    voidedRuns,
  })
  saveProgress()

  for (const step of schedule) {
    if (orderTrace.some(x => x.tag === step.tag)) {
      log(`skipping already completed ${step.label} (${step.tag})`)
      continue
    }
    const bundle = jars[step.variant]
    const outDir = join(WORK_ROOT, step.tag)
    let completed = null

    for (let attempt = 1; attempt <= MAX_AUTOCOMMIT_RETRIES; attempt++) {
      rmSync(outDir, { recursive: true, force: true })
      mkdirSync(outDir, { recursive: true })
      const args = [
        join(ROOT, 'scripts', 'perf', 'hc01', 'runner.mjs'),
        '--suite=hc04',
        '--rounds=1',
        `--warmup=${WARMUP_SEC}`,
        `--measure=${MEASURE_SEC}`,
        `--variant=${step.variant}`,
        `--code-sha=${bundle.sha}`,
        `--backend-jar=${bundle.backend}`,
        `--gateway-jar=${bundle.gateway}`,
        `--output=${outDir}`,
        '--skip-build',
      ]
      if (step.skipS6S7) args.push('--hc04-skip-s6-s7')
      if (lastValidAutocommit) {
        args.push(
          `--hc04-anchor-autocommit-p50=${lastValidAutocommit.p50}`,
          `--hc04-anchor-autocommit-mean=${lastValidAutocommit.mean}`,
          `--hc04-max-autocommit-drift=${MAX_AUTOCOMMIT_DRIFT}`,
        )
      }

      log(`starting ${step.label} (${step.tag}, attempt ${attempt}/${MAX_AUTOCOMMIT_RETRIES})`)
      const res = spawnSync(process.execPath, args, {
        cwd: ROOT,
        env: runnerEnv,
        stdio: 'inherit',
        windowsHide: true,
      })

      if (res.status === 42) {
        const voidFile = join(outDir, 'autocommit-voided.json')
        const voidInfo = existsSync(voidFile) ? JSON.parse(readFileSync(voidFile, 'utf8')) : { reason: 'exit 42' }
        voidedRuns.push({ label: step.label, tag: step.tag, attempt, ...voidInfo })
        saveProgress()
        log(`VOIDED ${step.label} attempt ${attempt}: ${voidInfo.reason}. Waiting ${SETTLE_WAIT_SEC}s before retry...`)
        await sleep(SETTLE_WAIT_SEC * 1000)
        continue
      }

      if (res.status !== 0) {
        throw new Error(`${step.label} (${step.tag}) failed with exit code ${res.status}. Inspect ${outDir}/load-results.json and .local-dev/hc04/logs/`)
      }

      const resultPath = join(outDir, 'load-results.json')
      const data = JSON.parse(readFileSync(resultPath, 'utf8'))
      const any502 = (data.scenarios ?? []).some(s => Number(s.load?.statusCounts?.['502'] ?? 0) > 0)
      if (any502) {
        throw new Error(`${step.label} (${step.tag}) recorded unexpected HTTP 502! Inspect ${resultPath}`)
      }
      completed = { ...step, resultPath, data }
      break
    }

    if (!completed) {
      throw new Error(`${step.label} (${step.tag}) exceeded ${MAX_AUTOCOMMIT_RETRIES} autocommit drift retries`)
    }

    lastValidAutocommit = completed.data.configuration.autocommitInsertMs
    validRuns[step.variant].push(completed)
    orderTrace.push({
      label: step.label,
      variant: step.variant,
      tag: step.tag,
      autocommit: lastValidAutocommit,
      driftVsPrevious: completed.data.configuration.autocommitDrift ?? autocommitDrift(lastValidAutocommit, preWarmAutocommit),
    })
    saveProgress()
    log(`completed ${step.label}: autocommit p50=${lastValidAutocommit.p50}ms mean=${lastValidAutocommit.mean}ms`)
  }

  const summary = {
    generatedAt: nowIso(),
    schedule: `${PAIRS} pairs ABAB (${orderTrace.map(x => x.label).join(' -> ')})`,
    maxAutocommitDriftRatio: MAX_AUTOCOMMIT_DRIFT,
    preWarmAutocommit,
    orderTrace,
    voidedRuns,
    jars: {
      baseline: { sha: jars.baseline.sha, gatewaySha256: sha256File(jars.baseline.gateway), backendSha256: sha256File(jars.baseline.backend) },
      optimized: { sha: jars.optimized.sha, gatewaySha256: sha256File(jars.optimized.gateway), backendSha256: sha256File(jars.optimized.backend) },
    },
    baseline: {
      environment: validRuns.baseline[0].data.environment,
      scenarios: aggregateRuns(validRuns.baseline),
      finalPerRound: validRuns.baseline.map(r => ({ tag: r.tag, final: r.data.final })),
    },
    optimized: {
      environment: validRuns.optimized[0].data.environment,
      scenarios: aggregateRuns(validRuns.optimized),
      finalPerRound: validRuns.optimized.map(r => ({ tag: r.tag, final: r.data.final })),
    },
  }

  const summaryPath = join(WORK_ROOT, 'abab-summary.json')
  writeJson(summaryPath, summary)
  log(`ABAB summary written: ${summaryPath}`)
}

main().catch(err => {
  console.error(`[HC-04-ABAB-FAIL] ${err.stack ?? err.message}`)
  process.exit(1)
})
