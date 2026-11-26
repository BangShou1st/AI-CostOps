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

export function roundSql(successCount) {
  const rows = mysqlRows(`SELECT DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT
    FROM performance_schema.events_statements_summary_by_digest
    WHERE SCHEMA_NAME='${DB_NAME}' AND DIGEST_TEXT IS NOT NULL
      AND DIGEST_TEXT NOT LIKE '%performance_schema%'
      AND DIGEST_TEXT NOT LIKE '%information_schema%'`, { database: null })
  const count = items => items.reduce((total, row) => total + Number(row[1]), 0)
  const isBudgetAdmission = ([sql]) => /^(UPDATE `?budget`? SET .*reserved_amount|SELECT .* FROM `?budget`? .*FOR UPDATE)/i.test(sql)
  const budgetAdmission = rows.filter(isBudgetAdmission)
  const budgetStatements = count(budgetAdmission)
  const statementCount = count(rows)
  const sessionReadOnlyCount = count(rows.filter(([sql]) => /transaction_read_only/i.test(sql)))
  return {
    statementCount,
    statementsPerSuccess: successCount > 0 ? statementCount / successCount : null,
    sessionReadOnlyCount,
    budgetStatements,
    budgetStatementMeanMs: budgetStatements > 0
      ? budgetAdmission.reduce((total, row) => total + Number(row[2]), 0) / budgetStatements / 1e9 : null,
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
