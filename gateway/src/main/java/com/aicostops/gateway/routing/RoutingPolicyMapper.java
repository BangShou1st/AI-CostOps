package com.aicostops.gateway.routing;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoutingPolicyMapper {

    @Select("""
            SELECT id,version,project_id,model_id
            FROM routing_policy
            WHERE org_id=#{orgId} AND project_id <=> #{projectId}
              AND model_id=#{modelId} AND status='ACTIVE'
            LIMIT 1
            """)
    PolicyRow findActiveExact(@Param("orgId") long orgId, @Param("projectId") Long projectId,
            @Param("modelId") long modelId);

    @Select("""
            SELECT id,version,project_id,model_id
            FROM routing_policy
            WHERE org_id=#{orgId} AND project_id IS NULL
              AND model_id=#{modelId} AND status='ACTIVE'
            LIMIT 1
            """)
    PolicyRow findActiveOrganizationDefault(@Param("orgId") long orgId, @Param("modelId") long modelId);

    @Select("""
            SELECT rpc.id,rpc.priority,
                   rpc.provider_account_id,
                   pa.provider_code,
                   rpc.provider_model_id,
                   pm.provider_model_name,
                   pv.id AS pricing_version_id,
                   pv.currency,
                   pc.base_url,
                   pc.adapter_code,
                   (pa.status='ACTIVE' AND EXISTS(SELECT 1 FROM provider_credential pcred
                          WHERE pcred.org_id=rpc.org_id
                            AND pcred.provider_account_id=rpc.provider_account_id
                            AND pcred.status='ACTIVE')) AS credential_ready,
                   (pm.status='ACTIVE' AND COALESCE(pm.routing_eligible,FALSE)) AS routing_eligible,
                   JSON_CONTAINS(pm.capabilities_json, JSON_QUOTE('CHAT_COMPLETIONS'), '$.capabilities') AS chat_capable,
                   JSON_CONTAINS(pm.capabilities_json, JSON_QUOTE('SSE_STREAMING'), '$.capabilities') AS stream_capable
            FROM routing_policy_candidate rpc
            LEFT JOIN provider_account pa
              ON pa.id=rpc.provider_account_id AND pa.org_id=rpc.org_id
            LEFT JOIN provider_model pm
              ON pm.id=rpc.provider_model_id
            LEFT JOIN provider_catalog pc
              ON pc.provider_code=pa.provider_code AND pc.status='ACTIVE'
            LEFT JOIN pricing_version pv
              ON pv.id=(
                SELECT pv2.id FROM pricing_version pv2
                WHERE pv2.org_id=rpc.org_id
                  AND pv2.provider_account_id=rpc.provider_account_id
                  AND pv2.provider_model_id=rpc.provider_model_id
                  AND pv2.status='ACTIVE'
                  AND pv2.effective_from <= #{now}
                  AND (pv2.effective_to IS NULL OR pv2.effective_to > #{now})
                ORDER BY pv2.effective_from DESC,pv2.id DESC LIMIT 1)
            WHERE rpc.org_id=#{orgId} AND rpc.routing_policy_id=#{policyId}
              AND rpc.status='ACTIVE'
            ORDER BY rpc.priority ASC,rpc.id ASC
            """)
    List<CandidateRow> findCandidates(@Param("orgId") long orgId, @Param("policyId") long policyId,
            @Param("now") Instant now);

    @Select("""
            SELECT pv.id AS pricing_version_id, pv.currency
            FROM pricing_version pv
            WHERE pv.org_id=#{orgId}
              AND pv.provider_account_id=#{providerAccountId}
              AND pv.provider_model_id=#{providerModelId}
              AND pv.status='ACTIVE'
              AND pv.effective_from <= #{now}
              AND (pv.effective_to IS NULL OR pv.effective_to > #{now})
            ORDER BY pv.effective_from DESC,pv.id DESC
            LIMIT 1
            """)
    CandidatePricingRow findCurrentPricing(
            @Param("orgId") long orgId,
            @Param("providerAccountId") long providerAccountId,
            @Param("providerModelId") long providerModelId,
            @Param("now") Instant now);

    record PolicyRow(long id, int version, Long projectId, long modelId) {
    }

    record CandidateRow(long id, int priority, Long providerAccountId, String providerCode,
            Long providerModelId, String providerModelName, Long pricingVersionId, String currency,
            String baseUrl, String adapterCode, boolean credentialReady, boolean routingEligible,
            boolean chatCapable, boolean streamCapable) {
    }

    record CandidatePricingRow(Long pricingVersionId, String currency) {
    }
}
