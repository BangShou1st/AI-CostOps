package com.aicostops.gatewaysettlement.application;

import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper;
import com.aicostops.ingestion.application.SecretShapes;
import com.aicostops.observability.AiCostOpsMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
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
 *
 * <p>Organizations are visited through a rotating keyset cursor so a fixed page
 * size can never starve organizations beyond the first page. A failure confined
 * to one organization (its discovery or its settlement) is logged, counted and
 * isolated so the rest of the round still runs; only a datasource reachability
 * failure stops the round, and it leaves the cursor in place for the next poll.
 */
@Component
@ConditionalOnProperty(name = "aicostops.gateway.settlement.worker-enabled",
        havingValue = "true")
public final class GatewaySettlementWorker {

    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int MAX_ORGANIZATIONS_PER_POLL = 100;
    public static final int MAX_AUTO_RETRIES = 3;

    /** Bounded, low-cardinality failure reason codes (never free-form text). */
    static final String TRANSIENT_FAILURE = "DATABASE_TRANSIENT";
    static final String ISOLATED_DISCOVERY_FAILURE = "SETTLEMENT_ISOLATED_FAILURE";
    static final String ISOLATED_SETTLEMENT_FAILURE = "SETTLEMENT_ERROR";

    private static final Logger LOG = LoggerFactory.getLogger(GatewaySettlementWorker.class);

    private final GatewaySettlementMapper settlements;
    private final GatewaySettlementDiscoveryService discovery;
    private final GatewaySettlementService service;
    private final AiCostOpsMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Keyset cursor of the rotating organization scan. It advances only after a
     * page was fully processed, is kept in memory per worker instance (a restart
     * simply resumes from the beginning) and wraps inside the mapper query, so it
     * is bounded and fair without any external scheduling framework.
     */
    private long afterOrgId;

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
                now, afterOrgId, MAX_ORGANIZATIONS_PER_POLL);
        var attempted = 0;
        for (var organizationId : organizations) {
            try {
                discovery.discover(organizationId, DEFAULT_BATCH_SIZE);
                for (var settlementId : discovery.workIds(organizationId, DEFAULT_BATCH_SIZE)) {
                    attempt(organizationId, settlementId);
                    attempted++;
                }
            } catch (DataAccessResourceFailureException globalFailure) {
                // The datasource itself is unreachable, so this failure is not
                // confined to one organization. Stop the round before the cursor
                // advances: the next scheduled poll re-reads the same page, and
                // one organization can never be permanently skipped.
                metrics.dependencyError("DATABASE");
                LOG.warn("Gateway Settlement round stopped, datasource unavailable while "
                                + "scanning organization {}: {}", organizationId,
                        SecretShapes.redact(globalFailure.toString()));
                return attempted;
            } catch (TransientDataAccessException transientDiscoveryFailure) {
                // Discovery writes no partial financial state, so a transient
                // failure is isolated and the next poll is the bounded recovery
                // path.
                metrics.gatewaySettlementRetry(TRANSIENT_FAILURE);
                LOG.warn("Gateway Settlement discovery failed transiently for "
                                + "organization {}: {}", organizationId,
                        SecretShapes.redact(transientDiscoveryFailure.toString()));
            } catch (RuntimeException isolatedDiscoveryFailure) {
                // The organization's own work is isolated: its transaction (if any)
                // has already rolled back and discovery never writes partial
                // financial state, so the healthy organizations behind it still run
                // this round. The failure is logged and counted instead of silently
                // swallowed, and the next poll re-reads this organization.
                metrics.gatewaySettlementRetry(ISOLATED_DISCOVERY_FAILURE);
                LOG.warn("Gateway Settlement discovery failed for organization {}: {}",
                        organizationId, SecretShapes.redact(isolatedDiscoveryFailure.toString()));
            }
        }
        if (!organizations.isEmpty()) {
            afterOrgId = organizations.getLast();
        }
        return attempted;
    }

    public void attempt(long organizationId, long settlementId) {
        try {
            service.settle(organizationId, settlementId);
        } catch (DataAccessResourceFailureException globalFailure) {
            // A reachability failure must reach the round loop so it can stop the
            // round instead of being recorded as a settlement-level retry.
            throw globalFailure;
        } catch (TransientDataAccessException transientFailure) {
            recordBoundedFailure(organizationId, settlementId, TRANSIENT_FAILURE);
        } catch (RuntimeException isolatedSettlementFailure) {
            // Any other failure left this settlement unfinished. Its transaction has
            // already rolled back (the service wraps the financial work), so it is
            // recorded through the same bounded durable failure path as a transient
            // one: the next poll retries it and RETRY_EXHAUSTED still routes it to
            // the manual reconciliation boundary, so it can never retry forever.
            recordBoundedFailure(organizationId, settlementId, ISOLATED_SETTLEMENT_FAILURE);
            LOG.warn("Gateway Settlement attempt failed for organization {} settlement {}: {}",
                    organizationId, settlementId,
                    SecretShapes.redact(isolatedSettlementFailure.toString()));
        }
    }

    private void recordBoundedFailure(long organizationId, long settlementId, String errorCode) {
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
                organizationId, settlementId, nextAttemptAt, errorCode, now));
        if (changed != null && changed == 1) {
            metrics.gatewaySettlementRetry(errorCode);
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
