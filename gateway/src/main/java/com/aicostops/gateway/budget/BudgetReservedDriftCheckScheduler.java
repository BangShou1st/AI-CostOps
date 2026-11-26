package com.aicostops.gateway.budget;

import com.aicostops.gateway.config.BlockingIoScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for the read-only {@code budget.reserved_amount} drift
 * check. Disabled in tests via {@code aicostops.gateway.reservation-drift-check-enabled=false}
 * so background scans never interfere across test classes sharing one container;
 * tests invoke {@link BudgetReservedDriftCheckService#inspectReservedDrift()} directly.
 */
@Component
@ConditionalOnProperty(
        prefix = "aicostops.gateway",
        name = "reservation-drift-check-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BudgetReservedDriftCheckScheduler {

    private final BudgetReservedDriftCheckService driftCheckService;
    private final BlockingIoScheduler blockingIo;

    public BudgetReservedDriftCheckScheduler(
            BudgetReservedDriftCheckService driftCheckService, BlockingIoScheduler blockingIo) {
        this.driftCheckService = driftCheckService;
        this.blockingIo = blockingIo;
    }

    @Scheduled(
            fixedDelayString = "${aicostops.gateway.reservation-drift-check-interval-ms:60000}",
            initialDelayString = "${aicostops.gateway.reservation-drift-check-initial-delay-ms:60000}")
    public void inspectDrift() {
        blockingIo.run(driftCheckService::inspectReservedDrift).subscribe();
    }
}
