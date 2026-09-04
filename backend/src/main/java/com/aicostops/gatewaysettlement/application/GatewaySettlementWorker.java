package com.aicostops.gatewaysettlement.application;

import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper;
import com.aicostops.observability.AiCostOpsMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded DB-backed Settlement worker.
 *
 * <p>The worker discovers current FINAL usage and then passes candidate ids to
 * the normal atomic financial transaction one at a time. It never claims a
 * Settlement row or persists a PROCESSING state. A transient database failure
 * is recorded only after the financial transaction has rolled back, using a
 * bounded delay and a bounded retry count.
 */
@Component
@ConditionalOnProperty(name = "aicostops.gateway.settlement.worker-enabled",
        havingValue = "true")
public final class GatewaySettlementWorker {

    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int MAX_ORGANIZATIONS_PER_POLL = 100;
    public static final int MAX_AUTO_RETRIES = 3;

    private final GatewaySettlementMapper settlements;
    private final GatewaySettlementDiscoveryService discovery;
    private final GatewaySettlementService service;
    private final AiCostOpsMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public GatewaySettlementWorker(
            GatewaySettlementMapper settlements,
            GatewaySettlementDiscoveryService discovery,
            GatewaySettlementService service,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.settlements = settlements;
        this.discovery = discovery;
        this.service = service;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${aicostops.gateway.settlement.poll-interval:5s}")
    public void scheduledPoll() {
        runOnce();
    }

    /** Runs one bounded poll and returns the number of settlement attempts. */
    public int runOnce() {
        var now = Instant.now(clock);
        var organizations = settlements.selectOrganizationsWithWork(
                now, MAX_ORGANIZATIONS_PER_POLL);
        var attempted = 0;
        for (var organizationId : organizations) {
            try {
                discovery.discover(organizationId, DEFAULT_BATCH_SIZE);
                for (var settlementId : discovery.workIds(organizationId, DEFAULT_BATCH_SIZE)) {
                    attempt(organizationId, settlementId);
                    attempted++;
                }
            } catch (TransientDataAccessException transientDiscoveryFailure) {
                // Discovery has no financial mutation to classify. The next
                // scheduled poll remains the bounded recovery path.
                metrics.gatewaySettlementRetry("DATABASE_TRANSIENT");
            }
        }
        return attempted;
    }

    public void attempt(long organizationId, long settlementId) {
        try {
            service.settle(organizationId, settlementId);
        } catch (TransientDataAccessException transientFailure) {
            recordTransientFailure(organizationId, settlementId);
        }
    }

    private void recordTransientFailure(long organizationId, long settlementId) {
        var current = settlements.selectById(organizationId, settlementId);
        if (current == null || current.status().isTerminal()) {
            return;
        }
        var now = Instant.now(clock);
        var nextAttempt = current.attemptCount() + 1;
        if (current.attemptCount() >= MAX_AUTO_RETRIES) {
            var changed = transactions.execute(status -> settlements.markReconciliationRequired(
                    organizationId, settlementId, "RETRY_EXHAUSTED", now));
            if (changed != null && changed == 1) {
                metrics.gatewaySettlementReconciliationRequired("RETRY_EXHAUSTED");
            }
            return;
        }

        var nextAttemptAt = now.plus(backoff(nextAttempt));
        var changed = transactions.execute(status -> settlements.markRetryableFailed(
                organizationId, settlementId, nextAttemptAt, "DATABASE_TRANSIENT", now));
        if (changed != null && changed == 1) {
            metrics.gatewaySettlementRetry("DATABASE_TRANSIENT");
            metrics.gatewaySettlement("RETRYABLE_FAILED", "RETRYABLE");
        }
    }

    public static Duration backoff(int retryNumber) {
        return switch (retryNumber) {
            case 1 -> Duration.ofSeconds(1);
            case 2 -> Duration.ofSeconds(5);
            default -> Duration.ofSeconds(30);
        };
    }
}
