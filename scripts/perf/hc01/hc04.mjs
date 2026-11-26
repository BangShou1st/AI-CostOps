import { DB_NAME, docker, mysql, mysqlNumber, mysqlRows, mysqlScalar, stats } from './lib.mjs'

const MYSQL_CONTAINER = 'ai-costops-hc04-mysql'

export function assertHc04Database(config) {
  if (DB_NAME !== 'aicostops_hc04' || config.mysqlContainer !== MYSQL_CONTAINER
      || config.mysqlHost !== '127.0.0.1') {
    throw new Error('HC-04 requires its dedicated local MySQL container and aicostops_hc04 database')
  }
  const [label, hostname] = docker([
    'inspect', '--format', '{{index .Config.Labels "io.aicostops.perf"}} {{.Config.Hostname}}',
    MYSQL_CONTAINER,
  ]).trim().split(' ')
  if (label !== 'hc04' || mysqlScalar('SELECT @@hostname', { database: null }) !== hostname) {
    throw new Error('HC-04 MySQL identity does not match the dedicated container')
  }
}

export function hasReservedCounter() {
  return mysqlNumber(`SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${DB_NAME}'
    AND table_name='budget' AND column_name='reserved_amount'`) === 1
}

export function reservedCounterDrift() {
  if (!hasReservedCounter()) return null
  return mysqlRows(`SELECT b.id, b.reserved_amount, COALESCE(h.amount,0)
    FROM budget b LEFT JOIN (
      SELECT org_id,budget_id,SUM(reserved_amount) AS amount FROM budget_reservation
      WHERE status IN ('ACTIVE','PENDING_HOLD') GROUP BY org_id,budget_id
    ) h ON h.org_id=b.org_id AND h.budget_id=b.id
    WHERE b.reserved_amount<>COALESCE(h.amount,0)`)
}

export function resetHc04Digests() {
  mysql('TRUNCATE TABLE performance_schema.events_statements_summary_by_digest', { database: null })
}

export function snapshotPreparedStatements() {
  const rows = mysqlRows(`SELECT
      TRIM(REGEXP_REPLACE(SQL_TEXT, '[[:space:]]+', ' ')) AS sql_text,
      SUM(COUNT_EXECUTE) AS cnt,
      SUM(SUM_TIMER_EXECUTE) AS timer_ps,
      SUM(SUM_LOCK_TIME) AS lock_ps,
      SUM(SUM_ROWS_AFFECTED) AS rows_affected,
      SUM(SUM_ROWS_EXAMINED) AS rows_examined,
      SUM(SUM_NO_INDEX_USED) AS no_index_used
    FROM performance_schema.prepared_statements_instances
    WHERE SQL_TEXT NOT LIKE '%performance_schema%'
      AND SQL_TEXT NOT LIKE '%information_schema%'
    GROUP BY sql_text`, { database: null })
  const map = {}
  for (const row of rows) {
    map[row[0]] = {
      count: Number(row[1]),
      timerPs: Number(row[2]),
      lockPs: Number(row[3]),
      rowsAffected: Number(row[4]),
      rowsExamined: Number(row[5]),
      noIndexUsed: Number(row[6]),
    }
  }
  return map
}

export function preparedStatementsDelta(before, after) {
  const items = []
  let resetDetected = false
  for (const [sql, cur] of Object.entries(after ?? {})) {
    const prev = before?.[sql]
    const prevCount = prev?.count ?? 0
    if (cur.count < prevCount) {
      resetDetected = true
      continue
    }
    const count = cur.count - prevCount
    if (count <= 0) continue
    items.push({
      sql,
      source: 'prepared',
      count,
      timerPs: Math.max(0, cur.timerPs - (prev?.timerPs ?? 0)),
      lockPs: Math.max(0, cur.lockPs - (prev?.lockPs ?? 0)),
      rowsAffected: Math.max(0, cur.rowsAffected - (prev?.rowsAffected ?? 0)),
      rowsExamined: Math.max(0, cur.rowsExamined - (prev?.rowsExamined ?? 0)),
      noIndexUsed: Math.max(0, cur.noIndexUsed - (prev?.noIndexUsed ?? 0)),
    })
  }
  return { items, resetDetected }
}

export function roundSql(successCount, preparedDelta = null, queriesDelta = null) {
  const rows = mysqlRows(`SELECT
      TRIM(REGEXP_REPLACE(DIGEST_TEXT, '[[:space:]]+', ' ')) AS sql_text,
      COUNT_STAR, SUM_TIMER_WAIT, SUM_LOCK_TIME,
      SUM_ROWS_AFFECTED, SUM_ROWS_EXAMINED, SUM_NO_INDEX_USED
    FROM performance_schema.events_statements_summary_by_digest
    WHERE SCHEMA_NAME='${DB_NAME}' AND DIGEST_TEXT IS NOT NULL
      AND DIGEST_TEXT NOT LIKE '%performance_schema%'
      AND DIGEST_TEXT NOT LIKE '%information_schema%'`, { database: null })
  const digestItems = rows.map(row => ({
    sql: row[0],
    source: 'digest',
    count: Number(row[1]),
    timerPs: Number(row[2]),
    lockPs: Number(row[3]),
    rowsAffected: Number(row[4]),
    rowsExamined: Number(row[5]),
    noIndexUsed: Number(row[6]),
  }))
  const preparedItems = preparedDelta?.items ?? []
  const allItems = [...digestItems, ...preparedItems]
  const sumCount = items => items.reduce((total, item) => total + item.count, 0)
  const sumField = (items, field) => items.reduce((total, item) => total + item[field], 0)
  const isBudgetAdmission = item =>
    /^(UPDATE `?budget`? SET .*reserved_amount|SELECT .* FROM `?budget`? .*FOR UPDATE)/i.test(item.sql)
  const isBillingPeriodLock = item =>
    /FROM `?billing_period`? .*FOR (SHARE|UPDATE)/i.test(item.sql)
  const budgetAdmission = allItems.filter(isBudgetAdmission)
  const periodLocks = allItems.filter(isBillingPeriodLock)
  const budgetStatements = sumCount(budgetAdmission)
  const periodLockStatements = sumCount(periodLocks)
  const digestStatementCount = sumCount(digestItems)
  const preparedStatementCount = sumCount(preparedItems)
  const statementCount = digestStatementCount + preparedStatementCount
  const sessionReadOnlyCount = sumCount(allItems.filter(item => /transaction_read_only/i.test(item.sql)))
  const round3 = v => Math.round(v * 1000) / 1000
  const topStatements = [...allItems]
    .sort((a, b) => b.timerPs - a.timerPs || b.count - a.count)
    .slice(0, 30)
    .map(item => ({
      sql: item.sql,
      source: item.source,
      count: item.count,
      perSuccess: successCount > 0 ? round3(item.count / successCount) : null,
      totalMs: round3(item.timerPs / 1e9),
      meanMs: item.count > 0 ? round3(item.timerPs / item.count / 1e9) : null,
      lockMeanMs: item.count > 0 ? round3(item.lockPs / item.count / 1e9) : null,
      rowsAffected: item.rowsAffected,
      rowsExamined: item.rowsExamined,
      noIndexUsed: item.noIndexUsed,
    }))
  return {
    statementCount,
    digestStatementCount,
    preparedStatementCount,
    preparedResetDetected: Boolean(preparedDelta?.resetDetected),
    statementsPerSuccess: successCount > 0 ? statementCount / successCount : null,
    queriesDelta,
    queriesPerSuccess: successCount > 0 && queriesDelta !== null ? queriesDelta / successCount : null,
    sessionReadOnlyCount,
    budgetStatements,
    budgetStatementTotalMs: budgetStatements > 0 ? round3(sumField(budgetAdmission, 'timerPs') / 1e9) : null,
    budgetStatementMeanMs: budgetStatements > 0 ? sumField(budgetAdmission, 'timerPs') / budgetStatements / 1e9 : null,
    budgetStatementLockMeanMs: budgetStatements > 0 ? sumField(budgetAdmission, 'lockPs') / budgetStatements / 1e9 : null,
    billingPeriodLockStatements: periodLockStatements,
    billingPeriodLockMeanMs: periodLockStatements > 0 ? sumField(periodLocks, 'timerPs') / periodLockStatements / 1e9 : null,
    topStatements,
  }
}

export function autocommitDrift(current, previous) {
  if (!current || !previous) return null
  const p50Ratio = previous.p50 > 0 ? Math.abs(current.p50 - previous.p50) / previous.p50 : 0
  const meanRatio = previous.mean > 0 ? Math.abs(current.mean - previous.mean) / previous.mean : 0
  return {
    p50Ratio: Math.round(p50Ratio * 10000) / 10000,
    meanRatio: Math.round(meanRatio * 10000) / 10000,
    maxRatio: Math.round(Math.max(p50Ratio, meanRatio) * 10000) / 10000,
  }
}

export function measureAutocommit() {
  const count = 1_000
  const output = mysqlRows(`
    CREATE TABLE hc04_durable_probe (id INT PRIMARY KEY) ENGINE=InnoDB;
    CREATE TEMPORARY TABLE hc04_commit_samples (elapsed_us BIGINT) ENGINE=MEMORY;
    DELIMITER //
    CREATE PROCEDURE hc04_probe_autocommit()
    BEGIN
      DECLARE i INT DEFAULT 1;
      DECLARE started DATETIME(6);
      WHILE i <= ${count} DO
        SET started = SYSDATE(6);
        INSERT INTO hc04_durable_probe VALUES (i);
        INSERT INTO hc04_commit_samples VALUES (TIMESTAMPDIFF(MICROSECOND,started,SYSDATE(6)));
        SET i = i + 1;
      END WHILE;
    END//
    DELIMITER ;
    SET autocommit=1;
    CALL hc04_probe_autocommit();
    SELECT elapsed_us FROM hc04_commit_samples;
    DROP PROCEDURE hc04_probe_autocommit;
    DROP TABLE hc04_durable_probe;`, { timeout: 300_000 })
  const samples = output.map(([value]) => Number(value) / 1_000)
  if (samples.length !== count || samples.some(value => !Number.isFinite(value) || value <= 0)) {
    throw new Error('Invalid HC-04 autocommit sample set')
  }
  return stats(samples)
}
