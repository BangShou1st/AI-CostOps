package com.aicostops.budget.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.aicostops.budget.infrastructure.BudgetCommitmentMapper;
import com.aicostops.budget.infrastructure.BudgetMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.observability.AiCostOpsMetrics;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * AIC-068 deterministic evidence for the existing financial retry boundary.
 * The retry is intentionally exercised without a broad exception catch or a
 * sleep-based race: the supplier is the transaction boundary fault seam.
 */
class M8FinancialDeadlockRetryTest {

    @Test
    void transientDeadlockRecoversWithinTheThreeAttemptBound() {
        var service = new RetryProbe();
        var attempts = new AtomicInteger();

        var result = service.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw deadlock();
            }
            return "committed-on-retry";
        });

        assertThat(result).isEqualTo("committed-on-retry");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void persistentDeadlockFailsAfterExactlyThreeAttempts() {
        var service = new RetryProbe();
        var attempts = new AtomicInteger();
        var lastFailure = new AtomicReference<DeadlockLoserDataAccessException>();

        assertThatThrownBy(() -> service.execute(() -> {
            attempts.incrementAndGet();
            var failure = deadlock();
            lastFailure.set(failure);
            throw failure;
        })).isSameAs(lastFailure.get());

        assertThat(attempts).hasValue(3);
    }

    @Test
    void validationFailureIsNotRetried() {
        var service = new RetryProbe();
        var attempts = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(() -> {
            attempts.incrementAndGet();
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid request", "The request is invalid.");
        })).isInstanceOf(DomainException.class);

        assertThat(attempts).hasValue(1);
    }

    private static DeadlockLoserDataAccessException deadlock() {
        return new DeadlockLoserDataAccessException("synthetic MySQL 1213", null);
    }

    private static final class RetryProbe extends BudgetCommitmentCommandService {

        private RetryProbe() {
            super(
                    mock(AuthorizationContextService.class),
                    mock(BudgetMapper.class),
                    mock(BudgetCommitmentMapper.class),
                    mock(BillingPeriodFinancialWriteFence.class),
                    mock(CommitmentIdempotency.class),
                    mock(CommitmentAuditPort.class),
                    mock(CommitmentResponseCodec.class),
                    mock(AiCostOpsMetrics.class),
                    mock(PlatformTransactionManager.class),
                    Clock.systemUTC());
        }

        private <T> T execute(Supplier<T> operation) {
            return executeWithDeadlockRetry(operation);
        }
    }
}
