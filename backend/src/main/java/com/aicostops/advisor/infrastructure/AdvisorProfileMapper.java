package com.aicostops.advisor.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aicostops.advisor.infrastructure.AdvisorRows.ProfileRow;

@Mapper
public interface AdvisorProfileMapper {
    @Select("SELECT id,org_id,version,provider_model_id,project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,status,created_by,created_at,updated_at FROM advisor_profile WHERE org_id=#{organizationId} AND status='ACTIVE' LIMIT 1")
    ProfileRow findActiveProfile(@Param("organizationId") long organizationId);

    @Select("SELECT id,org_id,version,provider_model_id,project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,status,created_by,created_at,updated_at FROM advisor_profile WHERE id=#{id} AND org_id=#{organizationId}")
    ProfileRow findProfile(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT COALESCE(MAX(version),0)+1 FROM advisor_profile WHERE org_id=#{organizationId}")
    int nextProfileVersion(@Param("organizationId") long organizationId);

    @Insert("""
            INSERT INTO advisor_profile(org_id,version,provider_model_id,project_id,financial_scope_type,
              financial_scope_id,budget_enforcement_mode,status,created_by,created_at,updated_at)
            VALUES(#{organizationId},#{version},#{providerModelId},#{projectId},#{scopeType},
              #{scopeId},#{budgetMode},'ACTIVE',#{createdBy},#{now},#{now})
            """)
    int insertProfile(@Param("organizationId") long organizationId, @Param("version") int version,
            @Param("providerModelId") long providerModelId, @Param("projectId") long projectId,
            @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
            @Param("budgetMode") String budgetMode, @Param("createdBy") long createdBy,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("UPDATE advisor_profile SET status='RETIRED',updated_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND status='ACTIVE'")
    int retireProfile(@Param("id") long id, @Param("organizationId") long organizationId, @Param("now") Instant now);

    /** Org-visible Provider Model status: global OR same-org private, ACTIVE, routable. */
    @Select("""
            SELECT pm.status FROM provider_model pm
            JOIN model_catalog mc ON mc.id = pm.model_id
            WHERE pm.id = #{providerModelId}
              AND (pm.owner_org_id IS NULL OR pm.owner_org_id = #{organizationId})
              AND (mc.owner_org_id IS NULL OR mc.owner_org_id = #{organizationId})
              AND (pm.provider_account_id IS NULL OR EXISTS(
                SELECT 1 FROM provider_account pa
                WHERE pa.id = pm.provider_account_id AND pa.org_id = #{organizationId} AND pa.status = 'ACTIVE'))
            """)
    String findOrgVisibleProviderModelStatus(@Param("providerModelId") long providerModelId,
            @Param("organizationId") long organizationId);

    @Select("SELECT status FROM project WHERE id=#{projectId} AND org_id=#{organizationId}")
    String findProjectStatus(@Param("projectId") long projectId, @Param("organizationId") long organizationId);

    @Select("SELECT status FROM team WHERE id=#{scopeId} AND org_id=#{organizationId}")
    String findTeamStatus(@Param("scopeId") long scopeId, @Param("organizationId") long organizationId);

    @Select("SELECT status FROM cost_center WHERE id=#{scopeId} AND org_id=#{organizationId}")
    String findCostCenterStatus(@Param("scopeId") long scopeId, @Param("organizationId") long organizationId);

    @Select("SELECT id FROM service_identity WHERE org_id=#{organizationId} AND code='AICOSTOPS_ADVISOR' LIMIT 1")
    Long findAdvisorIdentity(@Param("organizationId") long organizationId);

    @Insert("INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at) VALUES(#{organizationId},'AICOSTOPS_ADVISOR','AI CostOps Advisor','ACTIVE',#{now},#{now}) ON DUPLICATE KEY UPDATE updated_at=#{now}")
    int ensureAdvisorIdentity(@Param("organizationId") long organizationId, @Param("now") Instant now);

    @Select("SELECT id FROM gateway_credential WHERE org_id=#{organizationId} AND service_identity_id=#{serviceIdentityId} AND credential_origin='INTERNAL_SYSTEM' AND status='ACTIVE' LIMIT 1")
    Long findInternalCredential(@Param("organizationId") long organizationId, @Param("serviceIdentityId") long serviceIdentityId);

    /** Current ACTIVE profile-bound credential for the advisor identity (P0 exact binding). */
    @Select("""
            SELECT gc.id FROM gateway_credential gc
            JOIN advisor_profile ap ON ap.id = gc.advisor_profile_id AND ap.org_id = gc.org_id
            WHERE gc.org_id = #{organizationId} AND gc.service_identity_id = #{serviceIdentityId}
              AND gc.credential_origin = 'INTERNAL_SYSTEM' AND gc.status = 'ACTIVE'
              AND ap.status = 'ACTIVE' LIMIT 1
            """)
    Long findBoundInternalCredential(@Param("organizationId") long organizationId,
            @Param("serviceIdentityId") long serviceIdentityId);

    @Select("""
            SELECT gc.id FROM gateway_credential gc
            WHERE gc.org_id = #{organizationId} AND gc.service_identity_id = #{serviceIdentityId}
              AND gc.credential_origin = 'INTERNAL_SYSTEM' AND gc.status = 'ACTIVE'
            """)
    java.util.List<Long> listActiveInternalCredentials(@Param("organizationId") long organizationId,
            @Param("serviceIdentityId") long serviceIdentityId);

    @Update("UPDATE gateway_credential SET status='REVOKED', revoked_at=#{now}, updated_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND status='ACTIVE'")
    int revokeInternalCredential(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("now") Instant now);

    @Update("UPDATE gateway_credential_model SET status='DISABLED' WHERE credential_id=#{credentialId} AND org_id=#{organizationId} AND status='ACTIVE'")
    int disableCredentialModels(@Param("credentialId") long credentialId,
            @Param("organizationId") long organizationId);

    @Insert("""
            INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,secret_digest_version,
              principal_type,credential_origin,organization_member_id,service_identity_id,project_id,
              financial_scope_type,financial_scope_id,budget_enforcement_mode,status,expires_at,
              predecessor_credential_id,advisor_profile_id,created_at,updated_at,revoked_at)
            VALUES(#{organizationId},#{prefix},#{digest},1,'SERVICE','INTERNAL_SYSTEM',NULL,
              #{serviceIdentityId},#{projectId},#{scopeType},#{scopeId},#{budgetMode},'ACTIVE',NULL,
              #{predecessorId},#{profileId},#{now},#{now},NULL)
            """)
    int insertBoundInternalCredential(@Param("organizationId") long organizationId, @Param("prefix") String prefix,
            @Param("digest") byte[] digest, @Param("serviceIdentityId") long serviceIdentityId,
            @Param("projectId") long projectId, @Param("scopeType") String scopeType,
            @Param("scopeId") long scopeId, @Param("budgetMode") String budgetMode,
            @Param("predecessorId") Long predecessorId, @Param("profileId") long profileId,
            @Param("now") Instant now);

    @Insert("""
            INSERT IGNORE INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
            VALUES(#{credentialId},#{organizationId},#{modelId},'ACTIVE',#{now})
            """)
    int allowCredentialModel(@Param("credentialId") long credentialId, @Param("organizationId") long organizationId,
            @Param("modelId") long modelId, @Param("now") Instant now);

    /** Org-visible logical model for a provider model (global OR same-org private). */
    @Select("""
            SELECT pm.model_id FROM provider_model pm
            JOIN model_catalog mc ON mc.id = pm.model_id
            WHERE pm.id = #{providerModelId}
              AND (pm.owner_org_id IS NULL OR pm.owner_org_id = #{organizationId})
              AND (mc.owner_org_id IS NULL OR mc.owner_org_id = #{organizationId})
            """)
    Long findOrgVisibleLogicalModelOf(@Param("providerModelId") long providerModelId,
            @Param("organizationId") long organizationId);
}
