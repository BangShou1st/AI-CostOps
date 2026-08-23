package com.aicostops.iam.infrastructure;

import java.time.Instant;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence seams used only by the explicit local development bootstrap. */
@Mapper
public interface DevAuthenticationBootstrapMapper {

    @Insert("""
            INSERT INTO billing_period(
                org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
            SELECT #{organizationId},#{periodStart},#{periodEnd},'OPEN',0,0,#{now},#{now}
            WHERE NOT EXISTS (
                SELECT 1 FROM billing_period
                WHERE org_id=#{organizationId} AND period_start=#{periodStart} AND period_end=#{periodEnd})
            """)
    int insertOpenBillingPeriodIfMissing(
            @Param("organizationId") long organizationId,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("periodEnd") LocalDateTime periodEnd,
            @Param("now") Instant now);
}
