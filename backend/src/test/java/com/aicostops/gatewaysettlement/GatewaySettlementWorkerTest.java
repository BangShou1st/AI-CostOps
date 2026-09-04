package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.gatewaysettlement.application.GatewaySettlementDiscoveryService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementWorker;
import com.aicostops.gatewaysettlement.domain.GatewaySettlement;
import com.aicostops.gatewaysettlement.domain.GatewaySettlementStatus;
import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper;
import com.aicostops.observability.AiCostOpsMetrics;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;

class GatewaySettlementWorkerTest {

    @Test
    void usesBoundedBackoffCatalog() {
        assertThat(GatewaySettlementWorker.backoff(1).toSeconds()).isEqualTo(1);
        assertThat(GatewaySettlementWorker.backoff(2).toSeconds()).isEqualTo(5);
        assertThat(GatewaySettlementWorker.backoff(3).toSeconds()).isEqualTo(30);
        assertThat(GatewaySettlementWorker.backoff(99).toSeconds()).isEqualTo(30);
    }

    @Test
    void convertsTransientFailureToDurableRetryWithoutClaimingSettlement() {
        var mapper = Mockito.mock(GatewaySettlementMapper.class);
        var discovery = Mockito.mock(GatewaySettlementDiscoveryService.class);
        var service = Mockito.mock(GatewaySettlementService.class);
        var metrics = Mockito.mock(AiCostOpsMetrics.class);
        var txManager = Mockito.mock(PlatformTransactionManager.class);
        var status = Mockito.mock(org.springframework.transaction.TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(status);
        var settlement = settlement(7, GatewaySettlementStatus.PENDING, 0);
        when(mapper.selectById(11, 7)).thenReturn(settlement);
        doThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .when(service).settle(11, 7);

        var worker = new GatewaySettlementWorker(mapper, discovery, service, metrics, txManager,
                Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC));
        worker.attempt(11, 7);

        verify(mapper).markRetryableFailed(Mockito.eq(11L), Mockito.eq(7L),
                Mockito.eq(Instant.parse("2026-09-04T00:00:01Z")),
                Mockito.eq("DATABASE_TRANSIENT"), Mockito.eq(Instant.parse("2026-09-04T00:00:00Z")));
        verify(mapper, Mockito.never()).selectByIdForUpdate(anyLong(), anyLong());
    }

    @Test
    void exhaustedTransientFailuresBecomeReconciliationRequired() {
        var mapper = Mockito.mock(GatewaySettlementMapper.class);
        var discovery = Mockito.mock(GatewaySettlementDiscoveryService.class);
        var service = Mockito.mock(GatewaySettlementService.class);
        var metrics = Mockito.mock(AiCostOpsMetrics.class);
        var txManager = Mockito.mock(PlatformTransactionManager.class);
        var status = Mockito.mock(org.springframework.transaction.TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(status);
        when(mapper.selectById(11, 7)).thenReturn(settlement(7,
                GatewaySettlementStatus.RETRYABLE_FAILED, GatewaySettlementWorker.MAX_AUTO_RETRIES));
        doThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .when(service).settle(11, 7);

        var worker = new GatewaySettlementWorker(mapper, discovery, service, metrics, txManager,
                Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC));
        worker.attempt(11, 7);

        verify(mapper).markReconciliationRequired(11, 7, "RETRY_EXHAUSTED",
                Instant.parse("2026-09-04T00:00:00Z"));
        verify(mapper, Mockito.never()).markRetryableFailed(anyLong(), anyLong(), any(), any(), any());
    }

    private static GatewaySettlement settlement(long id, GatewaySettlementStatus status,
            int attempts) {
        var now = Instant.parse("2026-09-04T00:00:00Z");
        return new GatewaySettlement(id, 11, "GATEWAY_REQUEST:req", 21, 31, 41, null, 51,
                "PROJECT", 61, 71, 81, 91, "USD", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, status, attempts, null, null, null, now, null, null, now);
    }
}
