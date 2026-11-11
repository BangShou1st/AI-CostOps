/**
 * Per-round resource sampler: Gateway Micrometer gauges/counters, MySQL
 * instantaneous lock/transaction pressure, and Redis command latency.
 * Missing gauges are reported as null (NOT_VERIFIED), never as zero.
 */
import { fetchPrometheus, maxMetric, sleep, sumMetric } from './lib.mjs'
import { mysqlGlobalStatus, mysqlLockChains, mysqlMomentary, redisDelta, redisSnapshot } from './verify.mjs'

const COUNTERS = [
  'gateway_request_total',
  'gateway_reservation_attempt_total',
  'gateway_usage_total',
  'gateway_provider_error_total',
  'gateway_redis_dependency_error_total',
  'gateway_circuit_transition_total',
  'gateway_quota_total',
  'gateway_failover_total',
  'hikaricp_connections_timeout_total',
]

function extract(series) {
  const counters = {}
  for (const name of COUNTERS) {
    const value = sumMetric(series, name)
    if (value !== null) counters[name] = value
  }
  return {
    counters,
    gauges: {
      gateway_active_streams: maxMetric(series, 'gateway_active_streams'),
      gateway_db_active: maxMetric(series, 'gateway_db_active'),
      gateway_db_queued: maxMetric(series, 'gateway_db_queued'),
      gateway_db_threads_max: maxMetric(series, 'gateway_db_threads_max'),
      gateway_db_queue_capacity: maxMetric(series, 'gateway_db_queue_capacity'),
      hikaricp_connections_active: maxMetric(series, 'hikaricp_connections_active'),
      hikaricp_connections_pending: maxMetric(series, 'hikaricp_connections_pending'),
      hikaricp_connections_max: maxMetric(series, 'hikaricp_connections_max'),
    },
    processCpu: maxMetric(series, 'process_cpu_usage'),
    systemCpu: maxMetric(series, 'system_cpu_usage'),
    heapUsed: sumMetric(series, 'jvm_memory_used_bytes', 'area="heap"'),
    gcPauseCount: sumMetric(series, 'jvm_gc_pause_seconds_count'),
    gcPauseSum: sumMetric(series, 'jvm_gc_pause_seconds_sum'),
    httpRequests: sumMetric(series, 'http_server_requests_seconds_count'),
  }
}

function delta(first, last, name) {
  const a = first?.counters?.[name]
  const b = last?.counters?.[name]
  return a === undefined || b === undefined ? null : b - a
}

export class Sampler {
  constructor(ports, options = {}) {
    this.ports = ports
    this.intervalMs = options.intervalMs ?? 1000
    this.samples = new Map(ports.map(port => [port, []]))
    this.mysql = []
    this.lockChains = []
    this.running = false
    this.task = null
    this.redisBefore = null
    this.mysqlStatusBefore = null
    this.errors = []
  }

  async start() {
    this.running = true
    this.redisBefore = redisSnapshot()
    this.mysqlStatusBefore = mysqlGlobalStatus()
    this.task = this.loop()
    await this.sampleOnce()
  }

  async loop() {
    while (this.running) {
      await sleep(this.intervalMs)
      if (!this.running) break
      await this.sampleOnce()
    }
  }

  async sampleOnce() {
    for (const port of this.ports) {
      try {
        const series = await fetchPrometheus(port)
        this.samples.get(port).push({ at: Date.now(), data: extract(series) })
      } catch (error) {
        this.errors.push(`port ${port}: ${error.message}`)
      }
    }
    try {
      this.mysql.push({ at: Date.now(), ...mysqlMomentary() })
      this.lockChains.push(...mysqlLockChains())
    } catch (error) {
      this.errors.push(`mysql: ${error.message}`)
    }
  }

  async stop() {
    this.running = false
    if (this.task) await this.task
    await this.sampleOnce()
    const redisAfter = redisSnapshot()
    const mysqlStatusAfter = mysqlGlobalStatus()
    const perPort = {}
    for (const port of this.ports) {
      const samples = this.samples.get(port)
      if (!samples.length) {
        perPort[port] = { status: 'NOT_VERIFIED', samples: 0 }
        continue
      }
      const first = samples[0].data
      const last = samples[samples.length - 1].data
      const maxOf = key => {
        const values = samples.map(sample => sample.data.gauges[key]).filter(value => value !== null && value !== undefined)
        return values.length ? Math.max(...values) : null
      }
      const maxRaw = key => {
        const values = samples.map(sample => sample.data[key]).filter(value => value !== null && value !== undefined)
        return values.length ? Math.max(...values) : null
      }
      const avgRaw = key => {
        const values = samples.map(sample => sample.data[key]).filter(value => value !== null && value !== undefined)
        return values.length ? Math.round((values.reduce((a, b) => a + b, 0) / values.length) * 10000) / 10000 : null
      }
      perPort[port] = {
        status: 'PASS',
        samples: samples.length,
        windowSec: Math.round((samples[samples.length - 1].at - samples[0].at) / 100) / 10,
        countersDelta: Object.fromEntries(COUNTERS.map(name => [name, delta(first, last, name)])),
        maxGauges: Object.fromEntries(Object.keys(first.gauges).map(name => [name, maxOf(name)])),
        finalGauges: last.gauges,
        processCpuMax: maxRaw('processCpu'),
        processCpuAvg: avgRaw('processCpu'),
        systemCpuMax: maxRaw('systemCpu'),
        heapUsedMaxBytes: maxRaw('heapUsed'),
        heapUsedLastBytes: last.heapUsed,
        httpRequestsDelta: (first.httpRequests !== null && last.httpRequests !== null)
          ? last.httpRequests - first.httpRequests : null,
        gcPauseCountDelta: (first.gcPauseCount !== null && last.gcPauseCount !== null)
          ? last.gcPauseCount - first.gcPauseCount : null,
        gcPauseSecondsDelta: (first.gcPauseSum !== null && last.gcPauseSum !== null)
          ? Math.round((last.gcPauseSum - first.gcPauseSum) * 1000) / 1000 : null,
      }
    }
    const mysqlMax = key => {
      if (!this.mysql.length) return null
      return Math.max(...this.mysql.map(sample => sample[key] ?? 0))
    }
    const mysqlAvg = key => {
      if (!this.mysql.length) return null
      return Math.round((this.mysql.reduce((sum, sample) => sum + (sample[key] ?? 0), 0) / this.mysql.length) * 100) / 100
    }
    const statusDelta = {}
    for (const name of Object.keys(mysqlStatusAfter)) {
      statusDelta[name] = mysqlStatusAfter[name] - (this.mysqlStatusBefore[name] ?? 0)
    }
    const blocking = new Map()
    for (const chain of this.lockChains) {
      const key = `${chain.blockingTable}:${chain.blockingMode}`
      const current = blocking.get(key) ?? { blockingTable: chain.blockingTable, blockingMode: chain.blockingMode, maxWaiters: 0 }
      current.maxWaiters = Math.max(current.maxWaiters, chain.waiters)
      blocking.set(key, current)
    }
    return {
      perPort,
      mysql: {
        samples: this.mysql.length,
        maxDataLockWaits: mysqlMax('dataLockWaits'),
        maxInnodbTransactions: mysqlMax('innodbTransactions'),
        maxThreadsRunning: mysqlMax('threadsRunning'),
        avgThreadsRunning: mysqlAvg('threadsRunning'),
        statusDelta,
        blockingObjects: [...blocking.values()],
      },
      redis: redisDelta(this.redisBefore, redisAfter),
      samplerErrors: this.errors,
    }
  }
}
