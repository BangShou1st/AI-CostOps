package com.aicostops.budget.infrastructure;

import com.aicostops.budget.domain.BillingPeriod;
import java.time.Instant;
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
     * The billing period covering {@code at} in the half-open sense:
     * {@code period_start <= at < period_end}. Lookups are organization
     * scoped, so a foreign period is never visible. Overlapping periods are
     * not excluded by the schema; the latest-starting, highest-id period is
     * selected deterministically.
     */
    @Select("""
            SELECT
            """ + PERIOD_COLUMNS + """
            FROM billing_period bp
            WHERE bp.org_id=#{organizationId}
              AND bp.period_start <= #{at}
              AND bp.period_end > #{at}
            ORDER BY bp.period_start DESC, bp.id DESC
            LIMIT 1
            """)
    BillingPeriod selectCovering(
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