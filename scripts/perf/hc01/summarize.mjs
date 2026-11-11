#!/usr/bin/env node
/**
 * Renders a committed HC-01 load-results.json into Markdown tables for the
 * acceptance report. Aggregates rounds by scenario id; never invents a value
 * that is absent from the raw JSON (prints "NOT_VERIFIED" instead).
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const file = resolve(process.argv[2]
  ?? 'docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-01-gateway-concurrency/load-results.json')
const data = JSON.parse(readFileSync(file, 'utf8'))

function median(values) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
}

function round(value, digits = 1) {
  return value === null || value === undefined ? 'NOT_VERIFIED' : Number(value).toFixed(digits)
}

function mergeCounts(target, source) {
  for (const [key, value] of Object.entries(source ?? {})) {
    target[key] = (target[key] ?? 0) + value
  }
}

const groups = new Map()
for (const scenario of data.scenarios) {
  if (!groups.has(scenario.id)) groups.set(scenario.id, [])
  groups.get(scenario.id).push(scenario)
}

console.log(`<!-- generated from ${file} -->`)
console.log(`\n### 负载场景（按轮次聚合）\n`)
console.log('| 场景 | 实例 | 并发 | 轮次 | Provider | 成功 RPS（轮次中位） | P50 ms | P95 ms | P99 ms | 状态码 | Provider 调用 | DB 队列峰值 | Hikari 活跃峰值 | 行锁等待/轮 | 失败 |')
console.log('|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|')
for (const [id, runs] of groups) {
  const measured = runs.filter(run => run.load?.latencyMs)
  if (!measured.length) continue
  const rps = measured.map(run => run.load.successRps)
  const statusCounts = {}
  for (const run of measured) mergeCounts(statusCounts, run.load.statusCounts)
  const p50 = median(measured.map(run => run.load.latencyMs.p50))
  const p95 = median(measured.map(run => run.load.latencyMs.p95))
  const p99 = median(measured.map(run => run.load.latencyMs.p99))
  const ops = measured.map(run => run.providerOps).filter(value => value !== null)
  const dbQueued = measured.flatMap(run => Object.values(run.resources?.perPort ?? {})
    .map(port => port.maxGauges?.gateway_db_queued)).filter(value => value !== null && value !== undefined)
  const hikari = measured.flatMap(run => Object.values(run.resources?.perPort ?? {})
    .map(port => port.maxGauges?.hikaricp_connections_active)).filter(value => value !== null && value !== undefined)
  const lockWaits = measured.map(run => run.resources?.mysql?.statusDelta?.Innodb_row_lock_waits)
    .filter(value => value !== null && value !== undefined)
  const failures = measured.reduce((total, run) => total + (run.failures?.length ?? 0), 0)
  console.log(`| ${id} | ${measured[0].instances} | ${measured[0].concurrency} | ${measured.length} | ${measured[0].providerMode} | ${round(median(rps))}（${round(Math.min(...rps))}–${round(Math.max(...rps))}） | ${round(p50)} | ${round(p95)} | ${round(p99)} | ${JSON.stringify(statusCounts)} | ${ops.length ? ops.join('/') : 'NOT_VERIFIED'} | ${dbQueued.length ? Math.max(...dbQueued) : 'NOT_VERIFIED'} | ${hikari.length ? Math.max(...hikari) : 'NOT_VERIFIED'} | ${lockWaits.length ? lockWaits.join('/') : 'NOT_VERIFIED'} | ${failures} |`)
}

console.log(`\n### 正确性/边界场景\n`)
console.log('| 场景 | Provider 调用 | 状态分布 | 关键观测 | 失败 |')
console.log('|---|---:|---|---|---:|')
for (const [id, runs] of groups) {
  const run = runs[0]
  if (run.load?.latencyMs) continue
  const observations = []
  if (run.activeStreamsDuringHold !== undefined) observations.push(`hold_active=${run.activeStreamsDuringHold}`)
  if (run.activeStreamsAfterRelease !== undefined) observations.push(`after_release=${run.activeStreamsAfterRelease}`)
  if (run.activeStreamsAfterCancel !== undefined) observations.push(`after_cancel=${run.activeStreamsAfterCancel}`)
  if (run.overCeilingStatus !== undefined) observations.push(`ceiling+1=${run.overCeilingStatus}`)
  if (run.freshStatus !== undefined) observations.push(`fresh=${run.freshStatus}`)
  if (run.usageStatusCounts) observations.push(`usage=${JSON.stringify(run.usageStatusCounts)}`)
  if (run.reservationStatusCounts) observations.push(`reservations=${JSON.stringify(run.reservationStatusCounts)}`)
  if (run.settlements !== undefined) observations.push(`settlements=${run.settlements}`)
  if (run.count) observations.push(`count=${run.count}`)
  console.log(`| ${id} | ${run.providerOps ?? 'NOT_VERIFIED'} | ${JSON.stringify(run.load?.statusCounts ?? {})} | ${observations.join('；')} | ${(run.failures ?? []).length} |`)
}

console.log(`\n### 最终不变量\n`)
console.log(`- converged: **${data.final?.converged ? 'PASS' : 'FAIL'}**`)
console.log(`- settlement violations: ${data.final?.settlementViolations?.length ?? 'NOT_VERIFIED'}`)
console.log(`- budget overspend: ${data.final?.budget?.violations?.length ?? 'NOT_VERIFIED'}`)
console.log(`- active streams at end: ${JSON.stringify(data.final?.activeStreamsAtEnd ?? {})}`)
console.log(`- environment: ${data.environment?.git?.sha ?? 'unknown'} (${data.environment?.git?.branch ?? 'unknown'})`)
