package com.aicostops.routing.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RoutingPolicyMapper {

    @Select("""
            SELECT id,org_id AS organization_id,project_id,model_id,version,status
            FROM routing_policy
            WHERE org_id=#{organizationId}
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PolicyRow> findPage(@Param("organizationId") long organizationId,
            @Param("offset") long offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM routing_policy WHERE org_id=#{organizationId}")
    long count(@Param("organizationId") long organizationId);

    @Select("""
            SELECT id,org_id AS organization_id,project_id,model_id,version,status
            FROM routing_policy
            WHERE id=#{policyId} AND org_id=#{organizationId}
            """)
    PolicyRow find(@Param("policyId") long policyId, @Param("organizationId") long organizationId);

    @Select("""
            SELECT id,org_id AS organization_id,project_id,model_id,version,status
            FROM routing_policy
            WHERE id=#{policyId} AND org_id=#{organizationId}
            FOR UPDATE
            """)
    PolicyRow findForUpdate(@Param("policyId") long policyId, @Param("organizationId") long organizationId);

    @Select("""
            SELECT id,org_id AS organization_id,project_id,model_id,version,status
            FROM routing_policy
            WHERE org_id=#{organizationId} AND project_id <=> #{projectId}
              AND model_id=#{modelId} AND status='ACTIVE'
            LIMIT 1
            """)
    PolicyRow findActiveExactScope(@Param("organizationId") long organizationId,
            @Param("projectId") Long projectId, @Param("modelId") long modelId);

    @Select("""
            SELECT id,org_id AS organization_id,project_id,model_id,version,status
            FROM routing_policy
            WHERE org_id=#{organizationId} AND project_id <=> #{projectId}
              AND model_id=#{modelId}
            ORDER BY version DESC
            LIMIT 1
            """)
    PolicyRow findLatestExactScope(@Param("organizationId") long organizationId,
            @Param("projectId") Long projectId, @Param("modelId") long modelId);

    @Select("""
            SELECT id,org_id AS organization_id,project_id,model_id,version,status
            FROM routing_policy
            WHERE org_id=#{organizationId} AND project_id IS NULL
              AND model_id=#{modelId} AND status='ACTIVE'
            LIMIT 1
            """)
    PolicyRow findActiveOrganizationDefault(@Param("organizationId") long organizationId,
            @Param("modelId") long modelId);

    @Select("""
            SELECT id,provider_account_id,provider_model_id,priority,status,privacy_region_code
            FROM routing_policy_candidate
            WHERE routing_policy_id=#{policyId} AND org_id=#{organizationId}
            ORDER BY priority ASC,id ASC
            """)
    List<CandidateRow> findCandidates(@Param("policyId") long policyId,
            @Param("organizationId") long organizationId);

    @Insert("""
            INSERT INTO routing_policy(org_id,project_id,model_id,version,status,created_at,activated_at)
            VALUES(#{organizationId},#{projectId},#{modelId},#{version},'DRAFT',#{now},NULL)
            """)
    int insertPolicy(@Param("organizationId") long organizationId, @Param("projectId") Long projectId,
            @Param("modelId") long modelId, @Param("version") int version, @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Insert("""
            INSERT INTO routing_policy_candidate(
              org_id,routing_policy_id,provider_account_id,provider_model_id,priority,status,
              privacy_region_code,created_at)
            VALUES(#{organizationId},#{policyId},#{providerAccountId},#{providerModelId},#{priority},
              #{status},#{privacyRegionCode},#{now})
            """)
    int insertCandidate(@Param("organizationId") long organizationId, @Param("policyId") long policyId,
            @Param("providerAccountId") long providerAccountId,
            @Param("providerModelId") long providerModelId, @Param("priority") int priority,
            @Param("status") String status, @Param("privacyRegionCode") String privacyRegionCode,
            @Param("now") Instant now);

    @Select("SELECT COALESCE(MAX(version),0)+1 FROM routing_policy WHERE org_id=#{organizationId} AND project_id <=> #{projectId} AND model_id=#{modelId}")
    int nextVersion(@Param("organizationId") long organizationId, @Param("projectId") Long projectId,
            @Param("modelId") long modelId);

    @Select("SELECT id FROM organization WHERE id=#{organizationId} FOR UPDATE")
    Long lockOrganization(@Param("organizationId") long organizationId);

    @Select("SELECT id FROM project WHERE id=#{projectId} AND org_id=#{organizationId} AND status='ACTIVE'")
    Long findActiveProject(@Param("organizationId") long organizationId, @Param("projectId") long projectId);

    @Select("SELECT id FROM model_catalog WHERE id=#{modelId} AND status='ACTIVE'")
    Long findActiveModel(@Param("modelId") long modelId);

    @Select("SELECT provider_code FROM provider_account WHERE id=#{providerAccountId} AND org_id=#{organizationId} AND status='ACTIVE'")
    String findActiveAccountProviderCode(@Param("organizationId") long organizationId,
            @Param("providerAccountId") long providerAccountId);

    @Select("SELECT provider_code FROM provider_model WHERE id=#{providerModelId} AND model_id=#{modelId} AND status='ACTIVE' AND routing_eligible=TRUE")
    String findEligibleProviderModelCode(@Param("providerModelId") long providerModelId,
            @Param("modelId") long modelId);

    @Select("SELECT EXISTS(SELECT 1 FROM provider_catalog WHERE provider_code=#{providerCode} AND status='ACTIVE')")
    boolean isActiveProviderCatalog(@Param("providerCode") String providerCode);

    @Select("SELECT EXISTS(SELECT 1 FROM provider_credential WHERE org_id=#{organizationId} AND provider_account_id=#{providerAccountId} AND status='ACTIVE')")
    boolean hasActiveCredential(@Param("organizationId") long organizationId,
            @Param("providerAccountId") long providerAccountId);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM pricing_version pv
              WHERE pv.org_id=#{organizationId} AND pv.provider_account_id=#{providerAccountId}
                AND pv.provider_model_id=#{providerModelId} AND pv.status='ACTIVE'
                AND pv.effective_from <= #{now}
                AND (pv.effective_to IS NULL OR pv.effective_to > #{now}))
            """)
    boolean hasCurrentPricing(@Param("organizationId") long organizationId,
            @Param("providerAccountId") long providerAccountId,
            @Param("providerModelId") long providerModelId, @Param("now") Instant now);

    @Select("""
            SELECT pa.id AS provider_account_id,pa.display_name,pa.provider_code,
                   pm.id AS provider_model_id,pm.provider_model_name,pm.routing_eligible,
                   EXISTS(SELECT 1 FROM provider_credential pc2
                          WHERE pc2.org_id=pa.org_id AND pc2.provider_account_id=pa.id AND pc2.status='ACTIVE') AS credential_ready,
                   EXISTS(SELECT 1 FROM pricing_version pv
                          WHERE pv.org_id=pa.org_id AND pv.provider_account_id=pa.id
                            AND pv.provider_model_id=pm.id AND pv.status='ACTIVE'
                            AND pv.effective_from <= #{now}
                            AND (pv.effective_to IS NULL OR pv.effective_to > #{now})) AS pricing_ready,
                   COALESCE((SELECT GROUP_CONCAT(DISTINCT pv2.currency ORDER BY pv2.currency SEPARATOR ',')
                          FROM pricing_version pv2
                          WHERE pv2.org_id=pa.org_id AND pv2.provider_account_id=pa.id
                            AND pv2.provider_model_id=pm.id AND pv2.status='ACTIVE'
                            AND pv2.effective_from <= #{now}
                            AND (pv2.effective_to IS NULL OR pv2.effective_to > #{now})), '') AS currencies
            FROM provider_account pa
            JOIN provider_catalog pc ON pc.provider_code=pa.provider_code AND pc.status='ACTIVE'
            JOIN provider_model pm ON pm.provider_code=pa.provider_code
              AND pm.model_id=#{modelId} AND pm.status='ACTIVE'
            WHERE pa.org_id=#{organizationId} AND pa.status='ACTIVE'
            ORDER BY pa.id ASC,pm.id ASC
            """)
    List<RouteOptionRow> findRouteOptions(@Param("organizationId") long organizationId,
            @Param("modelId") long modelId, @Param("now") Instant now);

    @Update("UPDATE routing_policy SET status='RETIRED' WHERE org_id=#{organizationId} AND project_id <=> #{projectId} AND model_id=#{modelId} AND status='ACTIVE'")
    int retireActiveExactScope(@Param("organizationId") long organizationId, @Param("projectId") Long projectId,
            @Param("modelId") long modelId);

    @Update("UPDATE routing_policy SET status='ACTIVE',activated_at=#{now} WHERE id=#{policyId} AND org_id=#{organizationId} AND status='DRAFT'")
    int activateDraft(@Param("organizationId") long organizationId, @Param("policyId") long policyId,
            @Param("now") Instant now);

    @Delete("DELETE FROM routing_policy_candidate WHERE routing_policy_id=#{policyId} AND org_id=#{organizationId}")
    int deleteCandidates(@Param("organizationId") long organizationId, @Param("policyId") long policyId);

    record PolicyRow(long id, long organizationId, Long projectId, long modelId, int version, String status) {
    }

    record CandidateRow(long id, long providerAccountId, long providerModelId, int priority,
            String status, String privacyRegionCode) {
    }

    record RouteOptionRow(long providerAccountId, String displayName, String providerCode,
            long providerModelId, String providerModelName, boolean routingEligible,
            boolean credentialReady, boolean pricingReady, String currencies) {
    }
}
