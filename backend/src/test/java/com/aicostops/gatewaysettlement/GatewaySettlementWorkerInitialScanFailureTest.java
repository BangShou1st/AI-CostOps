package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aicostops.gatewaysettlement.application.GatewaySettlementDiscoveryService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementWorker;
import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper;
import com.aicostops.observability.AiCostOpsMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * G-03: the initial {@code selectOrganizationsWithWork(...)} organization scan
 * runs before the per-organization {@code try}/{@code catch}. A datasource that
 * is unreachable at that first query must be reported as a DATABASE dependency
 * failure with a redacted WARN and must end the round safely with the cursor
 * unchanged — never mistaken for "there is no pending work".
 *
 * <p>Pure Mockito control-flow bindings (no database); the real-MySQL counterpart
 * lives in {@code GatewaySettlementWorkerTraversalIntegrationTest}.
 */
class GatewaySettlementWorkerInitialScanFailureTest {

    private static final String SECRET_MARKER = "sk-synthetic-g03-marker-1234567890";

    private ListAppender<ILoggingEvent> logWatcher;
    private ch.qos.logback.classic.Logger workerLogger;

    @BeforeEach
    void watchWorkerWarnings() {
        workerLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(GatewaySettlementWorker.class);
        logWatcher = new ListAppender<>();
        logWatcher.start();
        workerLogger.addAppender(logWatcher);
        workerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void stopWatchingWorkerWarnings() {
        workerLogger.detachAppender(logWatcher);
    }

    @Test
    void initialScanDatasourceFailureIsSignalledRedactedAndEndsTheRoundSafely() {
        var mapper = Mockito.mock(GatewaySettlementMapper.class);
        var discovery = Mockito.mock(GatewaySettlementDiscoveryService.class);
        var service = Mockito.mock(GatewaySettlementService.class);
        var metrics = Mockito.mock(AiCostOpsMetrics.class);
        when(mapper.selectOrganizationsWithWork(any(), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException(
                        "connect failed: api_key=" + SECRET_MARKER));

        // The initial scan is outside any try/catch, so this used to escape runOnce().
        var attempted = worker(mapper, discovery, service, metrics).runOnce();

        assertThat(attempted).isZero();
        verify(metrics).dependencyError("DATABASE");
        // A datasource outage is not an organization-level failure and must never
        // be counted as if an organization had been processed.
        verify(metrics, never()).gatewaySettlementRetry(anyString());
        verify(discovery, never()).discover(anyLong(), anyInt());
        assertThat(logWatcher.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("initial organization scan")
                    .contains("[REDACTED]")
                    .doesNotContain(SECRET_MARKER);
        });
    }

    @Test
    void consecutiveInitialScanFailuresKeepTheCursorAndSignalEveryRound() {
        var mapper = Mockito.mock(GatewaySettlementMapper.class);
        var discovery = Mockito.mock(GatewaySettlementDiscoveryService.class);
        var service = Mockito.mock(GatewaySettlementService.class);
        var metrics = Mockito.mock(AiCostOpsMetrics.class);
        when(mapper.selectOrganizationsWithWork(any(), eq(0L),
                eq(GatewaySettlementWorker.MAX_ORGANIZATIONS_PER_POLL)))
                .thenThrow(new DataAccessResourceFailureException("database unreachable"));

        var worker = worker(mapper, discovery, service, metrics);

        assertThat(worker.runOnce()).isZero();
        assertThat(worker.runOnce()).isZero();

        // Both rounds re-read from the same cursor: the failure never advanced it.
        verify(mapper, times(2)).selectOrganizationsWithWork(any(), eq(0L),
                eq(GatewaySettlementWorker.MAX_ORGANIZATIONS_PER_POLL));
        verify(metrics, times(2)).dependencyError("DATABASE");
    }

    @Test
    void roundTraversesNormallyOnceTheInitialScanRecovers() {
        var mapper = Mockito.mock(GatewaySettlementMapper.class);
        var discovery = Mockito.mock(GatewaySettlementDiscoveryService.class);
        var service = Mockito.mock(GatewaySettlementService.class);
        var metrics = Mockito.mock(AiCostOpsMetrics.class);
        when(mapper.selectOrganizationsWithWork(any(), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("database unreachable"))
                .thenReturn(List.of(11L));
        when(discovery.workIds(11L, GatewaySettlementWorker.DEFAULT_BATCH_SIZE))
                .thenReturn(List.of(7L));

        var worker = worker(mapper, discovery, service, metrics);

        assertThat(worker.runOnce()).isZero();
        assertThat(worker.runOnce()).isEqualTo(1);
        verify(service).settle(11, 7);
        verify(metrics, times(1)).dependencyError("DATABASE");
    }

    private static GatewaySettlementWorker worker(GatewaySettlementMapper mapper,
            GatewaySettlementDiscoveryService discovery, GatewaySettlementService service,
            AiCostOpsMetrics metrics) {
        var txManager = Mockito.mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any()))
                .thenReturn(Mockito.mock(org.springframework.transaction.TransactionStatus.class));
        return new GatewaySettlementWorker(mapper, discovery, service, metrics, txManager,
                Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC));
    }
}
