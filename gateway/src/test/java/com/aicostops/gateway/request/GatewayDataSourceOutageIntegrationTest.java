package com.aicostops.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.request.GatewayRequestService.AuthorizeCommand;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F02 on real MySQL: a MySQL outage at TX1 (budget reservation) or TX2
 * (dispatch fence) must fail closed without creating a reservation that was
 * never committed, without moving the request to {@code DISPATCH_INTENT}, and
 * the pooled datasource must recover for the next request.
 *
 * <p>The outage is injected at the JDBC boundary of this test context only: a
 * delegating {@link DataSource} throws {@link SQLTransientConnectionException}
 * for statements matching a test-selected SQL fragment. This is a real
 * {@code SQLException} travelling through MyBatis/Spring translation, not a
 * mocked service result, and the underlying MySQL server stays healthy so the
 * recovery leg is a genuine pool re-acquisition.
 */
@SpringBootTest
@Tag("integration")
@Import(GatewayDataSourceOutageIntegrationTest.FaultDataSourceConfiguration.class)
class GatewayDataSourceOutageIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final FaultInjectingDataSource FAULT = new FaultInjectingDataSource();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GatewayRequestService requestService;

    @Autowired
    private RouteAttemptCoordinator coordinator;

    @Autowired
    private BudgetReservationService reservations;

    @Autowired
    private DispatchFenceService fence;

    private SeededEnv env;

    @AfterEach
    void clean() {
        FaultInjectingDataSource.clearFault();
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void tx1DatasourceFailureLeavesNoReservationAndRecovers() {
        var request = validatedRequestWithPlannedAttempt("tx1-outage");
        var command = admission(request);

        FaultInjectingDataSource.failOn(sql -> sql.contains("INSERT INTO budget_reservation"));
        assertThatThrownBy(() -> reservations.admitSync(command))
                .isInstanceOf(DataAccessException.class);

        assertThat(count("SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND request_id=?",
                env.orgId(), request.requestId())).isZero();
        assertThat(requestState(request.requestId())).isEqualTo("VALIDATED");
        assertThat(attemptStatus(request.attemptId())).isEqualTo("PLANNED");
        assertThat(budgetActual()).isEqualByComparingTo("0.00000000");

        // Same pool, same JVM: clearing the injected SQL failure is enough for
        // the next transaction to acquire a healthy connection.
        FaultInjectingDataSource.clearFault();
        var admission = reservations.admitSync(command);

        assertThat(admission.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
        assertThat(admission.reservationId()).isPositive();
        assertThat(count("SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND request_id=? "
                + "AND status='ACTIVE'", env.orgId(), request.requestId())).isEqualTo(1);
        assertThat(requestState(request.requestId())).isEqualTo("RESERVED");
        assertThat(budgetActual()).isEqualByComparingTo("0.00000000");
    }

    @Test
    void tx2DatasourceFailureNeverReachesDispatchIntentAndRecovers() {
        var request = validatedRequestWithPlannedAttempt("tx2-outage");
        var admission = reservations.admitSync(admission(request));
        assertThat(admission.outcome()).isEqualTo(AdmissionOutcome.RESERVED);

        FaultInjectingDataSource.failOn(sql -> sql.contains("FOR SHARE"));
        assertThatThrownBy(() -> fence.commitDispatchFence(env.orgId(), request.requestId(),
                request.attemptId(), env.periodId(), admission))
                .isInstanceOf(DataAccessException.class);

        assertThat(requestState(request.requestId())).isEqualTo("RESERVED");
        assertThat(attemptStatus(request.attemptId())).isEqualTo("PLANNED");
        assertThat(dispatchIntentAt(request.requestId())).isNull();
        assertThat(count("SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND request_id=? "
                + "AND status='ACTIVE'", env.orgId(), request.requestId())).isEqualTo(1);

        FaultInjectingDataSource.clearFault();
        fence.commitDispatchFence(env.orgId(), request.requestId(), request.attemptId(),
                env.periodId(), admission);

        assertThat(requestState(request.requestId())).isEqualTo("DISPATCH_INTENT");
        assertThat(attemptStatus(request.attemptId())).isEqualTo("DISPATCH_INTENT");
        assertThat(dispatchIntentAt(request.requestId())).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND request_id=?",
                env.orgId(), request.requestId())).isEqualTo(1);
    }

    @Test
    void repeatedTx2FailureNeverReleasesOrDuplicatesTheHold() {
        var request = validatedRequestWithPlannedAttempt("tx2-repeat");
        var admission = reservations.admitSync(admission(request));

        FaultInjectingDataSource.failOn(sql -> sql.contains("FOR SHARE"));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> fence.commitDispatchFence(env.orgId(), request.requestId(),
                    request.attemptId(), env.periodId(), admission))
                    .isInstanceOf(DataAccessException.class);
        }
        FaultInjectingDataSource.clearFault();

        assertThat(count("SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND request_id=?",
                env.orgId(), request.requestId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND request_id=? "
                + "AND status='ACTIVE'", env.orgId(), request.requestId())).isEqualTo(1);
        assertThat(requestState(request.requestId())).isEqualTo("RESERVED");

        fence.commitDispatchFence(env.orgId(), request.requestId(), request.attemptId(),
                env.periodId(), admission);
        assertThat(requestState(request.requestId())).isEqualTo("DISPATCH_INTENT");
    }

    // -----------------------------------------------------------------------

    private RequestRef validatedRequestWithPlannedAttempt(String suffix) {
        env = GatewayTestFixture.seed(jdbc, "outage-" + suffix + "-" + System.nanoTime(),
                HMAC_KEY, "aic_0123456789ab_" + "A".repeat(43));
        var principal = principal();
        var authorized = requestService.authorizeForRouting(new AuthorizeCommand(
                principal, env.modelId(),
                ("{\"model\":\"" + GatewayTestFixture.MODEL_KEY + "\"}").getBytes(StandardCharsets.UTF_8),
                "outage-" + suffix + "-" + System.nanoTime(), 16, false)).block();
        assertThat(authorized).isNotNull();
        var policyId = jdbc.queryForObject(
                "SELECT id FROM routing_policy WHERE org_id=? AND project_id IS NULL AND model_id=? "
                        + "AND status='ACTIVE'", Long.class, env.orgId(), env.modelId());
        var planned = coordinator.plan(env.orgId(), authorized.requestId(), policyId,
                "INITIAL_PRIMARY", env.providerAccountId(), env.providerModelId(), null,
                env.pricingVersionId());
        return new RequestRef(authorized.requestId(), planned.id());
    }

    private AdmissionCommand admission(RequestRef request) {
        return new AdmissionCommand(principal(), request.requestId(), request.attemptId(),
                env.periodId(), env.pricingVersionId(), "USD", 16L, -1L, true);
    }

    private GatewayPrincipal principal() {
        return new GatewayPrincipal(env.credentialId(), env.orgId(), env.projectId(), "SERVICE",
                null, env.serviceIdentityId(), "PROJECT", env.projectId(), "OPTIONAL");
    }

    private String requestState(long requestId) {
        return jdbc.queryForObject("SELECT state FROM gateway_request WHERE id=? AND org_id=?",
                String.class, requestId, env.orgId());
    }

    private String attemptStatus(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM gateway_route_attempt WHERE id=? AND org_id=?",
                String.class, attemptId, env.orgId());
    }

    private String dispatchIntentAt(long requestId) {
        return jdbc.queryForObject("SELECT dispatch_intent_at FROM gateway_request WHERE id=? AND org_id=?",
                String.class, requestId, env.orgId());
    }

    private java.math.BigDecimal budgetActual() {
        return jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=? AND org_id=?",
                java.math.BigDecimal.class, env.budgetId(), env.orgId());
    }

    private int count(String sql, Object... args) {
        var value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private record RequestRef(long requestId, long attemptId) {
    }

    @TestConfiguration
    static class FaultDataSourceConfiguration {

        @Bean
        @Primary
        DataSource faultInjectingDataSource() {
            var hikari = new HikariDataSource();
            hikari.setJdbcUrl(MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
            hikari.setUsername(MYSQL.getUsername());
            hikari.setPassword(MYSQL.getPassword());
            hikari.setMaximumPoolSize(4);
            hikari.setMinimumIdle(1);
            hikari.setPoolName("hc03-fault-pool");
            return FAULT.bind(hikari);
        }
    }

    /** Delegating DataSource that can fail selected SQL statements at JDBC level. */
    static final class FaultInjectingDataSource implements DataSource {

        private static final AtomicReference<Predicate<String>> FAULT =
                new AtomicReference<>(sql -> false);
        private volatile DataSource delegate;

        static void failOn(Predicate<String> predicate) {
            FAULT.set(predicate);
        }

        static void clearFault() {
            FAULT.set(sql -> false);
        }

        FaultInjectingDataSource bind(DataSource target) {
            this.delegate = target;
            return this;
        }

        private static void maybeFail(String sql) throws SQLException {
            if (sql != null && FAULT.get().test(sql)) {
                throw new SQLTransientConnectionException(
                        "HC-03 injected datasource outage for statement matching the fault predicate");
            }
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("prepareStatement") && args != null
                                && args.length > 0 && args[0] instanceof String sql) {
                            maybeFail(sql);
                        }
                        if (method.getName().equals("prepareCall") && args != null
                                && args.length > 0 && args[0] instanceof String sql) {
                            maybeFail(sql);
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
