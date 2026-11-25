package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.BudgetReservationMapper.BudgetRow;
import com.aicostops.gateway.persistence.BudgetReservationMapper.PricingRateRow;
import com.aicostops.gateway.persistence.BudgetReservationMapper.ReservationRow;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import reactor.core.publisher.Mono;

class BudgetReservationServiceTest {

    private static final long ORG_ID = 10L;
    private static final long REQUEST_ID = 100L;
    private static final long ATTEMPT_ID = 200L;
    private static final long PERIOD_ID = 300L;
    private static final long BUDGET_ID = 400L;
    private static final long PRICING_VERSION_ID = 500L;

    private BudgetReservationMapper reservationMapper;
    private GatewayRequestMapper requestMapper;
    private BudgetReservationService service;

    @BeforeEach
    void setUp() {
        reservationMapper = mock(BudgetReservationMapper.class);
        requestMapper = mock(GatewayRequestMapper.class);
        var properties = new GatewayProperties();
        properties.setReservationTtlMs(900_000L);
        var blockingIo = new BlockingIoScheduler() {
            @Override
            public <T> Mono<T> call(Callable<T> operation) {
                return Mono.fromCallable(operation);
            }

            @Override
            public Mono<Void> run(Runnable operation) {
                return Mono.fromRunnable(operation);
            }

            @Override
            public void close() {
            }
        };
        var txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
        var clock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
        service = new BudgetReservationService(
                reservationMapper, requestMapper, properties, blockingIo, txManager, clock);
    }

    @Test
    void duplicateKeyAfterReserveIncrementsRevertsAndReplaysWinner() {
        when(reservationMapper.findByRouteAttempt(ORG_ID, ATTEMPT_ID))
                .thenReturn(null)
                .thenReturn(activeRow(777L, new BigDecimal("31.94880000")));
        when(reservationMapper.countEffectiveHolds(ORG_ID, REQUEST_ID)).thenReturn(0);
        when(reservationMapper.findPricingRates(ORG_ID, PRICING_VERSION_ID)).thenReturn(validRates());
        when(requestMapper.lockBillingPeriodShared(PERIOD_ID, ORG_ID)).thenReturn("OPEN");
        when(reservationMapper.selectBudgetByIdentity(ORG_ID, PERIOD_ID, "PROJECT", 55L, "USD"))
                .thenReturn(budgetRow("ACTIVE"));
        when(reservationMapper.reserveBudgetAmount(eq(ORG_ID), eq(BUDGET_ID), any())).thenReturn(1);
        when(reservationMapper.insertActiveReservation(any()))
                .thenThrow(new DuplicateKeyException("uq_budget_reservation_route"));
        when(reservationMapper.releaseReservedBudgetAmount(eq(ORG_ID), eq(BUDGET_ID), any()))
                .thenReturn(1);

        var result = service.admitSync(command("REQUIRED"));

        assertThat(result.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
        assertThat(result.reservationId()).isEqualTo(777L);
        assertThat(result.budgetId()).isEqualTo(BUDGET_ID);
        verify(reservationMapper).releaseReservedBudgetAmount(
                eq(ORG_ID), eq(BUDGET_ID), eq(new BigDecimal("31.94880000")));
        verify(reservationMapper, never()).markRequestReserved(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void duplicateKeyWithMissingWinnerRevertsIncrementBeforeThrowing() {
        when(reservationMapper.findByRouteAttempt(ORG_ID, ATTEMPT_ID)).thenReturn(null);
        when(reservationMapper.countEffectiveHolds(ORG_ID, REQUEST_ID)).thenReturn(0);
        when(reservationMapper.findPricingRates(ORG_ID, PRICING_VERSION_ID)).thenReturn(validRates());
        when(requestMapper.lockBillingPeriodShared(PERIOD_ID, ORG_ID)).thenReturn("OPEN");
        when(reservationMapper.selectBudgetByIdentity(ORG_ID, PERIOD_ID, "PROJECT", 55L, "USD"))
                .thenReturn(budgetRow("ACTIVE"));
        when(reservationMapper.reserveBudgetAmount(eq(ORG_ID), eq(BUDGET_ID), any())).thenReturn(1);
        when(reservationMapper.insertActiveReservation(any()))
                .thenThrow(new DuplicateKeyException("uq_budget_reservation_effective"));
        when(reservationMapper.releaseReservedBudgetAmount(eq(ORG_ID), eq(BUDGET_ID), any()))
                .thenReturn(1);

        assertThatThrownBy(() -> service.admitSync(command("REQUIRED")))
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));

        verify(reservationMapper).releaseReservedBudgetAmount(
                eq(ORG_ID), eq(BUDGET_ID), eq(new BigDecimal("31.94880000")));
    }

    @Test
    void zeroAffectedRowsChecksReplayBeforeMarkingInsufficient() {
        when(reservationMapper.findByRouteAttempt(ORG_ID, ATTEMPT_ID))
                .thenReturn(null)
                .thenReturn(activeRow(888L, new BigDecimal("31.94880000")));
        when(reservationMapper.countEffectiveHolds(ORG_ID, REQUEST_ID)).thenReturn(0);
        when(reservationMapper.findPricingRates(ORG_ID, PRICING_VERSION_ID)).thenReturn(validRates());
        when(requestMapper.lockBillingPeriodShared(PERIOD_ID, ORG_ID)).thenReturn("OPEN");
        when(reservationMapper.selectBudgetByIdentity(ORG_ID, PERIOD_ID, "PROJECT", 55L, "USD"))
                .thenReturn(budgetRow("ACTIVE"));
        when(reservationMapper.reserveBudgetAmount(eq(ORG_ID), eq(BUDGET_ID), any())).thenReturn(0);

        var result = service.admitSync(command("REQUIRED"));

        assertThat(result.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
        assertThat(result.reservationId()).isEqualTo(888L);
        verify(requestMapper, never()).markRequestRejectedBudget(anyLong(), anyLong(), anyLong());
    }

    @Test
    void zeroAffectedRowsDistinguishesNonActiveBudgetFromExhaustedBudget() {
        when(reservationMapper.findByRouteAttempt(ORG_ID, ATTEMPT_ID)).thenReturn(null);
        when(reservationMapper.countEffectiveHolds(ORG_ID, REQUEST_ID)).thenReturn(0);
        when(reservationMapper.findPricingRates(ORG_ID, PRICING_VERSION_ID)).thenReturn(validRates());
        when(requestMapper.lockBillingPeriodShared(PERIOD_ID, ORG_ID)).thenReturn("OPEN");
        when(reservationMapper.selectBudgetByIdentity(ORG_ID, PERIOD_ID, "PROJECT", 55L, "USD"))
                .thenReturn(budgetRow("ACTIVE"));
        when(reservationMapper.reserveBudgetAmount(eq(ORG_ID), eq(BUDGET_ID), any())).thenReturn(0);
        when(reservationMapper.selectBudgetById(ORG_ID, BUDGET_ID)).thenReturn(budgetRow("FROZEN"));

        assertThatThrownBy(() -> service.admitSync(command("REQUIRED")))
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));

        verify(requestMapper, never()).markRequestRejectedBudget(anyLong(), anyLong(), anyLong());
    }

    @Test
    void precomputedBoundImpossibleRespectsPeriodClosedAndNoBudgetPrecedence() {
        when(reservationMapper.findByRouteAttempt(ORG_ID, ATTEMPT_ID)).thenReturn(null);
        when(reservationMapper.countEffectiveHolds(ORG_ID, REQUEST_ID)).thenReturn(0);
        when(reservationMapper.findPricingRates(ORG_ID, PRICING_VERSION_ID)).thenReturn(List.of());

        // 1. period != OPEN takes precedence over missing pricing rates.
        when(requestMapper.lockBillingPeriodShared(PERIOD_ID, ORG_ID)).thenReturn("CLOSED");
        assertThatThrownBy(() -> service.admitSync(command("REQUIRED")))
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));

        // 2. OPTIONAL without budget proceeds UNBUDGETED even when pricing rates are missing.
        when(requestMapper.lockBillingPeriodShared(PERIOD_ID, ORG_ID)).thenReturn("OPEN");
        when(reservationMapper.selectBudgetByIdentity(anyLong(), anyLong(), any(), anyLong(), any()))
                .thenReturn(null);
        assertThat(service.admitSync(command("OPTIONAL")).outcome())
                .isEqualTo(AdmissionOutcome.UNBUDGETED);

        // 3. REQUIRED without budget returns REJECTED_BUDGET (not REJECTED_DEPENDENCY).
        when(requestMapper.markRequestRejectedBudget(REQUEST_ID, ORG_ID, PERIOD_ID)).thenReturn(1);
        assertThat(service.admitSync(command("REQUIRED")).outcome())
                .isEqualTo(AdmissionOutcome.REJECTED_BUDGET);

        // 4. Active budget + impossible bound returns REJECTED_DEPENDENCY for REQUIRED and
        // REJECTED_BUDGET for OPTIONAL.
        when(reservationMapper.selectBudgetByIdentity(ORG_ID, PERIOD_ID, "PROJECT", 55L, "USD"))
                .thenReturn(budgetRow("ACTIVE"));
        assertThat(service.admitSync(command("REQUIRED")).outcome())
                .isEqualTo(AdmissionOutcome.REJECTED_DEPENDENCY);
        assertThat(service.admitSync(command("OPTIONAL")).outcome())
                .isEqualTo(AdmissionOutcome.REJECTED_BUDGET);
    }

    private AdmissionCommand command(String enforcementMode) {
        var principal = new GatewayPrincipal(
                1L, ORG_ID, 55L, "SERVICE", null, 77L, "PROJECT", 55L, enforcementMode);
        return new AdmissionCommand(
                principal, REQUEST_ID, ATTEMPT_ID, PERIOD_ID, PRICING_VERSION_ID, "USD", 8192L, -1L);
    }

    private static List<PricingRateRow> validRates() {
        return List.of(
                new PricingRateRow("INPUT_TOKEN", 1_000_000L, new BigDecimal("30.00000000")),
                new PricingRateRow("OUTPUT_TOKEN", 1_000_000L, new BigDecimal("60.00000000")));
    }

    private static BudgetRow budgetRow(String status) {
        return new BudgetRow(
                BUDGET_ID, ORG_ID, PERIOD_ID, "PROJECT", 55L, "USD",
                new BigDecimal("100.00000000"), BigDecimal.ZERO, BigDecimal.ZERO, status);
    }

    private static ReservationRow activeRow(long id, BigDecimal amount) {
        return new ReservationRow(id, "ACTIVE", amount, BUDGET_ID, PERIOD_ID, ATTEMPT_ID);
    }
}
