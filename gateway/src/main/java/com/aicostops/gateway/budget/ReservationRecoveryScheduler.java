package com.aicostops.gateway.budget;

import com.aicostops.gateway.config.BlockingIoScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for M12 reservation recovery. Disabled in tests via
 * {@code aicostops.gateway.reservation-recovery-enabled=false} so background
 * scans never interfere across test classes sharing one container; tests
 * drive {@link ReservationRecoveryService#recoverExpiredBlocking()}
 * deterministically instead.
 */
@Component
@ConditionalOnProperty(
        prefix = "aicostops.gateway",
        name = "reservation-recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationRecoveryScheduler {

    private final ReservationRecoveryService recoveryService;
    private final BlockingIoScheduler blockingIo;

    public ReservationRecoveryScheduler(
            ReservationRecoveryService recoveryService, BlockingIoScheduler blockingIo) {
        this.recoveryService = recoveryService;
        this.blockingIo = blockingIo;
    }

    @Scheduled(fixedDelayString = "${aicostops.gateway.reservation-recovery-interval-ms:60000}")
    public void recoverExpired() {
        blockingIo.run(recoveryService::recoverExpiredBlocking).subscribe();
    }
}
