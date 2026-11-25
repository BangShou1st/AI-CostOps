package com.aicostops.gateway.budget;

import com.aicostops.gateway.config.BlockingIoScheduler;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ReservationRecoveryScheduler.class);

    private final ReservationRecoveryService recoveryService;
    private final BlockingIoScheduler blockingIo;
    private final AtomicBoolean queuePollRunning = new AtomicBoolean();

    public ReservationRecoveryScheduler(
            ReservationRecoveryService recoveryService, BlockingIoScheduler blockingIo) {
        this.recoveryService = recoveryService;
        this.blockingIo = blockingIo;
    }

    @Scheduled(fixedDelayString = "${aicostops.gateway.reservation-recovery-interval-ms:60000}")
    public void recoverExpired() {
        blockingIo.run(recoveryService::recoverExpiredBlocking).subscribe();
    }

    @Scheduled(fixedDelayString = "${aicostops.gateway.reservation-delay-poll-interval-ms:1000}")
    public void recoverQueued() {
        if (queuePollRunning.compareAndSet(false, true)) {
            blockingIo.run(recoveryService::recoverQueuedBlocking)
                    .doFinally(signal -> queuePollRunning.set(false))
                    .subscribe(null, error -> log.warn("Reservation queue poll failed", error));
        }
    }
}
