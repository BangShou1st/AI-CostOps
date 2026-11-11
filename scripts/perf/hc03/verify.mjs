/**
 * HC-03 financial invariant probes.
 *
 * The oracles are always the durable MySQL facts (requests, route attempts,
 * reservations, usage facts, settlements, ledger postings, budget Actual) plus
 * the controlled Mock Provider call counter. A process-kill "result" is never
 * accepted as evidence on its own.
 */
import { decimalToScaled, scaledToDecimal, sleep } from './lib.mjs'
import { sqlNumber, sqlRows } from './lib.mjs'

const POSSIBLY_DISPATCHED = `('DISPATCH_INTENT','BILLABLE_POSSIBLE','COMPLETED')`

function counts(rows) {
  return Object.fromEntries(rows.map(([key, value]) => [key, Number(value)]))
}

function sumCounts(object) {
  return Object.values(object).reduce((total, value) => total + value, 0)
}

export function budgetSnapshot(fixture) {
  const row = sqlRows(`SELECT b.id, b.total_amount, b.actual_amount, b.committed_amount,
      COALESCE((SELECT SUM(r.reserved_amount) FROM budget_reservation r
        WHERE r.budget_id=b.id AND r.status IN ('ACTIVE','PENDING_HOLD')),0),
      (SELECT COUNT(*) FROM budget_reservation r WHERE r.budget_id=b.id AND r.status='ACTIVE'),
      (SELECT COUNT(*) FROM budget_reservation r WHERE r.budget_id=b.id AND r.status='PENDING_HOLD'),
      (SELECT COUNT(*) FROM budget_reservation r WHERE r.budget_id=b.id AND r.status='SETTLED')
      FROM budget b WHERE b.id=${fixture.budgetId} AND b.org_id=${fixture.orgId}`)[0]
  if (!row) throw new Error(`budget ${fixture.budgetId} not found`)
  const total = decimalToScaled(row[1])
  const actual = decimalToScaled(row[2])
  const committed = decimalToScaled(row[3])
  const holds = decimalToScaled(row[4])
  const used = actual + committed + holds
  return {
    budgetId: Number(row[0]),
    total: row[1],
    actual: row[2],
    committed: row[3],
    effectiveHolds: row[4],
    used: scaledToDecimal(used),
    available: scaledToDecimal(total - used),
    activeHolds: Number(row[5]),
    pendingHolds: Number(row[6]),
    settledHolds: Number(row[7]),
    overspent: used > total,
  }
}

export function fixtureSnapshot(fixture) {
  const { orgId, projectId, budgetId } = fixture
  const requestStates = counts(sqlRows(`SELECT state, COUNT(*) FROM gateway_request
    WHERE org_id=${orgId} AND project_id=${projectId} GROUP BY state`))
  const attemptStatuses = counts(sqlRows(`SELECT ra.status, COUNT(*) FROM gateway_route_attempt ra
    JOIN gateway_request gr ON gr.id=ra.request_id AND gr.org_id=ra.org_id
    WHERE gr.org_id=${orgId} AND gr.project_id=${projectId} GROUP BY ra.status`))
  const possiblyDispatched = sqlNumber(`SELECT COUNT(DISTINCT gr.id) FROM gateway_request gr
    JOIN gateway_route_attempt ra ON ra.request_id=gr.id AND ra.org_id=gr.org_id
    WHERE gr.org_id=${orgId} AND gr.project_id=${projectId}
      AND ra.status IN ${POSSIBLY_DISPATCHED}`)
  const multiAttempt = sqlNumber(`SELECT COUNT(*) FROM (
      SELECT request_id FROM gateway_route_attempt WHERE org_id=${orgId}
      GROUP BY request_id HAVING COUNT(*) > 1) d`)
  const reservationStatuses = counts(sqlRows(`SELECT status, COUNT(*) FROM budget_reservation
    WHERE org_id=${orgId} AND budget_id=${budgetId} GROUP BY status`))
  const usageStatuses = counts(sqlRows(`SELECT uf.status, COUNT(*) FROM gateway_usage_fact uf
    JOIN gateway_request gr ON gr.id=uf.request_id AND gr.org_id=uf.org_id
    WHERE gr.org_id=${orgId} AND gr.project_id=${projectId} GROUP BY uf.status`))
  const settlementStatuses = counts(sqlRows(`SELECT gs.status, COUNT(*) FROM gateway_settlement gs
    JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
    WHERE gr.org_id=${orgId} AND gr.project_id=${projectId} GROUP BY gs.status`))
  const ledger = sqlRows(`SELECT
      (SELECT COUNT(*) FROM ledger_posting lp
        JOIN gateway_settlement gs ON gs.id=lp.source_id AND gs.org_id=lp.org_id
        JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
        WHERE lp.source_type='GATEWAY_SETTLEMENT' AND gr.org_id=${orgId} AND gr.project_id=${projectId}),
      (SELECT COUNT(*) FROM ledger_entry le
        JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
        JOIN gateway_settlement gs ON gs.id=lp.source_id AND gs.org_id=lp.org_id
        JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
        WHERE lp.source_type='GATEWAY_SETTLEMENT' AND gr.org_id=${orgId} AND gr.project_id=${projectId}),
      (SELECT COUNT(*) FROM (
        SELECT le.posting_id FROM ledger_entry le
          JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
          JOIN gateway_settlement gs ON gs.id=lp.source_id AND gs.org_id=lp.org_id
          JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
          WHERE lp.source_type='GATEWAY_SETTLEMENT' AND gr.org_id=${orgId} AND gr.project_id=${projectId}
          GROUP BY le.posting_id HAVING COUNT(*) <> 1) d),
      (SELECT COUNT(*) FROM (
        SELECT lp.posting_key FROM ledger_posting lp
          JOIN gateway_settlement gs ON gs.id=lp.source_id AND gs.org_id=lp.org_id
          JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
          WHERE lp.source_type='GATEWAY_SETTLEMENT' AND gr.org_id=${orgId} AND gr.project_id=${projectId}
          GROUP BY lp.posting_key HAVING COUNT(*) > 1) d),
      (SELECT COUNT(*) FROM (
        SELECT lp.source_id FROM ledger_posting lp
          JOIN gateway_settlement gs ON gs.id=lp.source_id AND gs.org_id=lp.org_id
          JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
          WHERE lp.source_type='GATEWAY_SETTLEMENT' AND gr.org_id=${orgId} AND gr.project_id=${projectId}
          GROUP BY lp.source_id HAVING COUNT(*) > 1) d),
      (SELECT COUNT(*) FROM ledger_posting lp
        JOIN gateway_settlement gs ON gs.id=lp.source_id AND gs.org_id=lp.org_id
        JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
        WHERE lp.source_type='GATEWAY_SETTLEMENT' AND gr.org_id=${orgId} AND gr.project_id=${projectId}
          AND (lp.posted_by_member_id IS NOT NULL AND lp.posting_actor_type <> 'SYSTEM')),
      (SELECT COUNT(*) FROM gateway_usage_fact uf
        JOIN gateway_request gr ON gr.id=uf.request_id AND gr.org_id=uf.org_id
        WHERE gr.org_id=${orgId} AND gr.project_id=${projectId} AND uf.status='FINAL'
          AND NOT EXISTS (SELECT 1 FROM gateway_settlement gs
            WHERE gs.usage_fact_id=uf.id AND gs.org_id=uf.org_id)),
      (SELECT COUNT(*) FROM gateway_settlement gs
        JOIN gateway_usage_fact uf ON uf.id=gs.usage_fact_id AND uf.org_id=gs.org_id
        JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
        WHERE gr.org_id=${orgId} AND gr.project_id=${projectId} AND uf.status <> 'FINAL'),
      (SELECT COUNT(*) FROM gateway_settlement gs
        JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
        WHERE gr.org_id=${orgId} AND gr.project_id=${projectId} AND gs.status='SETTLED'
          AND (gs.posted_amount IS NULL OR gs.posted_amount <= 0 OR gs.ledger_posting_id IS NULL))`)[0]
  const budget = budgetSnapshot(fixture)
  const state = {
    projectId,
    requests: { total: sumCounts(requestStates), states: requestStates, possiblyDispatched, multiAttempt },
    attempts: { total: sumCounts(attemptStatuses), statuses: attemptStatuses },
    reservations: { total: sumCounts(reservationStatuses), statuses: reservationStatuses },
    usage: { total: sumCounts(usageStatuses), statuses: usageStatuses },
    settlements: { total: sumCounts(settlementStatuses), statuses: settlementStatuses },
    ledger: {
      postings: Number(ledger[0]),
      entries: Number(ledger[1]),
      badEntryCardinality: Number(ledger[2]),
      duplicatePostingKeys: Number(ledger[3]),
      duplicateSources: Number(ledger[4]),
      nonSystemPostings: Number(ledger[5]),
      finalWithoutSettlement: Number(ledger[6]),
      settledNonFinalUsage: Number(ledger[7]),
      settledWithoutPosting: Number(ledger[8]),
    },
    budget,
  }
  state.logicalPostingOk = state.ledger.postings === (state.settlements.statuses.SETTLED ?? 0)
  return state
}

export function requestDetail(projectId) {
  const rows = sqlRows(`SELECT id, state, billing_period_id, dispatch_intent_at, created_at
    FROM gateway_request WHERE project_id=${projectId} ORDER BY id DESC LIMIT 1`)
  if (!rows.length) return null
  const [id, state, periodId, dispatchIntentAt, createdAt] = rows[0]
  const attempts = sqlRows(`SELECT id, attempt_no, status, safety_reason_code, dispatch_intent_at
    FROM gateway_route_attempt WHERE request_id=${id} ORDER BY attempt_no`)
    .map(([attemptId, attemptNo, status, reason, intentAt]) => ({
      id: Number(attemptId), attemptNo: Number(attemptNo), status, reason, dispatchIntentAt: intentAt,
    }))
  const reservations = sqlRows(`SELECT id, status, reserved_amount, expires_at, version
    FROM budget_reservation WHERE request_id=${id}`)
    .map(([reservationId, status, amount, expiresAt, version]) => ({
      id: Number(reservationId), status, amount, expiresAt, version: Number(version),
    }))
  const usage = sqlRows(`SELECT id, status, sequence FROM gateway_usage_fact
    WHERE request_id=${id} ORDER BY sequence DESC`).map(([usageId, status, sequence]) => ({
    id: Number(usageId), status, sequence: Number(sequence),
  }))
  const settlements = sqlRows(`SELECT id, status, attempt_count, last_error_code, ledger_posting_id
    FROM gateway_settlement WHERE request_id=${id}`).map(([settlementId, status, attemptCount, error, posting]) => ({
    id: Number(settlementId), status, attemptCount: Number(attemptCount), lastErrorCode: error,
    ledgerPostingId: posting === 'NULL' ? null : Number(posting),
  }))
  return { id: Number(id), state, periodId: Number(periodId), dispatchIntentAt, createdAt, attempts, reservations, usage, settlements }
}

export function requestById(requestId) {
  const rows = sqlRows(`SELECT id, state, project_id FROM gateway_request WHERE id=${requestId}`)
  if (!rows.length) return null
  const detail = requestDetail(Number(rows[0][2]))
  return detail?.id === Number(requestId) ? detail : null
}

export function periodSnapshot(periodId) {
  const row = sqlRows(`SELECT status, close_generation, version, closing_started_at, closed_at
    FROM billing_period WHERE id=${periodId}`)[0]
  if (!row) return null
  const runs = sqlRows(`SELECT id, status, attempt_no, error_code FROM period_close_run
    WHERE billing_period_id=${periodId} ORDER BY id DESC`).map(([id, status, attemptNo, error]) => ({
    id: Number(id), status, attemptNo: Number(attemptNo), error,
  }))
  const checks = sqlRows(`SELECT blocker_code, result, item_count FROM period_close_check
    WHERE period_close_run_id IN (SELECT id FROM period_close_run WHERE billing_period_id=${periodId})
    ORDER BY id`).map(([code, result, items]) => ({ code, result, items: Number(items) }))
  return {
    periodId,
    status: row[0],
    closeGeneration: Number(row[1]),
    version: Number(row[2]),
    closingStartedAt: row[3],
    closedAt: row[4],
    runs,
    checks,
  }
}

export function orgLedgerSnapshot(orgId) {
  const row = sqlRows(`SELECT
      (SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=${orgId} AND status='FINAL'),
      (SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=${orgId} AND status<>'FINAL'),
      (SELECT COUNT(*) FROM gateway_settlement WHERE org_id=${orgId} AND status='SETTLED'),
      (SELECT COUNT(*) FROM gateway_settlement WHERE org_id=${orgId} AND status IN ('PENDING','RETRYABLE_FAILED')),
      (SELECT COUNT(*) FROM gateway_settlement WHERE org_id=${orgId} AND status='RECONCILIATION_REQUIRED'),
      (SELECT COUNT(*) FROM ledger_posting WHERE org_id=${orgId} AND source_type='GATEWAY_SETTLEMENT'),
      (SELECT COUNT(*) FROM (
        SELECT posting_key FROM ledger_posting WHERE org_id=${orgId} GROUP BY posting_key HAVING COUNT(*)>1) d),
      (SELECT COUNT(*) FROM gateway_settlement gs JOIN gateway_usage_fact uf
        ON uf.id=gs.usage_fact_id AND uf.org_id=gs.org_id
        WHERE gs.org_id=${orgId} AND uf.status<>'FINAL'),
      (SELECT COUNT(*) FROM gateway_settlement WHERE org_id=${orgId} AND status='SETTLED'
        AND ledger_posting_id IS NULL),
      (SELECT COUNT(*) FROM (
        SELECT source_id FROM ledger_posting WHERE org_id=${orgId}
          AND source_type='GATEWAY_SETTLEMENT' GROUP BY source_id HAVING COUNT(*)>1) d),
      (SELECT COUNT(*) FROM budget WHERE org_id=${orgId}
        AND actual_amount + committed_amount + COALESCE((SELECT SUM(r.reserved_amount)
          FROM budget_reservation r WHERE r.budget_id=budget.id AND r.status IN ('ACTIVE','PENDING_HOLD')),0)
          > total_amount)`)[0]
  return {
    orgId,
    finalUsage: Number(row[0]),
    nonFinalUsage: Number(row[1]),
    settled: Number(row[2]),
    pendingSettlements: Number(row[3]),
    reconciliationRequired: Number(row[4]),
    postings: Number(row[5]),
    duplicatePostingKeys: Number(row[6]),
    settledNonFinalUsage: Number(row[7]),
    settledWithoutPosting: Number(row[8]),
    duplicateSources: Number(row[9]),
    overspentBudgets: Number(row[10]),
  }
}

export function ledgerAmountByProject(projectId) {
  return sqlRows(`SELECT lp.id, lp.posting_key, le.amount
    FROM ledger_posting lp
    JOIN ledger_entry le ON le.posting_id=lp.id AND le.org_id=lp.org_id
    JOIN gateway_settlement gs ON gs.id=lp.source_id AND gs.org_id=lp.org_id
    JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
    WHERE lp.source_type='GATEWAY_SETTLEMENT' AND gr.project_id=${projectId}
    ORDER BY lp.id`).map(([id, key, amount]) => ({ id: Number(id), postingKey: key, amount }))
}

export function mysqlGlobalCounters() {
  return Object.fromEntries(sqlRows(`SHOW GLOBAL STATUS WHERE Variable_name IN
    ('Innodb_deadlocks','Innodb_row_lock_waits','Innodb_row_lock_time','Threads_connected',
     'Aborted_connects','Max_used_connections','Innodb_row_lock_current_waits')`)
    .map(([name, value]) => [name, Number(value)]))
}

export function violations(snapshot, label) {
  const out = []
  const where = label ?? `project ${snapshot.projectId}`
  if (snapshot.budget.overspent) out.push(`${where}: budget overspent (used ${snapshot.budget.used} > ${snapshot.budget.total})`)
  if (snapshot.ledger.badEntryCardinality !== 0) out.push(`${where}: ledger entry cardinality violations=${snapshot.ledger.badEntryCardinality}`)
  if (snapshot.ledger.duplicatePostingKeys !== 0) out.push(`${where}: duplicate posting_key groups=${snapshot.ledger.duplicatePostingKeys}`)
  if (snapshot.ledger.duplicateSources !== 0) out.push(`${where}: duplicate settlement ledger sources=${snapshot.ledger.duplicateSources}`)
  if (snapshot.ledger.nonSystemPostings !== 0) out.push(`${where}: non-SYSTEM gateway postings=${snapshot.ledger.nonSystemPostings}`)
  if (snapshot.ledger.settledNonFinalUsage !== 0) out.push(`${where}: settlement bound to non-FINAL usage=${snapshot.ledger.settledNonFinalUsage}`)
  if (snapshot.ledger.settledWithoutPosting !== 0) out.push(`${where}: SETTLED without ledger posting=${snapshot.ledger.settledWithoutPosting}`)
  if ((snapshot.usage.statuses.FINAL ?? 0) > (snapshot.settlements.statuses.SETTLED ?? 0)
      && (snapshot.settlements.statuses.PENDING ?? 0) === 0
      && (snapshot.settlements.statuses.RETRYABLE_FAILED ?? 0) === 0) {
    out.push(`${where}: FINAL usage=${snapshot.usage.statuses.FINAL ?? 0} > SETTLED=${snapshot.settlements.statuses.SETTLED ?? 0} with no pending settlement`)
  }
  if ((snapshot.settlements.statuses.SETTLED ?? 0) > (snapshot.usage.statuses.FINAL ?? 0)) {
    out.push(`${where}: SETTLED=${snapshot.settlements.statuses.SETTLED ?? 0} > FINAL usage=${snapshot.usage.statuses.FINAL ?? 0}`)
  }
  return out
}

export async function waitFor(predicate, label, timeoutMs, intervalMs = 500) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    last = await predicate()
    if (last) return last
    await sleep(intervalMs)
  }
  throw new Error(`等待超时: ${label} (${timeoutMs}ms); last=${JSON.stringify(last)}`)
}

export async function waitForSettlement(fixture, expectedSettled, timeoutMs = 120_000) {
  return waitFor(() => {
    const snapshot = fixtureSnapshot(fixture)
    const settled = snapshot.settlements.statuses.SETTLED ?? 0
    return settled >= expectedSettled ? snapshot : null
  }, `settlement settle>=${expectedSettled} for project ${fixture.projectId}`, timeoutMs, 1000)
}
