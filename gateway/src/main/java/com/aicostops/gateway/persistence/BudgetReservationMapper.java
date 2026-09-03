package com.aicostops.gateway.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * M12 MySQL-authoritative budget reservation writes and locked budget reads.
 * Gateway MAY read/lock BillingPeriod and Budget and write budget_reservation;
 * it MUST NOT mutate actual/committed/Ledger/Settlement (no such statement
 * exists in this mapper by design).
 */
@Mapper
public interface BudgetReservationMapper {

    @Select("""
            SELECT id, org_id, billing_period_id, scope_type, scope_id, currency,
                   total_amount, actual_amount, committed_amount, status
            FROM budget
            WHERE org_id=#{orgId} AND billing_period_id=#{periodId}
              AND scope_type=#{scopeType} AND scope_id=#{scopeId}
              AND currency=#{currency}
            """)
    BudgetRow selectBudgetByIdentity(
            @Param("orgId") long orgId,
            @Param("periodId") long periodId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") long scopeId,
            @Param("currency") String currency);

    @Select("""
            SELECT id, org_id, billing_period_id, scope_type, scope_id, currency,
                   total_amount, actual_amount, committed_amount, status
            FROM budget
            WHERE org_id=#{orgId} AND id=#{budgetId}
            FOR UPDATE
            """)
    BudgetRow lockBudgetById(@Param("orgId") long orgId, @Param("budgetId") long budgetId);

    @Select("""
            SELECT COALESCE(SUM(reserved_amount), 0)
            FROM budget_reservation
            WHERE org_id=#{orgId} AND budget_id=#{budgetId}
              AND status IN ('ACTIVE','PENDING_HOLD')
            """)
    BigDecimal sumEffectiveReservations(
            @Param("orgId") long orgId, @Param("budgetId") long budgetId);

    @Select("""
            SELECT dimension_code, unit_quantity, unit_price
            FROM pricing_rate
            WHERE org_id=#{orgId} AND pricing_version_id=#{pricingVersionId}
            """)
    java.util.List<PricingRateRow> findPricingRates(
            @Param("orgId") long orgId, @Param("pricingVersionId") long pricingVersionId);

    @Select("""
            SELECT id, status, reserved_amount, budget_id, billing_period_id, route_attempt_id
            FROM budget_reservation
            WHERE org_id=#{orgId} AND route_attempt_id=#{routeAttemptId}
            """)
    ReservationRow findByRouteAttempt(
            @Param("orgId") long orgId, @Param("routeAttemptId") long routeAttemptId);

    @Select("""
            SELECT COUNT(*)
            FROM budget_reservation
            WHERE org_id=#{orgId} AND request_id=#{requestId}
              AND status IN ('ACTIVE','PENDING_HOLD')
            """)
    int countEffectiveHolds(@Param("orgId") long orgId, @Param("requestId") long requestId);

    @Insert("""
            INSERT INTO budget_reservation(
              org_id,request_id,route_attempt_id,billing_period_id,budget_id,
              financial_scope_type,financial_scope_id,currency,
              reserved_amount,commitment_id,commitment_backed_amount,
              status,version,expires_at,created_at,updated_at,released_at,finalized_at)
            VALUES (#{orgId},#{requestId},#{routeAttemptId},#{periodId},#{budgetId},
              #{scopeType},#{scopeId},#{currency},
              #{reservedAmount},NULL,0,
              'ACTIVE',0,#{expiresAt},UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
            """)
    int insertActiveReservation(ReservationInsert insert);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE gateway_request
            SET state='RESERVED', billing_period_id=#{periodId},
                current_route_attempt_id=#{attemptId}, updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId} AND state='VALIDATED'
            """)
    int markRequestReserved(
            @Param("requestId") long requestId,
            @Param("orgId") long orgId,
            @Param("periodId") long periodId,
            @Param("attemptId") long attemptId);

    @Update("""
            UPDATE gateway_request
            SET state='REJECTED_BUDGET', billing_period_id=#{periodId},
                updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId} AND state='VALIDATED'
            """)
    int markRequestRejectedBudget(
            @Param("requestId") long requestId,
            @Param("orgId") long orgId,
            @Param("periodId") long periodId);

    @Update("""
            UPDATE gateway_request
            SET state='FAILED_PRE_DISPATCH', terminal_at=UTC_TIMESTAMP(6),
                updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId}
              AND state IN ('VALIDATED','RESERVED')
            """)
    int markRequestFailedPreDispatch(
            @Param("requestId") long requestId, @Param("orgId") long orgId);

    @Update("""
            UPDATE budget_reservation
            SET status='RELEASED', version=version+1,
                released_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{reservationId} AND org_id=#{orgId}
              AND version=#{expectedVersion} AND status='ACTIVE'
            """)
    int releaseActiveReservation(
            @Param("reservationId") long reservationId,
            @Param("orgId") long orgId,
            @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE budget_reservation
            SET status='PENDING_HOLD', version=version+1, updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{reservationId} AND org_id=#{orgId}
              AND version=#{expectedVersion} AND status='ACTIVE'
            """)
    int holdActiveReservation(
            @Param("reservationId") long reservationId,
            @Param("orgId") long orgId,
            @Param("expectedVersion") long expectedVersion);

    record BudgetRow(
            long id,
            long orgId,
            long billingPeriodId,
            String scopeType,
            long scopeId,
            String currency,
            BigDecimal totalAmount,
            BigDecimal actualAmount,
            BigDecimal committedAmount,
            String status) {
    }

    record PricingRateRow(String dimensionCode, long unitQuantity, BigDecimal unitPrice) {
    }

    record ReservationRow(
            long id,
            String status,
            BigDecimal reservedAmount,
            long budgetId,
            long billingPeriodId,
            long routeAttemptId) {
    }

    record ReservationInsert(
            long orgId,
            long requestId,
            long routeAttemptId,
            long periodId,
            long budgetId,
            String scopeType,
            long scopeId,
            String currency,
            BigDecimal reservedAmount,
            Instant expiresAt) {
    }
}
