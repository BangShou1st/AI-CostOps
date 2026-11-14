package com.aicostops.gatewayadmin.infrastructure;

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

}
