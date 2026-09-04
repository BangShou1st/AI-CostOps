package com.aicostops.gateway.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Org-qualified MyBatis persistence for append-only Gateway usage facts. */
@Mapper
public interface GatewayUsageMapper {

    @Select("""
            SELECT gr.state AS request_state, gr.current_usage_fact_id,
                   gr.dispatch_intent_at, ra.status AS route_status,
                   ra.pricing_version_id, pv.currency
            FROM gateway_request gr
            JOIN gateway_route_attempt ra
              ON ra.id=#{attemptId} AND ra.org_id=#{orgId}
             AND ra.request_id=gr.id
            JOIN pricing_version pv
              ON pv.id=ra.pricing_version_id AND pv.org_id=ra.org_id
            WHERE gr.id=#{requestId} AND gr.org_id=#{orgId}
            FOR UPDATE
            """)
    LineageRow lockLineage(
            @Param("orgId") long orgId,
            @Param("requestId") long requestId,
            @Param("attemptId") long attemptId);

    @Select("""
            SELECT dimension_code
            FROM pricing_rate
            WHERE org_id=#{orgId} AND pricing_version_id=#{pricingVersionId}
            ORDER BY dimension_code
            """)
    List<String> findPricingDimensions(
            @Param("orgId") long orgId,
            @Param("pricingVersionId") long pricingVersionId);

    @Select("""
            SELECT id, sequence, status
            FROM gateway_usage_fact
            WHERE id=#{factId} AND org_id=#{orgId}
            """)
    FactRow findFact(@Param("orgId") long orgId, @Param("factId") long factId);

    @Insert("""
            INSERT INTO gateway_usage_fact(
              org_id,request_id,route_attempt_id,sequence,status,supersedes_usage_fact_id,
              provider_request_id,usage_effective_at,usage_effective_at_source,
              pricing_version_id,currency,safe_provider_metadata_json,observed_at,created_at)
            VALUES (#{orgId},#{requestId},#{routeAttemptId},#{sequence},#{status},
              #{supersedesUsageFactId},#{providerRequestId},#{usageEffectiveAt},
              #{usageEffectiveAtSource},#{pricingVersionId},#{currency},
              #{safeProviderMetadataJson},#{observedAt},#{createdAt})
            """)
    int insertFact(UsageFactInsert insert);

    @Insert("""
            INSERT INTO gateway_usage_dimension(
              org_id,usage_fact_id,dimension_code,quantity,provenance)
            VALUES (#{orgId},#{usageFactId},#{dimensionCode},#{quantity},#{provenance})
            """)
    int insertDimension(DimensionInsert insert);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE gateway_request
            SET current_usage_fact_id=#{factId}, updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId}
            """)
    int updateCurrentUsageFact(
            @Param("orgId") long orgId,
            @Param("requestId") long requestId,
            @Param("factId") long factId);

    record LineageRow(
            String requestState,
            Long currentUsageFactId,
            Instant dispatchIntentAt,
            String routeStatus,
            long pricingVersionId,
            String currency) {
    }

    record FactRow(long id, int sequence, String status) {
    }

    record UsageFactInsert(
            long orgId,
            long requestId,
            long routeAttemptId,
            int sequence,
            String status,
            Long supersedesUsageFactId,
            String providerRequestId,
            Instant usageEffectiveAt,
            String usageEffectiveAtSource,
            long pricingVersionId,
            String currency,
            String safeProviderMetadataJson,
            Instant observedAt,
            Instant createdAt) {
    }

    record DimensionInsert(
            long orgId,
            long usageFactId,
            String dimensionCode,
            java.math.BigDecimal quantity,
            String provenance) {
    }
}
