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

    /** Same covering lookup, but locks candidates for a financial write. */
    @Select("""
            SELECT
            """ + PERIOD_COLUMNS + """
            FROM billing_period bp
            WHERE bp.org_id=#{organizationId}
              AND bp.period_start <= #{at}
              AND bp.period_end > #{at}
            ORDER BY bp.period_start DESC, bp.id DESC
            LIMIT 2
            FOR UPDATE
            """)
    List<BillingPeriod> selectCoveringCandidatesForUpdate(
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

    @Select("""
            SELECT
            """ + PERIOD_COLUMNS + """
            FROM billing_period bp
            WHERE bp.org_id=#{organizationId}
            ORDER BY bp.period_start DESC, bp.id DESC
            """)
    List<BillingPeriod> selectByOrganization(@Param("organizationId") long organizationId);

    /** Row lock for callers that must serialize against Close. */
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

    /** Organization admission lock for unknown-period financial truth. */
    @Select("SELECT id FROM organization WHERE id=#{organizationId} FOR UPDATE")
    Long lockOrganization(@Param("organizationId") long organizationId);

    @Select("""
            SELECT COUNT(*) FROM billing_period
            WHERE org_id=#{organizationId} AND status='CLOSING'
            """)
    int countClosingByOrganization(@Param("organizationId") long organizationId);
}
