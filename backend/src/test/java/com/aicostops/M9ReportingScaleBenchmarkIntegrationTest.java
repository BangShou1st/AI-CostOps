package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.reporting.application.WorkbenchQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MinioAuthenticationContainersSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M9 reporting/read-model scale benchmark over a large realistic fact set.
 *
 * <p>Fixtures are inserted before the timed section via batched JDBC. The timed
 * section runs the REAL WorkbenchQueryService (as the /api/v1/workbench endpoint
 * does), and the dominant SQL statements are captured through real MySQL
 * EXPLAIN ANALYZE (never hand-written pseudo-explain).
 *
 * <p>The default CI run is a lightweight correctness sample (10k charges).
 * Larger volumes are opt-in:
 *
 * <pre>
 * mvnw.cmd -B "-Dm9.reporting.rows=200000" failsafe:integration-test "-Dit.test=M9ReportingScaleBenchmarkIntegrationTest"
 * </pre>
 */
@SpringBootTest
@Tag("integration")
@Tag("benchmark")
class M9ReportingScaleBenchmarkIntegrationTest extends MinioAuthenticationContainersSupport {

    private static final String ROLE_CODE = "M9_REPORT_BENCHMARK";
    private static final String PERIOD_START = "2026-09-01 00:00:00.000000";
    private static final String PERIOD_END = "2026-10-01 00:00:00.000000";
    private static final BigDecimal AMOUNT_PER_CHARGE = new BigDecimal("1.25000000");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private WorkbenchQueryService workbench;

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long periodId;

    @BeforeEach
    void setUp() {
        resetDatabase();
    }

    @AfterEach
    void tearDown() {
        clearDatabase();
    }

    @Test
    void runsWorkbenchScaleBenchmarkWithRealExplainAnalyze() {
        var rows = Integer.parseInt(System.getProperty("m9.reporting.rows", "10000"));
        assertThat(rows).as("m9.reporting.rows").isPositive().isLessThanOrEqualTo(500_000);

        // Fixture is outside the timed section.
        var fixtureStart = System.nanoTime();
        seedReportingFixture(rows);
        var fixtureMillis = elapsedMillis(fixtureStart);
        var user = new AuthenticatedUser(actorUserId, 7);

        // Warm-up read (fills caches, warm pools), then flushes Redis so the
        // measured run hits the SQL again.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        var view = workbench.get(user, periodId);
        assertThat(view.period()).isNotNull();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        var start = System.nanoTime();
        var measured = workbench.get(user, periodId);
        var totalMillis = elapsedMillis(start);

        // Correctness at this scale. The fixture folds the remainder row into the
        // last provider, so only the TOTAL amount is exact for every group size.
        assertThat(measured.costByProvider()).hasSize(3);
        var expectedTotal = AMOUNT_PER_CHARGE.multiply(BigDecimal.valueOf(rows));
        var sum = measured.costByProvider().stream()
                .map(line -> new BigDecimal(line.totalAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(expectedTotal);
        assertThat(measured.costByProject()).isNotEmpty();
        assertThat(new BigDecimal(measured.costByProject().getFirst().totalAmount()))
                .isEqualByComparingTo(expectedTotal);
        assertThat(measured.unallocatedCharges()).isEmpty();
        assertThat(measured.budgetVariance()).hasSize(1);
        assertThat(measured.period().status()).isEqualTo("OPEN");
        assertThat(measured.openReconciliations()).isNotNull();
        assertThat(measured.pendingApprovals()).isNull();

        System.out.printf(Locale.ROOT,
                "M9_REPORTING_BENCHMARK|measured|rows=%d|fixture_ms=%d|workbench_ms=%d|providers=%d|projects=%d|charges_sum=%s|period=%s%n",
                rows, fixtureMillis, totalMillis, measured.costByProvider().size(),
                measured.costByProject().size(), sum.toPlainString(), measured.period().status());

        // Real EXPLAIN ANALYZE for the dominant SQL (verbatim copies of the
        // statements in WorkbenchQueryMapper, executed against the same schema).
        var orgId = Long.toString(organizationId);
        var periodStart = "2026-09-01 00:00:00.000000";
        var periodEnd = "2026-10-01 00:00:00.000000";
        var limit = "100";

        explain("workbench_provider_cost", """
                SELECT cf.provider_code AS providerCode, cf.currency AS currency,
                       SUM(cf.amount) AS totalAmount, COUNT(*) AS chargeCount
                FROM charge_fact cf
                JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
                JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
                JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
                WHERE cf.org_id=%s
                  AND ib.status='CONFIRMED'
                  AND ib.confirmed_attempt_id=ia.id
                  AND cf.review_status='CLEAN'
                  AND cf.period_start >= '%s'
                  AND cf.period_start < '%s'
                GROUP BY cf.provider_code, cf.currency
                ORDER BY SUM(cf.amount) DESC, cf.provider_code ASC
                LIMIT %s
                """.formatted(orgId, periodStart, periodEnd, limit));

        explain("workbench_project_cost", """
                SELECT al.project_id AS projectId, p.name AS projectName,
                       al.currency AS currency, SUM(al.allocated_amount) AS totalAmount
                FROM allocation_line al
                JOIN allocation_decision ad
                  ON ad.id=al.decision_id AND ad.org_id=al.org_id
                JOIN charge_fact cf
                  ON cf.id=ad.charge_fact_id AND cf.org_id=ad.org_id
                JOIN project p
                  ON p.id=al.project_id AND p.org_id=al.org_id
                WHERE al.org_id=%s
                  AND ad.status='CONFIRMED' AND ad.subject_type='CHARGE_FACT'
                  AND al.project_id IS NOT NULL
                  AND cf.period_start >= '%s'
                  AND cf.period_start < '%s'
                GROUP BY al.project_id, p.name, al.currency
                ORDER BY SUM(al.allocated_amount) DESC, al.project_id ASC
                LIMIT %s
                """.formatted(orgId, periodStart, periodEnd, limit));

        explain("workbench_unallocated_currency", """
                SELECT cf.currency AS currency, SUM(cf.amount) AS amount,
                       COUNT(*) AS chargeCount
                FROM charge_fact cf
                LEFT JOIN allocation_decision ad
                  ON ad.id=cf.current_allocation_decision_id AND ad.org_id=cf.org_id
                JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
                JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
                JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
                WHERE cf.org_id=%s
                  AND ib.status='CONFIRMED'
                  AND ib.confirmed_attempt_id=ia.id
                  AND cf.review_status='CLEAN'
                  AND cf.period_start >= '%s'
                  AND cf.period_start < '%s'
                  AND (cf.current_allocation_decision_id IS NULL OR ad.status <> 'CONFIRMED')
                GROUP BY cf.currency
                ORDER BY SUM(cf.amount) DESC
                """.formatted(orgId, periodStart, periodEnd));
    }

    private void explain(String name, String sql) {
        var started = System.nanoTime();
        var rows = jdbc.queryForList("EXPLAIN ANALYZE " + sql);
        var millis = elapsedMillis(started);
        var plan = rows.isEmpty() ? "(empty)" : String.valueOf(rows.getFirst().values().iterator().next());
        System.out.println("M9_REPORTING_EXPLAIN|" + name + "|ms=" + millis + "|plan=" + plan);
    }

    private void seedReportingFixture(int charges) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES ('M9 Reporting Benchmark',?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "m9-reporting-" + System.nanoTime());
        organizationId = jdbc.queryForObject("SELECT MAX(id) FROM organization", Long.class);
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, PERIOD_START, PERIOD_END);
        periodId = jdbc.queryForObject("SELECT MAX(id) FROM billing_period", Long.class);
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?, 'M9 Reporting', 'ACTIVE', 7, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "m9-reporting-" + System.nanoTime() + "@example.com");
        actorUserId = jdbc.queryForObject("SELECT MAX(id) FROM app_user", Long.class);
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, organizationId, actorUserId);
        actorMemberId = jdbc.queryForObject(
                "SELECT MAX(id) FROM organization_member WHERE org_id=?", Long.class, organizationId);
        jdbc.update("""
                INSERT INTO evidence(org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,created_at,updated_at)
                VALUES (?,REPEAT('a',64),'/bench/reporting.zip','reporting.zip','application/zip',100,
                    ?,'AVAILABLE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, actorMemberId);
        var evidenceId = jdbc.queryForObject("SELECT MAX(id) FROM evidence", Long.class);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,external_account_ref,status,
                    metadata_json,created_at,updated_at)
                VALUES (?,'DEEPSEEK','Reporting Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId);
        var providerAccountId = jdbc.queryForObject("SELECT MAX(id) FROM provider_account", Long.class);
        jdbc.update("""
                INSERT INTO import_batch(org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?, 'DEEPSEEK','FILE_EXPORT','bench.v1','CONFIRMED',?,?,
                    ?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, evidenceId, providerAccountId, PERIOD_START, PERIOD_END, actorMemberId);
        var batchId = jdbc.queryForObject("SELECT MAX(id) FROM import_batch", Long.class);
        jdbc.update("""
                INSERT INTO import_attempt(import_batch_id,attempt_no,status,trigger_type,available_at,
                    parser_version,detected_provider_code,started_at,finished_at,records_seen,records_valid,
                    created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',UTC_TIMESTAMP(6),'bench.v1','DEEPSEEK',
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?,UTC_TIMESTAMP(6))
                """, batchId, charges, charges);
        var attemptId = jdbc.queryForObject("SELECT MAX(id) FROM import_attempt", Long.class);
        jdbc.update("UPDATE import_batch SET confirmed_attempt_id=? WHERE id=?", attemptId, batchId);

        jdbc.update("INSERT INTO project(org_id,code,name,status,created_at,updated_at) VALUES (?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                organizationId, "m9-rpt", "M9 Reporting Project", "ACTIVE");
        var projectId = jdbc.queryForObject("SELECT MAX(id) FROM project WHERE org_id=?", Long.class, organizationId);
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,total_amount,
                    actual_amount,committed_amount,version,created_at,updated_at)
                VALUES (?,?,'ORG',?,'CNY','1000000.00000000','0.00000000','0.00000000',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, periodId, organizationId);
        jdbc.update("""
                INSERT INTO expense_claim(org_id,claimant_member_id,expense_date,amount,currency,status,version,
                    created_at,updated_at)
                VALUES (?,?,DATE_ADD(CURDATE(), INTERVAL -1 DAY),'5.00000000','CNY','DRAFT',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, actorMemberId);
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,tolerance_amount,
                    basis_hash,summary_json,created_by_member_id,started_at,finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','v1','0.01000000',REPEAT('0',64),JSON_OBJECT(),?,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, periodId, actorMemberId);

        // Batched facts: raw records + canonical charges across 3 providers.
        var providers = new String[] {"DEEPSEEK", "OPENAI", "ANTHROPIC"};
        var perProvider = charges / 3;
        var rawRows = new ArrayList<Object[]>();
        for (var index = 0; index < charges; index++) {
            rawRows.add(new Object[] {attemptId, index, "loc-" + index,
                    "{\"k\":\"bench\"}", "NORMALIZED", "2026-09-02 00:00:00.000000"});
        }
        jdbc.batchUpdate("""
                INSERT INTO raw_provider_record(import_attempt_id,record_index,record_locator,
                    raw_payload,normalize_status,usage_start,created_at)
                VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, rawRows);
        var rawIds = jdbc.queryForList(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? ORDER BY id", Long.class, attemptId);

        var chargeRows = new ArrayList<Object[]>();
        for (var index = 0; index < charges; index++) {
            // A remainder group is folded into the last provider (e.g. 10000 = 3*3333 + 1).
            var provider = providers[Math.min(index / perProvider, providers.length - 1)];
            chargeRows.add(new Object[] {organizationId, rawIds.get(index),
                    provider, AMOUNT_PER_CHARGE, "CNY",
                    "2026-09-02 00:00:00.000000", "2026-09-02 01:00:00.000000"});
        }
        jdbc.batchUpdate("""
                INSERT INTO charge_fact(org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    review_status,period_start,period_end,created_at)
                VALUES (?,?,0,?,'USAGE',?,?,'CLEAN',?,?,UTC_TIMESTAMP(6))
                """, chargeRows);
        var chargeIds = jdbc.queryForList(
                "SELECT id FROM charge_fact WHERE org_id=? ORDER BY id", Long.class, organizationId);

        var decisionRows = new ArrayList<Object[]>();
        for (var index = 0; index < charges; index++) {
            decisionRows.add(new Object[] {organizationId, chargeIds.get(index),
                    "CHARGE_FACT", "MANUAL", "CONFIRMED", actorMemberId});
        }
        jdbc.batchUpdate("""
                INSERT INTO allocation_decision(org_id,charge_fact_id,subject_type,decision_source,status,
                    created_by_member_id,created_at)
                VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, decisionRows);
        var decisionIds = jdbc.queryForList(
                "SELECT id FROM allocation_decision WHERE org_id=? ORDER BY id", Long.class, organizationId);

        var lineRows = new ArrayList<Object[]>();
        for (var index = 0; index < charges; index++) {
            lineRows.add(new Object[] {organizationId, decisionIds.get(index),
                    AMOUNT_PER_CHARGE, "CNY", projectId});
        }
        jdbc.batchUpdate("""
                INSERT INTO allocation_line(org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,created_at)
                VALUES (?,?,0,?,?,?,UTC_TIMESTAMP(6))
                """, lineRows);
        jdbc.update("""
                UPDATE charge_fact cf
                JOIN allocation_decision ad
                  ON ad.charge_fact_id=cf.id AND ad.org_id=cf.org_id
                SET cf.current_allocation_decision_id=ad.id
                WHERE cf.org_id=?
                """, organizationId);

        jdbc.update("""
                INSERT INTO `role`(code,name) VALUES (?,?)
                """, ROLE_CODE, ROLE_CODE);
        for (var permission : List.of("PERIOD_READ", "COST_READ", "ALLOCATION_READ", "BUDGET_READ",
                "DUPLICATE_REVIEW", "RECONCILIATION_READ")) {
            jdbc.update("""
                    INSERT IGNORE INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p WHERE r.code=? AND p.code=?
                    """, ROLE_CODE, permission);
        }
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,'ORG',?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, organizationId, ROLE_CODE);
    }

    private void resetDatabase() {
        clearDatabase();
    }

    private void clearDatabase() {
        if (jdbc == null) {
            return;
        }
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code=?", ROLE_CODE);
        jdbc.update("DELETE FROM `role` WHERE code=?", ROLE_CODE);
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }
}