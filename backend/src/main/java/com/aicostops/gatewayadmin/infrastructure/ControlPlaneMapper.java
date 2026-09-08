package com.aicostops.gatewayadmin.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Read/write seams of the governed Control Plane surface (Service Identity,
 * Gateway Credential, Model/Pricing). Every org-owned query is scoped by
 * {@code org_id}; raw secrets are never read back (the digest column is never
 * selected). Same-organization integrity follows the V18 convention.
 */
@Mapper
public interface ControlPlaneMapper {

    record ServiceIdentityRow(long id, long orgId, String code, String name,
            String status, Instant createdAt, Instant updatedAt) {
    }

    record CredentialRow(long id, long orgId, String prefix, String principalType,
            Long serviceIdentityId, Long organizationMemberId, long projectId,
            String financialScopeType, long financialScopeId, String budgetEnforcementMode,
            String status, Instant expiresAt, Instant createdAt, Instant revokedAt) {
    }

    record PricingVersionRow(long id, long orgId, long providerAccountId, long providerModelId,
            int version, String currency, String status, Instant effectiveFrom,
            Instant effectiveTo, Instant createdAt, Instant activatedAt, Instant retiredAt) {
    }

    record PricingRateRow(long id, long orgId, long pricingVersionId, String dimensionCode,
            long unitQuantity, BigDecimal unitPrice) {
    }

    record ModelRow(long id, String modelKey, String name, String status) {
    }

    record ProviderModelRow(long id, String providerCode, long modelId,
            String providerModelName, String status, boolean routingEligible) {
    }

    record ProviderAccountRow(long id, String providerCode, String displayName, String status) {
    }

    // ------------------------------------------------------------------ //
    // Service Identity
    // ------------------------------------------------------------------ //

    @Select("""
            SELECT id,org_id,code,name,status,created_at,updated_at
            FROM service_identity
            WHERE org_id=#{orgId}
            ORDER BY id DESC
            """)
    List<ServiceIdentityRow> selectServiceIdentities(@Param("orgId") long orgId);

    @Select("""
            SELECT id,org_id,code,name,status,created_at,updated_at
            FROM service_identity
            WHERE org_id=#{orgId} AND id=#{id}
            """)
    ServiceIdentityRow selectServiceIdentity(@Param("orgId") long orgId, @Param("id") long id);

    @Select("""
            SELECT status FROM service_identity
            WHERE org_id=#{orgId} AND id=#{id}
            """)
    String selectServiceIdentityStatus(@Param("orgId") long orgId, @Param("id") long id);

    @Insert("""
            INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
            VALUES (#{orgId},#{code},#{name},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """)
    int insertServiceIdentity(@Param("orgId") long orgId, @Param("code") String code,
            @Param("name") String name);

    // ------------------------------------------------------------------ //
    // Gateway Credential
    // ------------------------------------------------------------------ //

    @Select("""
            SELECT id,org_id,credential_prefix AS prefix,principal_type,
                   service_identity_id,organization_member_id,project_id,financial_scope_type,
                   financial_scope_id,budget_enforcement_mode,status,expires_at,created_at,revoked_at
            FROM gateway_credential
            WHERE org_id=#{orgId}
            ORDER BY id DESC
            """)
    List<CredentialRow> selectCredentials(@Param("orgId") long orgId);

    @Select("""
            SELECT id,org_id,credential_prefix AS prefix,principal_type,
                   service_identity_id,organization_member_id,project_id,financial_scope_type,
                   financial_scope_id,budget_enforcement_mode,status,expires_at,created_at,revoked_at
            FROM gateway_credential
            WHERE org_id=#{orgId} AND id=#{id}
            """)
    CredentialRow selectCredential(@Param("orgId") long orgId, @Param("id") long id);

    @Select("SELECT EXISTS(SELECT 1 FROM gateway_credential WHERE org_id=#{orgId} AND id=#{id})")
    boolean credentialExists(@Param("orgId") long orgId, @Param("id") long id);

    @Select("SELECT EXISTS(SELECT 1 FROM organization_member WHERE id=#{memberId} AND org_id=#{orgId})")
    boolean memberExists(@Param("orgId") long orgId, @Param("memberId") long memberId);

    @Select("SELECT status FROM project WHERE org_id=#{orgId} AND id=#{projectId}")
    String selectProjectStatus(@Param("orgId") long orgId, @Param("projectId") long projectId);

    @Select("SELECT status FROM team WHERE org_id=#{orgId} AND id=#{teamId}")
    String selectTeamStatus(@Param("orgId") long orgId, @Param("teamId") long teamId);

    @Select("SELECT status FROM cost_center WHERE org_id=#{orgId} AND id=#{costCenterId}")
    String selectCostCenterStatus(@Param("orgId") long orgId, @Param("costCenterId") long costCenterId);

    @Select("SELECT status FROM model_catalog WHERE id=#{modelId}")
    String selectModelStatus(@Param("modelId") long modelId);

    @Insert("""
            INSERT INTO gateway_credential(
              org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
              organization_member_id,service_identity_id,project_id,financial_scope_type,
              financial_scope_id,budget_enforcement_mode,status,expires_at,
              predecessor_credential_id,created_at,updated_at,revoked_at)
            VALUES (#{orgId},#{prefix},#{digest},1,#{principalType},#{organizationMemberId},
              #{serviceIdentityId},#{projectId},#{financialScopeType},#{financialScopeId},
              #{budgetEnforcementMode},'ACTIVE',#{expiresAt},NULL,
              UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
            """)
    int insertCredential(
            @Param("orgId") long orgId,
            @Param("prefix") String prefix,
            @Param("digest") byte[] digest,
            @Param("principalType") String principalType,
            @Param("organizationMemberId") Long organizationMemberId,
            @Param("serviceIdentityId") Long serviceIdentityId,
            @Param("projectId") long projectId,
            @Param("financialScopeType") String financialScopeType,
            @Param("financialScopeId") long financialScopeId,
            @Param("budgetEnforcementMode") String budgetEnforcementMode,
            @Param("expiresAt") Instant expiresAt);

    @Insert("""
            INSERT IGNORE INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
            VALUES (#{credentialId},#{orgId},#{modelId},'ACTIVE',UTC_TIMESTAMP(6))
            """)
    int insertCredentialModel(@Param("credentialId") long credentialId, @Param("orgId") long orgId,
            @Param("modelId") long modelId);

    @Update("""
            UPDATE gateway_credential
            SET status='REVOKED', revoked_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6)
            WHERE org_id=#{orgId} AND id=#{id} AND status='ACTIVE'
            """)
    int revokeCredential(@Param("orgId") long orgId, @Param("id") long id);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    // ------------------------------------------------------------------ //
    // Model / Pricing
    // ------------------------------------------------------------------ //

    @Select("""
            SELECT id,model_key,name,status FROM model_catalog
            WHERE status='ACTIVE' ORDER BY id
            """)
    List<ModelRow> selectActiveModels();

    @Select("""
            SELECT id,provider_code,model_id,provider_model_name,status,routing_eligible
            FROM provider_model
            WHERE status='ACTIVE' ORDER BY id
            """)
    List<ProviderModelRow> selectActiveProviderModels();

    @Select("""
            SELECT id,provider_code,model_id,provider_model_name,status,routing_eligible
            FROM provider_model
            WHERE id=#{providerModelId}
            """)
    ProviderModelRow selectProviderModel(@Param("providerModelId") long providerModelId);

    @Select("""
            SELECT id,provider_code,display_name,status FROM provider_account
            WHERE org_id=#{orgId} AND id=#{providerAccountId}
            """)
    ProviderAccountRow selectProviderAccount(@Param("orgId") long orgId,
            @Param("providerAccountId") long providerAccountId);

    @Select("""
            SELECT COALESCE(MAX(version),0) FROM pricing_version
            WHERE org_id=#{orgId} AND provider_account_id=#{providerAccountId}
              AND provider_model_id=#{providerModelId}
            """)
    int selectMaxPricingVersion(@Param("orgId") long orgId,
            @Param("providerAccountId") long providerAccountId,
            @Param("providerModelId") long providerModelId);

    @Insert("""
            INSERT INTO pricing_version(
              org_id,provider_account_id,provider_model_id,version,currency,
              effective_from,effective_to,status,created_at,activated_at,retired_at)
            VALUES (#{orgId},#{providerAccountId},#{providerModelId},#{version},#{currency},
              #{effectiveFrom},#{effectiveTo},'DRAFT',UTC_TIMESTAMP(6),NULL,NULL)
            """)
    int insertPricingVersion(
            @Param("orgId") long orgId,
            @Param("providerAccountId") long providerAccountId,
            @Param("providerModelId") long providerModelId,
            @Param("version") int version,
            @Param("currency") String currency,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("effectiveTo") Instant effectiveTo);

    @Insert("""
            INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
            VALUES (#{orgId},#{pricingVersionId},#{dimensionCode},#{unitQuantity},#{unitPrice})
            """)
    int insertPricingRate(
            @Param("orgId") long orgId,
            @Param("pricingVersionId") long pricingVersionId,
            @Param("dimensionCode") String dimensionCode,
            @Param("unitQuantity") long unitQuantity,
            @Param("unitPrice") BigDecimal unitPrice);

    @Select("""
            SELECT id,org_id,provider_account_id,provider_model_id,version,currency,status,
                   effective_from,effective_to,created_at,activated_at,retired_at
            FROM pricing_version
            WHERE org_id=#{orgId} AND id=#{id}
            """)
    PricingVersionRow selectPricingVersion(@Param("orgId") long orgId, @Param("id") long id);

    @Select("""
            SELECT id,org_id,provider_account_id,provider_model_id,version,currency,status,
                   effective_from,effective_to,created_at,activated_at,retired_at
            FROM pricing_version
            WHERE org_id=#{orgId}
            ORDER BY id DESC
            """)
    List<PricingVersionRow> selectPricingVersions(@Param("orgId") long orgId);

    @Select("""
            SELECT id,org_id,pricing_version_id,dimension_code,unit_quantity,unit_price
            FROM pricing_rate
            WHERE org_id=#{orgId} AND pricing_version_id=#{pricingVersionId}
            ORDER BY id
            """)
    List<PricingRateRow> selectPricingRates(@Param("orgId") long orgId,
            @Param("pricingVersionId") long pricingVersionId);

    @Update("""
            UPDATE pricing_version
            SET status='ACTIVE', activated_at=UTC_TIMESTAMP(6)
            WHERE org_id=#{orgId} AND id=#{id} AND status='DRAFT'
            """)
    int activatePricingVersion(@Param("orgId") long orgId, @Param("id") long id);

    @Update("""
            UPDATE pricing_version
            SET status='RETIRED', retired_at=UTC_TIMESTAMP(6)
            WHERE org_id=#{orgId} AND provider_account_id=#{providerAccountId}
              AND provider_model_id=#{providerModelId} AND status='ACTIVE' AND id<>#{exceptId}
            """)
    int retireOtherActivePricingVersions(@Param("orgId") long orgId,
            @Param("providerAccountId") long providerAccountId,
            @Param("providerModelId") long providerModelId,
            @Param("exceptId") long exceptId);
}