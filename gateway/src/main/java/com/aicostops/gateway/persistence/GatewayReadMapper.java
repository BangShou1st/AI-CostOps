package com.aicostops.gateway.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Read-only runtime projection of Control-Plane-owned identity, catalog and
 * pricing data. The Gateway never writes these administrative tables.
 */
@Mapper
public interface GatewayReadMapper {

    @Select("""
            SELECT id, org_id, credential_prefix, secret_digest, secret_digest_version,
                   principal_type, organization_member_id, service_identity_id, project_id,
                   financial_scope_type, financial_scope_id, budget_enforcement_mode, status,
                   expires_at
            FROM gateway_credential
            WHERE credential_prefix=#{prefix}
            """)
    CredentialRow findCredentialByPrefix(@Param("prefix") String prefix);

    @Select("""
            SELECT model_id FROM gateway_credential_model
            WHERE credential_id=#{credentialId} AND status='ACTIVE'
            """)
    List<Long> findActiveModelIds(@Param("credentialId") long credentialId);

    @Select("SELECT status FROM service_identity WHERE id=#{id}")
    String findServiceIdentityStatus(@Param("id") long id);

    @Select("SELECT status FROM organization_member WHERE id=#{id}")
    String findOrganizationMemberStatus(@Param("id") long id);

    @Select("SELECT status FROM project WHERE id=#{id} AND org_id=#{orgId}")
    String findProjectStatus(@Param("id") long id, @Param("orgId") long orgId);

    @Select("SELECT status FROM team WHERE id=#{id} AND org_id=#{orgId}")
    String findTeamStatus(@Param("id") long id, @Param("orgId") long orgId);

    @Select("SELECT status FROM cost_center WHERE id=#{id} AND org_id=#{orgId}")
    String findCostCenterStatus(@Param("id") long id, @Param("orgId") long orgId);

    @Select("""
            SELECT id, model_key, status, default_max_output_tokens, max_output_tokens
            FROM model_catalog WHERE id=#{modelId}
            """)
    ModelRow findModelById(@Param("modelId") long modelId);

    @Select("""
            SELECT id FROM billing_period
            WHERE org_id=#{orgId} AND status='OPEN'
              AND period_start <= #{now} AND period_end > #{now}
            ORDER BY period_start DESC LIMIT 1
            """)
    Long findOpenBillingPeriodId(@Param("orgId") long orgId, @Param("now") Instant now);

    @Select("""
            SELECT status FROM billing_period WHERE id=#{periodId} AND org_id=#{orgId}
            """)
    String findBillingPeriodStatus(@Param("periodId") long periodId, @Param("orgId") long orgId);

    @Select("""
            SELECT credential_type, ciphertext, nonce, encryption_key_version
            FROM provider_credential
            WHERE org_id=#{orgId} AND provider_account_id=#{accountId} AND status='ACTIVE'
            ORDER BY id LIMIT 1
            """)
    ProviderCredentialRow findActiveProviderCredential(
            @Param("orgId") long orgId, @Param("accountId") long providerAccountId);

    @Select("SELECT id FROM model_catalog WHERE model_key=#{modelKey}")
    Long findModelIdByKey(@Param("modelKey") String modelKey);

    record CredentialRow(
            long id,
            long orgId,
            String credentialPrefix,
            byte[] secretDigest,
            short secretDigestVersion,
            String principalType,
            Long organizationMemberId,
            Long serviceIdentityId,
            long projectId,
            String financialScopeType,
            long financialScopeId,
            String budgetEnforcementMode,
            String status,
            Instant expiresAt) {
    }

    record ModelRow(
            long id,
            String modelKey,
            String status,
            Integer defaultMaxOutputTokens,
            int maxOutputTokens) {
    }

    record ProviderCredentialRow(String credentialType, byte[] ciphertext, byte[] nonce, short encryptionKeyVersion) {
    }
}
