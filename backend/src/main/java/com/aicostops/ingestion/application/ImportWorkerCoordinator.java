package com.aicostops.ingestion.application;

import com.aicostops.ingestion.infrastructure.ImportWorkerProperties;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * DB-backed worker coordinator.
 *
 * <p>One {@code pollOnce()} recovers at most one expired Attempt, then claims the
 * next queued Attempt if a local execution permit is available. The local
 * {@link Semaphore} is acquired <em>before</em> the DB claim so executor saturation
 * can never strand a claimed Attempt. Tests call {@link #pollOnce()} directly
 * instead of waiting for a scheduler tick.
 *
 * <p>Registered conditionally on {@code aicostops.ingestion.worker-enabled=true}.
 */
public class ImportWorkerCoordinator {

    private final ImportLeaseService leases;
    private final ImportAttemptExecutor executor;
    private final ImportWorkerProperties properties;
    private final TaskExecutor taskExecutor;
    private final Semaphore permits;
    private final String workerId;

    public ImportWorkerCoordinator(
            ImportLeaseService leases,
            ImportAttemptExecutor executor,
            ImportWorkerProperties properties,
            TaskExecutor taskExecutor) {
        this.leases = leases;
        this.executor = executor;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.permits = new Semaphore(properties.workerConcurrency());
        this.workerId = "worker-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${aicostops.ingestion.poll-interval}")
    public void scheduledPoll() {
        pollOnce();
    }

    public void pollOnce() {
        leases.recoverExpiredLease();
        if (!permits.tryAcquire()) {
            return;
        }
        try {
            var claimed = leases.claimNext(workerId);
            if (claimed.isEmpty()) {
                permits.release();
                return;
            }
            final var lease = claimed.orElseThrow();
            taskExecutor.execute(() -> {
                try {
                    executor.execute(lease);
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException exception) {
            permits.release();
            throw exception;
        }
    }

    public String workerId() {
        return workerId;
    }
}
