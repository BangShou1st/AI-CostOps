package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.request.RequestIdentityService;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import reactor.core.publisher.Mono;

/**
 * Real-MySQL 8.4 evaluation and digest proof for HC-04 A4 JDBC session parameters.
 *
 * <p>Compares {@code performance_schema.events_statements_summary_by_digest} and
 * {@code Com_stmt_*} counters between a baseline pool (no A4 properties) and
 * the Spring-configured Gateway pool (which receives A4 driver properties via
 * {@code spring.datasource.hikari.data-source-properties} even when
 * {@code spring.datasource.url} omits them).
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
@Tag("integration")
class JdbcSessionParametersIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private BudgetReservationService springReservationService;

    @Autowired
    private GatewayRequestMapper requestMapper;

    @Autowired
    private RequestIdentityService identityService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource springDataSource;

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void a4EliminatesTransactionReadOnlyDigestsAndCachesServerPreparedStatements() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "a4-digest-" + System.nanoTime(), HMAC_KEY, rawKey());
        jdbc.update("UPDATE budget SET total_amount='5000.00000000' WHERE id=?", env.budgetId());

        int warmupCount = 2;
        int steadyCount = 20;

        var baselineRequests = new ArrayList<RequestIds>(warmupCount + steadyCount);
        for (int i = 0; i < warmupCount + steadyCount; i++) {
            baselineRequests.add(insertValidatedRequest(env, "a4-base-" + i));
        }
        var optimizedRequests = new ArrayList<RequestIds>(warmupCount + steadyCount);
        for (int i = 0; i < warmupCount + steadyCount; i++) {
            optimizedRequests.add(insertValidatedRequest(env, "a4-opt-" + i));
        }

        DigestSnapshot before;
        try (var baselineDs = createBaselineDataSource();
             var rootConn = openRootConnection()) {
            var baselineService = createReservationService(baselineDs);
            for (int i = 0; i < warmupCount; i++) {
                var res = baselineService.admitSync(command(env, baselineRequests.get(i)));
                assertThat(res.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
            }
            before = measureSteadyState(rootConn, () -> {
                for (int i = warmupCount; i < warmupCount + steadyCount; i++) {
                    var res = baselineService.admitSync(command(env, baselineRequests.get(i)));
                    assertThat(res.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
                    assertThat(res.reservationId()).isPositive();
                }
            });
        }

        // Verify the Spring-managed HikariDataSource (where GatewayMySqlContainerSupport
        // overrides spring.datasource.url without A4 params, so A4 comes purely from
        // spring.datasource.hikari.data-source-properties).
        var hikari = springDataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getDataSourceProperties().getProperty("useLocalSessionState")).isEqualTo("true");

        DigestSnapshot after;
        try (var rootConn = openRootConnection()) {
            for (int i = 0; i < warmupCount; i++) {
                var res = springReservationService.admit(command(env, optimizedRequests.get(i))).block();
                assertThat(res.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
            }
            after = measureSteadyState(rootConn, () -> {
                for (int i = warmupCount; i < warmupCount + steadyCount; i++) {
                    var res = springReservationService.admit(command(env, optimizedRequests.get(i))).block();
                    assertThat(res.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
                    assertThat(res.reservationId()).isPositive();
                }
            });
        }

        System.out.println("=== A4 performance_schema digest comparison (20 steady-state TX1 admissions) ===");
        System.out.printf("BEFORE: totalDigests=%d readOnlyCount=%d comStmtPrepare=%d comStmtExecute=%d%n",
                before.totalExecutions(), before.readOnlyExecutions(),
                before.comStmtPrepareDelta(), before.comStmtExecuteDelta());
        before.topDigests().forEach(row -> System.out.println("  BEFORE digest: " + row));
        System.out.printf("AFTER:  totalDigests=%d readOnlyCount=%d comStmtPrepare=%d comStmtExecute=%d%n",
                after.totalExecutions(), after.readOnlyExecutions(),
                after.comStmtPrepareDelta(), after.comStmtExecuteDelta());
        after.topDigests().forEach(row -> System.out.println("  AFTER  digest: " + row));

        assertThat(before.readOnlyExecutions())
                .describedAs("Baseline without useLocalSessionState=true must execute @@session.transaction_read_only")
                .isGreaterThan(0L);
        assertThat(after.readOnlyExecutions())
                .describedAs("A4 useLocalSessionState=true must eliminate SELECT @@session.transaction_read_only in steady state")
                .isZero();
        assertThat(after.totalExecutions()).isLessThan(before.totalExecutions());
        assertThat(after.comStmtPrepareDelta())
                .describedAs("cachePrepStmts + useServerPrepStmts must cache statements after warmup")
                .isZero();
        assertThat(after.comStmtExecuteDelta())
                .describedAs("useServerPrepStmts=true must execute server-side prepared statements")
                .isGreaterThan(0L);

        // Verify useGeneratedKeys + reserved_amount invariant across all 44 admissions.
        var budgetReserved = jdbc.queryForObject(
                "SELECT reserved_amount FROM budget WHERE id=?", BigDecimal.class, env.budgetId());
        var sumHolds = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_amount), 0) FROM budget_reservation
                WHERE org_id=? AND budget_id=? AND status IN ('ACTIVE','PENDING_HOLD')
                """, BigDecimal.class, env.orgId(), env.budgetId());
        assertThat(budgetReserved).isEqualByComparingTo(sumHolds);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND status='ACTIVE'",
                Integer.class, env.orgId())).isEqualTo(2 * (warmupCount + steadyCount));
    }

    private DigestSnapshot measureSteadyState(Connection rootConn, Runnable workload) throws SQLException {
        try (var st = rootConn.createStatement()) {
            st.execute("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest");
        }
        long prepareBefore = globalStatus(rootConn, "Com_stmt_prepare");
        long executeBefore = globalStatus(rootConn, "Com_stmt_execute");
        workload.run();
        long prepareAfter = globalStatus(rootConn, "Com_stmt_prepare");
        long executeAfter = globalStatus(rootConn, "Com_stmt_execute");

        long totalExecutions = 0L;
        long readOnlyExecutions = 0L;
        var topDigests = new ArrayList<String>();
        try (var st = rootConn.createStatement();
             var rs = st.executeQuery("""
                     SELECT DIGEST_TEXT, SUM(COUNT_STAR) AS total_count
                     FROM performance_schema.events_statements_summary_by_digest
                     WHERE DIGEST_TEXT NOT LIKE '%events_statements_summary_by_digest%'
                       AND DIGEST_TEXT NOT LIKE 'SHOW GLOBAL STATUS%'
                     GROUP BY DIGEST_TEXT
                     ORDER BY total_count DESC, DIGEST_TEXT ASC
                     """)) {
            while (rs.next()) {
                var digest = rs.getString("DIGEST_TEXT");
                long count = rs.getLong("total_count");
                totalExecutions += count;
                if (digest != null && digest.contains("transaction_read_only")) {
                    readOnlyExecutions += count;
                }
                if (topDigests.size() < 20) {
                    topDigests.add(count + "x | " + digest);
                }
            }
        }
        return new DigestSnapshot(
                totalExecutions,
                readOnlyExecutions,
                prepareAfter - prepareBefore,
                executeAfter - executeBefore,
                topDigests);
    }

    private static long globalStatus(Connection rootConn, String name) throws SQLException {
        try (var ps = rootConn.prepareStatement("SHOW GLOBAL STATUS LIKE ?")) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(2) : 0L;
            }
        }
    }

    private static HikariDataSource createBaselineDataSource() {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        ds.setMaximumPoolSize(1);
        ds.setMinimumIdle(1);
        ds.setPoolName("a4-baseline-pool");
        return ds;
    }

    private static Connection openRootConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl() + "?serverTimezone=UTC", "root", MYSQL.getPassword());
    }

    private static BudgetReservationService createReservationService(DataSource ds) {
        var cfg = new Configuration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(BudgetReservationMapper.class);
        cfg.addMapper(GatewayRequestMapper.class);
        var env = new org.apache.ibatis.mapping.Environment(
                "baseline",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),
                ds);
        cfg.setEnvironment(env);
        var factory = new SqlSessionFactoryBuilder().build(cfg);
        var sqlSession = new SqlSessionTemplate(factory);
        var resMapper = sqlSession.getMapper(BudgetReservationMapper.class);
        var reqMapper = sqlSession.getMapper(GatewayRequestMapper.class);
        var txManager = new DataSourceTransactionManager(ds);
        var props = new GatewayProperties();
        var blockingIo = new BlockingIoScheduler() {
            @Override
            public <T> Mono<T> call(Callable<T> operation) {
                return Mono.fromCallable(operation);
            }

            @Override
            public Mono<Void> run(Runnable operation) {
                return Mono.fromRunnable(operation);
            }

            @Override
            public void close() {
            }
        };
        return new BudgetReservationService(
                resMapper, reqMapper, props, blockingIo, txManager, Clock.systemUTC());
    }

    private AdmissionCommand command(SeededEnv env, RequestIds ids) {
        var principal = new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "REQUIRED");
        return new AdmissionCommand(
                principal, ids.requestId(), ids.attemptId(),
                env.periodId(), env.pricingVersionId(), "USD", 8192L, -1L);
    }

    private RequestIds insertValidatedRequest(SeededEnv env, String idempotencyKey) {
        var idemDigest = identityService.idempotencyKeyDigest(idempotencyKey);
        var fingerprint = identityService.requestFingerprint(
                ("{\"model\":\"a4-" + idempotencyKey + "\"}").getBytes(StandardCharsets.UTF_8));
        requestMapper.insertRequest(new GatewayRequestMapper.GatewayRequestInsert(
                env.orgId(), identityService.newPublicRequestId(), env.credentialId(), "SERVICE",
                null, env.serviceIdentityId(), env.projectId(), "PROJECT", env.projectId(),
                env.modelId(), idemDigest, fingerprint));
        var requestId = jdbc.queryForObject("""
                SELECT id FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, env.orgId());
        requestMapper.insertRouteAttempt(new GatewayRequestMapper.RouteAttemptInsert(
                env.orgId(), requestId, identityService.newRouteDecisionId(),
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId()));
        var attemptId = jdbc.queryForObject("""
                SELECT id FROM gateway_route_attempt WHERE request_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, requestId);
        return new RequestIds(requestId, attemptId);
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    private record RequestIds(long requestId, long attemptId) {
    }

    private record DigestSnapshot(
            long totalExecutions,
            long readOnlyExecutions,
            long comStmtPrepareDelta,
            long comStmtExecuteDelta,
            List<String> topDigests) {
    }
}
