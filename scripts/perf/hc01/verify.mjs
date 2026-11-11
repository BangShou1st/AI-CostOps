/**
 * HC-01 invariant probes. They read the real financial tables and the real
 * bounded-resource gauges; a load number is never accepted as correctness
 * evidence on its own.
 */
import {
  DB_NAME, config, decimalToScaled, mysqlRows, mysqlScalar, redisCli, scaledToDecimal, sleep,
} from './lib.mjs'

export function sqlTimestamp(date = new Date()) {
  return date.toISOString().replace('T', ' ').replace('Z', '')
}

export function mysqlGlobalStatus() {
  const rows = mysqlRows(`SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status
    WHERE VARIABLE_NAME IN (
      'Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_time_max',
      'Threads_connected','Threads_running','Max_used_connections','Aborted_connects',
      'Innodb_buffer_pool_wait_free','Queries','Slow_queries','Innodb_deadlocks',
      'Innodb_row_lock_current_waits'
    ) ORDER BY VARIABLE_NAME;`)
  return Object.fromEntries(rows.map(([name, value]) => [name, Number(value)]))
}

export function mysqlMomentary() {
  const row = mysqlRows(`SELECT
    (SELECT COUNT(*) FROM performance_schema.data_lock_waits),
    (SELECT COUNT(*) FROM information_schema.innodb_trx),
    (SELECT COUNT(*) FROM information_schema.processlist WHERE COMMAND <> 'Sleep'),
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running');`)[0]
  return {
    dataLockWaits: Number(row[0]),
    innodbTransactions: Number(row[1]),
    activeProcesses: Number(row[2]),
    threadsRunning: Number(row[3]),
  }
}

export function mysqlLockChains() {
  return mysqlRows(`SELECT bl.OBJECT_NAME, bl.LOCK_MODE, COUNT(*) AS waiters
    FROM performance_schema.data_lock_waits w
    JOIN performance_schema.data_locks bl
      ON bl.ENGINE_TRANSACTION_ID=w.BLOCKING_ENGINE_TRANSACTION_ID
     AND bl.LOCK_STATUS='GRANTED' AND bl.LOCK_TYPE='RECORD'
    GROUP BY bl.OBJECT_NAME, bl.LOCK_MODE
    ORDER BY waiters DESC`).map(row => ({
    blockingTable: row[0],
    blockingMode: row[1],
    waiters: Number(row[2]),
  }))
}

export function redisSnapshot() {
  const commandStats = redisCli(['info', 'commandstats'])
  const stats = redisCli(['info', 'stats'])
  const memory = redisCli(['info', 'memory'])
  const parsed = {}
  for (const line of commandStats.split(/\r?\n/)) {
    const match = /^cmdstat_([^:]+):calls=(\d+),usec=(\d+)(?:,usec_per_call=([\d.]+))?/.exec(line)
    if (!match) continue
    parsed[match[1]] = {
      calls: Number(match[2]),
      usec: Number(match[3]),
      usecPerCall: match[4] ? Number(match[4]) : null,
    }
  }
  const stat = Object.fromEntries(
    stats.split(/\r?\n/)
      .map(line => /^(\w+):([^,\r\n]+)/.exec(line))
      .filter(Boolean)
      .map(match => [match[1], match[2]]),
  )
  const mem = Object.fromEntries(
    memory.split(/\r?\n/)
      .map(line => /^(\w+):([^,\r\n]+)/.exec(line))
      .filter(Boolean)
      .map(match => [match[1], match[2]]),
  )
  return {
    evalsha: parsed.evalsha ?? null,
    evalshaCalls: parsed.evalsha?.calls ?? 0,
    evalshaUsec: parsed.evalsha?.usec ?? 0,
    totalCommands: Number(stat.total_commands_processed ?? 0),
    usedMemoryPeak: Number(mem.used_memory_peak ?? 0),
  }
}

export function redisDelta(before, after) {
  return {
    evalshaCalls: after.evalshaCalls - before.evalshaCalls,
    evalshaUsec: after.evalshaUsec - before.evalshaUsec,
    evalshaUsecPerCall: after.evalshaCalls - before.evalshaCalls > 0
      ? Math.round(((after.evalshaUsec - before.evalshaUsec) / (after.evalshaCalls - before.evalshaCalls)) * 100) / 100
      : null,
    totalCommands: after.totalCommands - before.totalCommands,
  }
}

export function budgetSnapshot(budgetIds) {
  if (!budgetIds.length) return { budgets: [], violations: [] }
  const ids = budgetIds.join(',')
  const rows = mysqlRows(`SELECT b.id, b.total_amount, b.actual_amount, b.committed_amount,
      COALESCE((SELECT SUM(r.reserved_amount) FROM budget_reservation r
        WHERE r.budget_id=b.id AND r.status IN ('ACTIVE','PENDING_HOLD')),0) AS effective_holds,
      (SELECT COUNT(*) FROM budget_reservation r WHERE r.budget_id=b.id AND r.status='ACTIVE') AS active_holds,
      (SELECT COUNT(*) FROM budget_reservation r WHERE r.budget_id=b.id AND r.status='PENDING_HOLD') AS pending_holds,
      (SELECT COUNT(*) FROM budget_reservation r WHERE r.budget_id=b.id AND r.status='SETTLED') AS settled_holds
    FROM budget b WHERE b.id IN (${ids}) ORDER BY b.id;`)
  const budgets = rows.map(row => {
    const total = decimalToScaled(row[1])
    const actual = decimalToScaled(row[2])
    const committed = decimalToScaled(row[3])
    const holds = decimalToScaled(row[4])
    const used = actual + committed + holds
    return {
      budgetId: Number(row[0]),
      total: row[1], actual: row[2], committed: row[3], effectiveHolds: row[4],
      used: scaledToDecimal(used), available: scaledToDecimal(total - used),
      activeHolds: Number(row[5]), pendingHolds: Number(row[6]), settledHolds: Number(row[7]),
      overspent: used > total,
    }
  })
  return { budgets, violations: budgets.filter(item => item.overspent) }
}

export function dispatchSnapshot(orgIds, sinceIso) {
  if (!orgIds.length) return null
  const orgList = orgIds.join(',')
  const since = sqlTimestamp(new Date(sinceIso))
  const row = mysqlRows(`SELECT
    (SELECT COUNT(*) FROM gateway_request WHERE org_id IN (${orgList}) AND created_at >= '${since}'),
    (SELECT COUNT(*) FROM gateway_request gr WHERE gr.org_id IN (${orgList}) AND gr.created_at >= '${since}'
      AND (SELECT COUNT(*) FROM gateway_route_attempt ra WHERE ra.request_id=gr.id AND ra.org_id=gr.org_id) > 1),
    (SELECT COUNT(*) FROM (SELECT request_id FROM gateway_usage_fact
      WHERE org_id IN (${orgList}) AND status='FINAL' GROUP BY org_id,request_id HAVING COUNT(*)>1) d),
    (SELECT COUNT(*) FROM gateway_request gr JOIN gateway_usage_fact uf
      ON uf.id=gr.current_usage_fact_id AND uf.org_id=gr.org_id
      WHERE gr.org_id IN (${orgList}) AND gr.created_at >= '${since}'
        AND gr.state IN ('COMPLETED','TRANSPORT_COMPLETED') AND uf.status<>'FINAL'),
    (SELECT COUNT(*) FROM gateway_request gr
      WHERE gr.org_id IN (${orgList}) AND gr.created_at >= '${since}'
        AND gr.state IN ('COMPLETED','TRANSPORT_COMPLETED')
      AND NOT EXISTS (SELECT 1 FROM gateway_usage_fact uf WHERE uf.org_id=gr.org_id AND uf.request_id=gr.id AND uf.status='FINAL'));`)[0]
  return {
    requests: Number(row[0]),
    multiAttemptRequests: Number(row[1]),
    duplicateFinalGroups: Number(row[2]),
    completedWithoutFinal: Number(row[3]),
    completedWithNonFinalCurrent: Number(row[4]),
  }
}

export function settlementSnapshot(orgIds) {
  return orgIds.map(orgId => {
    const row = mysqlRows(`SELECT
      (SELECT COUNT(*) FROM gateway_request WHERE org_id=${orgId}),
      (SELECT COUNT(*) FROM gateway_request WHERE org_id=${orgId}
        AND state IN ('COMPLETED','TRANSPORT_COMPLETED')),
      (SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=${orgId} AND status='FINAL'),
      (SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=${orgId} AND status<>'FINAL'),
      (SELECT COUNT(*) FROM gateway_settlement WHERE org_id=${orgId} AND status='SETTLED'),
      (SELECT COUNT(*) FROM gateway_settlement WHERE org_id=${orgId} AND status IN ('PENDING','RETRYABLE_FAILED')),
      (SELECT COUNT(*) FROM ledger_posting WHERE org_id=${orgId} AND source_type='GATEWAY_SETTLEMENT'),
      (SELECT COUNT(*) FROM ledger_entry le JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
        WHERE lp.org_id=${orgId} AND lp.source_type='GATEWAY_SETTLEMENT'),
      (SELECT COUNT(*) FROM gateway_settlement WHERE org_id=${orgId} AND status='SETTLED' AND ledger_posting_id IS NULL),
      (SELECT COUNT(*) FROM (SELECT posting_key FROM ledger_posting WHERE org_id=${orgId}
        GROUP BY posting_key HAVING COUNT(*)>1) d),
      (SELECT COUNT(*) FROM gateway_settlement gs JOIN gateway_usage_fact uf
        ON uf.id=gs.usage_fact_id AND uf.org_id=gs.org_id WHERE gs.org_id=${orgId} AND uf.status<>'FINAL'),
      (SELECT COUNT(*) FROM (SELECT source_id FROM ledger_posting WHERE org_id=${orgId}
        AND source_type='GATEWAY_SETTLEMENT' GROUP BY source_id HAVING COUNT(*)>1) d),
      (SELECT COUNT(*) FROM (SELECT le.posting_id FROM ledger_entry le JOIN ledger_posting lp
        ON lp.id=le.posting_id AND lp.org_id=le.org_id WHERE lp.org_id=${orgId}
        AND lp.source_type='GATEWAY_SETTLEMENT' GROUP BY le.posting_id HAVING COUNT(*)<>1) d),
      (SELECT COUNT(*) FROM ledger_posting WHERE org_id=${orgId} AND source_type='GATEWAY_SETTLEMENT'
        AND (posted_by_member_id IS NOT NULL AND posting_actor_type<>'SYSTEM')),
      (SELECT COUNT(*) FROM gateway_settlement WHERE org_id=${orgId} AND status='SETTLED'
        AND (posted_amount IS NULL OR posted_amount<=0))
    `)[0]
    return {
      orgId,
      requests: Number(row[0]),
      completedRequests: Number(row[1]),
      finalUsage: Number(row[2]),
      nonFinalUsage: Number(row[3]),
      settled: Number(row[4]),
      pending: Number(row[5]),
      ledgerPostings: Number(row[6]),
      ledgerEntries: Number(row[7]),
      settledWithoutPosting: Number(row[8]),
      duplicatePostingKeys: Number(row[9]),
      settledNonFinalUsage: Number(row[10]),
      duplicateSettlementSources: Number(row[11]),
      badEntryCardinality: Number(row[12]),
      nonSystemPostings: Number(row[13]),
      zeroAmountSettlements: Number(row[14]),
    }
  })
}

export function settlementViolations(snapshots) {
  const violations = []
  for (const snapshot of snapshots) {
    const where = `org ${snapshot.orgId}`
    if (snapshot.settledWithoutPosting !== 0) violations.push(`${where}: SETTLED without ledger_posting_id=${snapshot.settledWithoutPosting}`)
    if (snapshot.duplicatePostingKeys !== 0) violations.push(`${where}: duplicate posting_key groups=${snapshot.duplicatePostingKeys}`)
    if (snapshot.settledNonFinalUsage !== 0) violations.push(`${where}: settlement bound to non-FINAL usage=${snapshot.settledNonFinalUsage}`)
    if (snapshot.duplicateSettlementSources !== 0) violations.push(`${where}: duplicate settlement ledger sources=${snapshot.duplicateSettlementSources}`)
    if (snapshot.badEntryCardinality !== 0) violations.push(`${where}: ledger entry cardinality violations=${snapshot.badEntryCardinality}`)
    if (snapshot.nonSystemPostings !== 0) violations.push(`${where}: non-SYSTEM gateway postings=${snapshot.nonSystemPostings}`)
    if (snapshot.pending !== 0) violations.push(`${where}: unsettled PENDING/RETRYABLE=${snapshot.pending}`)
    if (snapshot.zeroAmountSettlements !== 0) violations.push(`${where}: SETTLED with non-positive amount=${snapshot.zeroAmountSettlements}`)
    if (snapshot.finalUsage !== snapshot.settled) violations.push(`${where}: FINAL usage=${snapshot.finalUsage} != SETTLED=${snapshot.settled}`)
    if (snapshot.completedRequests !== snapshot.finalUsage) violations.push(`${where}: COMPLETED=${snapshot.completedRequests} != FINAL usage=${snapshot.finalUsage}`)
  }
  return violations
}

export async function waitSettlementConverged(orgIds, { timeoutMs = 15 * 60_000, onTick } = {}) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    last = settlementSnapshot(orgIds)
    const pending = last.reduce((total, item) => total + item.pending, 0)
    const undrainedFinal = last.reduce((total, item) => total + Math.max(0, item.finalUsage - item.settled - item.pending), 0)
    if (onTick) onTick(last)
    if (pending === 0 && undrainedFinal === 0) return { converged: true, snapshots: last }
    await sleep(5000)
  }
  return { converged: false, snapshots: last }
}

export function topSqlDigests(limit = 12) {
  return mysqlRows(`SELECT DIGEST_TEXT, COUNT_STAR,
      ROUND(SUM_TIMER_WAIT/1000000000000,3) AS total_seconds,
      ROUND(AVG_TIMER_WAIT/1000000000,3) AS avg_ms,
      SUM_ROWS_EXAMINED, SUM_ROWS_AFFECTED, SUM_NO_INDEX_USED
    FROM performance_schema.events_statements_summary_by_digest
    WHERE SCHEMA_NAME='${DB_NAME}'
    ORDER BY SUM_TIMER_WAIT DESC LIMIT ${limit};`).map(row => ({
    digest: row[0],
    count: Number(row[1]),
    totalSeconds: Number(row[2]),
    avgMs: Number(row[3]),
    rowsExamined: Number(row[4]),
    rowsAffected: Number(row[5]),
    noIndexUsed: Number(row[6]),
  }))
}

export function explainKeyStatements(seedData) {
  const { orgId, periodId, budgetId, projectId } = seedData.hot
  const queries = {
    lockBillingPeriod: `EXPLAIN FORMAT=JSON SELECT status FROM billing_period WHERE id=${periodId} AND org_id=${orgId} FOR UPDATE`,
    selectBudgetByIdentity: `EXPLAIN FORMAT=JSON SELECT id,total_amount,actual_amount,committed_amount,status,currency
      FROM budget WHERE org_id=${orgId} AND billing_period_id=${periodId} AND scope_type='PROJECT'
      AND scope_id=${projectId} AND currency='USD'`,
    lockBudgetById: `EXPLAIN FORMAT=JSON SELECT id,total_amount,actual_amount,committed_amount,status
      FROM budget WHERE id=${budgetId} AND org_id=${orgId} FOR UPDATE`,
    sumEffectiveReservations: `EXPLAIN FORMAT=JSON SELECT COALESCE(SUM(reserved_amount),0)
      FROM budget_reservation WHERE org_id=${orgId} AND budget_id=${budgetId} AND status IN ('ACTIVE','PENDING_HOLD')`,
    findReservationByRouteAttempt: `EXPLAIN FORMAT=JSON SELECT id,status,reserved_amount,budget_id,billing_period_id
      FROM budget_reservation WHERE org_id=${orgId} AND route_attempt_id=0`,
    selectEligibleFinalCandidates: `EXPLAIN FORMAT=JSON SELECT gr.id FROM gateway_request gr
      JOIN gateway_usage_fact uf ON uf.id=gr.current_usage_fact_id AND uf.org_id=gr.org_id
      JOIN gateway_route_attempt ra ON ra.id=uf.route_attempt_id AND ra.org_id=uf.org_id
      LEFT JOIN budget_reservation br ON br.route_attempt_id=ra.id AND br.org_id=ra.org_id
      LEFT JOIN gateway_settlement gs ON gs.request_id=gr.id AND gs.org_id=gr.org_id
      WHERE gr.org_id=${orgId} AND uf.status='FINAL' AND gs.id IS NULL
      AND NOT EXISTS (SELECT 1 FROM gateway_financial_resolution gfr
        WHERE gfr.org_id=gr.org_id AND gfr.request_id=gr.id)
      ORDER BY gr.id ASC, uf.id ASC LIMIT 100`,
  }
  const result = {}
  for (const [name, sql] of Object.entries(queries)) {
    try {
      const rows = mysqlRows(sql)
      result[name] = rows.map(row => {
        try { return JSON.parse(row[0]) } catch { return row[0] }
      })[0] ?? null
    } catch (error) {
      result[name] = { error: error.message }
    }
  }
  return result
}

export function channelIsolation() {
  // Read-only confirmation that the harness used a dedicated database and
  // did not mutate the shared dev database.
  const row = mysqlRows(`SELECT DATABASE(), @@innodb_buffer_pool_size, @@max_connections,
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE());`)[0]
  return {
    database: row[0],
    innodbBufferPoolBytes: Number(row[1]),
    maxConnections: Number(row[2]),
    tables: Number(row[3]),
  }
}

export function gatewayConfigSnapshot() {
  const cfg = config()
  return {
    mysqlHost: `${cfg.mysqlHost}:${cfg.mysqlPort}`,
    redisHost: `${cfg.redisHost}:${cfg.redisPort}`,
    database: DB_NAME,
    mockProvider: `http://127.0.0.1:${cfg.mockPort}`,
  }
}
