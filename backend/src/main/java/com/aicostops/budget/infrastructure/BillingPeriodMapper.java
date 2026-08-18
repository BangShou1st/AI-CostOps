package com.aicostops.budget.infrastructure;

import com.aicostops.budget.domain.BillingPeriod;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Row access for {@code billing_period}. */
@Mapper
public interface BillingPeriodMapper {

    String PERIOD_COLUMNS = """
            bp.id,bp.org_id,bp.period_start,bp.period_end,bp.status,bp.close_generation,
            bp.closing_started_at,bp.closed_at,bp.reopened_at,bp.version,bp.created_at,bp.updated_at
            """;

    /**
     * Up to two billing periods covering {@code at} in the half-open sense:
     * {@code period_start <= at < period_end}. Lookups are organization
     * scoped, so a foreign period is never visible. Overlapping periods are
     * not excluded by the schema, so this returns at most two candidates and
     * the caller decides: one candidate is a valid period identity, two mean
     * the organization has ambiguous covering periods and the guard must fail
     * closed. The deterministic ORDER BY only stabilizes which two rows come
     * back; the guard never picks a winner among them.
     */
    @Select("""
            SELECT
            """ + PERIOD_COLUMNS + """
            FROM billing_period bp
            WHERE bp.org_id=#{organizationId}
              AND bp.period_start <= #{at}
              AND bp.period_end > #{at}
            ORDER BY bp.period_start DESC, bp.id DESC
            LIMIT 2
            """)
    List<BillingPeriod> selectCoveringCandidates(
            @Param("organizationId") long organizationId,
            @Param("at") Instant at);

    @Select("""
            SELECT
            """ + PERIOD_COLUMNS + """
            FROM billing_period bp
            WHERE bp.org_id=#{organizationId} AND bp.id=#{periodId}
            """)
    BillingPeriod selectByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("periodId") long periodId);

    /** Row lock for callers that must serialize against Close (AIC-058+). */
    @Select("""
            SELECT
            """ + PERIOD_COLUMNS + """
            FROM billing_period bp
            WHERE bp.org_id=#{organizationId} AND bp.id=#{periodId}
            FOR UPDATE
            """)
    BillingPeriod selectByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("periodId") long periodId);
}