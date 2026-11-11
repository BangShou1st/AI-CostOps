#!/usr/bin/env node
/**
 * HC-02 A/B comparison: reads two HC-01-format load-results.json files
 * (baseline and optimized), aggregates measured rounds by scenario and prints
 * a Markdown table plus a machine-readable summary. It never invents a value:
 * missing rounds/metrics are printed as NOT_VERIFIED.
 *
 * Usage:
 *   node scripts/perf/hc02/compare.mjs <baseline/load-results.json> <optimized/load-results.json> [out-dir]
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join, resolve } from 'node:path'

const [baselineArg, optimizedArg, outDirArg] = process.argv.slice(2)
if (!baselineArg || !optimizedArg) {
  console.error('usage: compare.mjs <baseline/load-results.json> <optimized/load-results.json> [out-dir]')
  process.exit(2)
}

const baseline = JSON.parse(readFileSync(resolve(baselineArg), 'utf8'))
const optimized = JSON.parse(readFileSync(resolve(optimizedArg), 'utf8'))

function median(values) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
}

function round(value, digits = 1) {
  return value === null || value === undefined ? 'NOT_VERIFIED' : Number(value).toFixed(digits)
}

function delta(before, after, digits = 1) {
  if (before === null || before === undefined || after === null || after === undefined || before === 0) {
    return 'NOT_VERIFIED'
  }
  const percent = ((after - before) / before) * 100
  return `${percent >= 0 ? '+' : ''}${percent.toFixed(digits)}%`
}

function mergeCounts(target, source) {
  for (const [key, value] of Object.entries(source ?? {})) {
    target[key] = (target[key] ?? 0) + value
  }
}

function summarize(data) {
  const groups = new Map()
  for (const scenario of data.scenarios ?? []) {
    if (!groups.has(scenario.id)) groups.set(scenario.id, [])
    groups.get(scenario.id).push(scenario)
  }
  const summary = new Map()
  for (const [id, runs] of groups) {
    const measured = runs.filter(run => run.load?.latencyMs && run.count === undefined)
    if (!measured.length) continue
    const rps = measured.map(run => run.load.successRps).filter(value => value !== null)
    const p50 = measured.map(run => run.load.latencyMs.p50).filter(value => value !== null)
    const p95 = measured.map(run => run.load.latencyMs.p95).filter(value => value !== null)
    const p99 = measured.map(run => run.load.latencyMs.p99).filter(value => value !== null)
    const statusCounts = {}
    for (const run of measured) mergeCounts(statusCounts, run.load.statusCounts)
    const lockWaits = measured
      .map(run => run.resources?.mysql?.statusDelta?.Innodb_row_lock_waits)
      .filter(value => value !== null && value !== undefined)
    const dbQueued = measured.flatMap(run => Object.values(run.resources?.perPort ?? {})
      .map(port => port.maxGauges?.gateway_db_queued))
      .filter(value => value !== null && value !== undefined)
    const successCount = Number(statusCounts['200'] ?? 0)
    const totalLockWaits = lockWaits.reduce((sum, value) => sum + Number(value), 0)
    summary.set(id, {
      rounds: measured.length,
      concurrency: measured[0].concurrency,
      instances: measured[0].instances,
      successRps: median(rps),
      rpsRange: rps.length ? [Math.min(...rps), Math.max(...rps)] : null,
      p50: median(p50),
      p95: median(p95),
      p99: median(p99),
      statusCounts,
      providerOps: measured.map(run => run.providerOps),
      dbQueuedMax: dbQueued.length ? Math.max(...dbQueued) : null,
      rowLockWaitsMedian: median(lockWaits),
      rowLockWaitsPer1k: successCount ? (totalLockWaits / successCount) * 1000 : null,
      successCount,
      failures: measured.reduce((total, run) => total + (run.failures?.length ?? 0), 0),
    })
  }
  return summary
}

const beforeSummary = summarize(baseline)
const afterSummary = summarize(optimized)
const ids = [...new Set([...beforeSummary.keys(), ...afterSummary.keys()])]

console.log('### A/B 对比（按场景聚合轮次中位）\n')
console.log('| 场景 | 实例 | 并发 | 基线 RPS | 优化 RPS | RPS Δ | 基线 P95 ms | 优化 P95 ms | P95 Δ | 基线 P99 ms | 优化 P99 ms | 行锁等待/轮（基线→优化） | 行锁等待/1k 成功（基线→优化） | 失败（基线/优化） |')
console.log('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
for (const id of ids) {
  const before = beforeSummary.get(id)
  const after = afterSummary.get(id)
  console.log(`| ${id} | ${after?.instances ?? before?.instances ?? 'NOT_VERIFIED'} | ${after?.concurrency ?? before?.concurrency ?? 'NOT_VERIFIED'}`
    + ` | ${round(before?.successRps)}${before?.rpsRange ? `（${round(before.rpsRange[0])}–${round(before.rpsRange[1])}）` : ''}`
    + ` | ${round(after?.successRps)}${after?.rpsRange ? `（${round(after.rpsRange[0])}–${round(after.rpsRange[1])}）` : ''}`
    + ` | ${delta(before?.successRps, after?.successRps)}`
    + ` | ${round(before?.p95)} | ${round(after?.p95)} | ${delta(before?.p95, after?.p95)}`
    + ` | ${round(before?.p99)} | ${round(after?.p99)}`
    + ` | ${round(before?.rowLockWaitsMedian, 0)} → ${round(after?.rowLockWaitsMedian, 0)}`
    + ` | ${round(before?.rowLockWaitsPer1k)} → ${round(after?.rowLockWaitsPer1k)}`
    + ` | ${before?.failures ?? 'NOT_VERIFIED'}/${after?.failures ?? 'NOT_VERIFIED'} |`)
}

console.log('\n### 最终不变量\n')
for (const [label, data] of [['baseline', baseline], ['optimized', optimized]]) {
  console.log(`- ${label}: converged=${data.final?.converged ? 'PASS' : 'FAIL'}`
    + `, settlementViolations=${data.final?.settlementViolations?.length ?? 'NOT_VERIFIED'}`
    + `, budgetOverspend=${data.final?.budget?.violations?.length ?? 'NOT_VERIFIED'}`
    + `, sha=${data.environment?.git?.sha ?? data.commit ?? 'unknown'}`
    + `, variant=${data.configuration?.variant ?? 'unknown'}`)
}

const summary = {
  generatedAt: new Date().toISOString(),
  baseline: {
    file: resolve(baselineArg),
    sha: baseline.environment?.git?.sha ?? baseline.commit ?? null,
    variant: baseline.configuration?.variant ?? null,
    gatewayJar: baseline.configuration?.gatewayJar ?? null,
    environment: baseline.environment ?? null,
    rounds: baseline.configuration?.rounds ?? null,
    scenarios: Object.fromEntries(beforeSummary),
    final: baseline.final ?? null,
  },
  optimized: {
    file: resolve(optimizedArg),
    sha: optimized.environment?.git?.sha ?? optimized.commit ?? null,
    variant: optimized.configuration?.variant ?? null,
    gatewayJar: optimized.configuration?.gatewayJar ?? null,
    environment: optimized.environment ?? null,
    rounds: optimized.configuration?.rounds ?? null,
    scenarios: Object.fromEntries(afterSummary),
    final: optimized.final ?? null,
  },
  comparison: ids.map(id => {
    const before = beforeSummary.get(id) ?? null
    const after = afterSummary.get(id) ?? null
    return {
      id,
      baseline: before,
      optimized: after,
      rpsDeltaPercent: before?.successRps && after?.successRps
        ? ((after.successRps - before.successRps) / before.successRps) * 100 : null,
      p95DeltaPercent: before?.p95 && after?.p95
        ? ((after.p95 - before.p95) / before.p95) * 100 : null,
    }
  }),
}

if (outDirArg) {
  const outDir = resolve(outDirArg)
  mkdirSync(outDir, { recursive: true })
  writeFileSync(join(outDir, 'results.json'), `${JSON.stringify(summary, null, 2)}\n`, 'utf8')
  console.error(`[compare] wrote ${join(outDir, 'results.json')}`)
}

