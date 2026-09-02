package com.aicostops.gatewayadmin.infrastructure;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis persistence seams for the Gateway runtime projection used by the
 * explicit local development bootstrap. Idempotent provisioning selects
 * before inserting; the V18 database uniqueness constraints are the final
 * convergence authority.
 */
@Mapper
public interface GatewayAdminMapper {

    @Select("SELECT id FROM project WHERE org_id=#{orgId} AND code=#{code} AND status='ACTIVE'")
    Long findActiveProjectId(@Param("orgId") long orgId, @Param("code") String code);

    @Insert("""
            INSERT INTO project(org_id,code,name,status,created_at,updated_at)
            VALUES (#{orgId},#{code},'AI CostOps Gateway Dev','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """)
    int insertActiveProject(@Param("orgId") long orgId, @Param("code") String code);

    @Select("SELECT id FROM service_identity WHERE org_id=#{orgId} AND code=#{code}")
    Long findServiceIdentityId(@Param("orgId") long orgId, @Param("code") String code);

    @Insert("""
            INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
            VALUES (#{orgId},#{code},'AI CostOps Gateway Dev','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """)
    int insertServiceIdentity(@Param("orgId") long orgId, @Param("code") String code);

    @Select("SELECT id FROM gateway_credential WHERE credential_prefix=#{prefix}")
    Long findCredentialIdByPrefix(@Param("prefix") String prefix);

    @Insert("""
            INSERT INTO gateway_credential(
              org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
              organization_member_id,service_identity_id,project_id,financial_scope_type,
              financial_scope_id,budget_enforcement_mode,status,expires_at,
              predecessor_credential_id,created_at,updated_at,revoked_at)
            VALUES (#{orgId},#{prefix},#{digest},1,'SERVICE',NULL,#{serviceIdentityId},
              #{projectId},'PROJECT',#{projectId},'OPTIONAL','ACTIVE',NULL,NULL,
              UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
            """)
    int insertGatewayCredential(
            @Param("orgId") long orgId,
            @Param("prefix") String prefix,
            @Param("digest") byte[] digest,
            @Param("serviceIdentityId") long serviceIdentityId,
            @Param("projectId") long projectId);

    @Insert("""
            INSERT IGNORE INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
            VALUES (#{credentialId},#{orgId},#{modelId},'ACTIVE',UTC_TIMESTAMP(6))
            """)
    int insertCredentialModelIfMissing(
            @Param("credentialId") long credentialId,
            @Param("orgId") long orgId,
            @Param("modelId") long modelId);

    @Select("SELECT id FROM model_catalog WHERE model_key=#{modelKey}")
    Long findModelIdByKey(@Param("modelKey") String modelKey);

    @Insert("""
            INSERT INTO model_catalog(
              model_key,name,status,capabilities_json,default_max_output_tokens,
              max_output_tokens,created_at,updated_at)
            VALUES (#{modelKey},'AI CostOps Default Chat','ACTIVE',JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),8192,131072,
              UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """)
    int insertModelCatalog(@Param("modelKey") String modelKey);

    @Select("SELECT COUNT(*) FROM provider_catalog WHERE provider_code=#{providerCode}")
    int countProviderCatalog(@Param("providerCode") String providerCode);

    @Insert("""
            INSERT INTO provider_catalog(
              provider_code,name,adapter_code,base_url,status,capabilities_json,created_at,updated_at)
            VALUES (#{providerCode},'MiMo','MIMO',#{baseUrl},'ACTIVE',
              JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),
              UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """)
    int insertProviderCatalog(
            @Param("providerCode") String providerCode, @Param("baseUrl") String baseUrl);

    @Select("SELECT id FROM provider_model WHERE provider_code=#{providerCode} AND provider_model_name=#{providerModelName}")
    Long findProviderModelId(
            @Param("providerCode") String providerCode,
            @Param("providerModelName") String providerModelName);

    @Insert("""
            INSERT INTO provider_model(
              provider_code,model_id,provider_model_name,status,routing_eligible,
              capabilities_json,created_at,updated_at)
            VALUES (#{providerCode},#{modelId},#{providerModelName},'ACTIVE',TRUE,
              JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),
              UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """)
    int insertProviderModel(
            @Param("providerCode") String providerCode,
            @Param("modelId") long modelId,
            @Param("providerModelName") String providerModelName);

    @Select("SELECT id FROM provider_account WHERE org_id=#{orgId} AND provider_code=#{providerCode} AND status='ACTIVE' ORDER BY id LIMIT 1")
    Long findActiveProviderAccountId(
            @Param("orgId") long orgId, @Param("providerCode") String providerCode);

    @Insert("""
            INSERT INTO provider_account(
              org_id,provider_code,display_name,external_account_ref,status,metadata_json,
              created_at,updated_at)
            VALUES (#{orgId},#{providerCode},'MiMo Dev Account','mimo-dev', 'ACTIVE',JSON_OBJECT(),
              UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """)
    int insertProviderAccount(@Param("orgId") long orgId, @Param("providerCode") String providerCode);

    @Select("SELECT id FROM pricing_version WHERE org_id=#{orgId} AND provider_account_id=#{providerAccountId} AND provider_model_id=#{providerModelId} AND version=#{version}")
    Long findPricingVersionId(
            @Param("orgId") long orgId,
            @Param("providerAccountId") long providerAccountId,
            @Param("providerModelId") long providerModelId,
            @Param("version") int version);

    @Insert("""
            INSERT INTO pricing_version(
              org_id,provider_account_id,provider_model_id,version,currency,
              effective_from,effective_to,status,created_at,activated_at,retired_at)
            VALUES (#{orgId},#{providerAccountId},#{providerModelId},#{version},'USD',
              #{effectiveFrom},#{effectiveTo},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
            """)
    int insertPricingVersion(
            @Param("orgId") long orgId,
            @Param("providerAccountId") long providerAccountId,
            @Param("providerModelId") long providerModelId,
            @Param("version") int version,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("effectiveTo") Instant effectiveTo);

    @Insert("""
            INSERT IGNORE INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
            VALUES (#{orgId},#{pricingVersionId},#{dimensionCode},#{unitQuantity},#{unitPrice})
            """)
    int insertPricingRateIfMissing(
            @Param("orgId") long orgId,
            @Param("pricingVersionId") long pricingVersionId,
            @Param("dimensionCode") String dimensionCode,
            @Param("unitQuantity") long unitQuantity,
            @Param("unitPrice") String unitPrice);

    @Select("SELECT id FROM provider_credential WHERE org_id=#{orgId} AND provider_account_id=#{providerAccountId} AND status='ACTIVE' ORDER BY id LIMIT 1")
    Long findActiveProviderCredentialId(
            @Param("orgId") long orgId, @Param("providerAccountId") long providerAccountId);

    @Insert("""
            INSERT INTO provider_credential(
              org_id,provider_account_id,credential_type,ciphertext,nonce,
              encryption_key_version,safe_label,status,predecessor_credential_id,
              created_at,rotated_at,revoked_at)
            VALUES (#{orgId},#{providerAccountId},'API_KEY',#{ciphertext},#{nonce},
              1,'MiMo Dev', 'ACTIVE',NULL,UTC_TIMESTAMP(6),NULL,NULL)
            """)
    int insertProviderCredential(
            @Param("orgId") long orgId,
            @Param("providerAccountId") long providerAccountId,
            @Param("ciphertext") byte[] ciphertext,
            @Param("nonce") byte[] nonce);

    @Select("""
            SELECT b.period_start, b.period_end
            FROM billing_period b
            WHERE b.org_id=#{orgId} AND b.status='OPEN'
            ORDER BY b.period_start DESC LIMIT 1
            """)
    java.util.Map<String, Object> findOpenBillingPeriodRange(@Param("orgId") long orgId);
}