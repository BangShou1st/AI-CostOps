package com.aicostops.budget.infrastructure;

import com.aicostops.budget.domain.BillingPeriod;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("SELECT id FROM organization WHERE id=#{organizationId} FOR UPDATE")
    Long lockOrganization(@Param("organizationId") long organizationId);

    @Select("""
            SELECT COUNT(*) FROM billing_period
            WHERE org_id=#{organizationId} AND status='CLOSING'
            """)
    int countClosingByOrganization(@Param("organizationId") long organizationId);

    @Update("""
            UPDATE billing_period
            SET status='CLOSING',closing_started_at=#{now},version=version+1,updated_at=#{now}
            WHERE id=#{periodId} AND org_id=#{organizationId}
              AND status='OPEN' AND version=#{expectedVersion}
            """)
    int markClosing(
            @Param("organizationId") long organizationId,
            @Param("periodId") long periodId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    @Update("""
            UPDATE billing_period
            SET status='OPEN',closing_started_at=NULL,version=version+1,updated_at=#{now}
            WHERE id=#{periodId} AND org_id=#{organizationId}
              AND status='CLOSING' AND version=#{expectedVersion}
            """)
    int returnOpen(
            @Param("organizationId") long organizationId,
            @Param("periodId") long periodId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    @Update("""
            UPDATE billing_period
            SET status='CLOSED',closed_at=#{now},version=version+1,updated_at=#{now}
            WHERE id=#{periodId} AND org_id=#{organizationId}
              AND status='CLOSING' AND version=#{expectedVersion}
            """)
    int markClosed(
            @Param("organizationId") long organizationId,
            @Param("periodId") long periodId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    @Update("""
            UPDATE billing_period
            SET status='OPEN',close_generation=close_generation+1,
                closing_started_at=NULL,reopened_at=#{now},version=version+1,updated_at=#{now}
            WHERE id=#{periodId} AND org_id=#{organizationId}
              AND status='CLOSED' AND version=#{expectedVersion}
            """)
    int reopen(
            @Param("organizationId") long organizationId,
            @Param("periodId") long periodId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);
}
