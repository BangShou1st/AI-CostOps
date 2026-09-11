package com.aicostops.providerhub.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ModelDiscoveryMapper {

    @Select("SELECT id FROM model_catalog WHERE namespace_key=0 AND model_key=#{modelKey} LIMIT 1")
    Long findGlobalLogicalModel(@Param("modelKey") String modelKey);

    @Select("SELECT id FROM model_catalog WHERE owner_org_id=#{organizationId} AND model_key=#{modelKey} LIMIT 1")
    Long findPrivateLogicalModel(@Param("organizationId") long organizationId, @Param("modelKey") String modelKey);

    @Insert("""
            INSERT INTO model_catalog(model_key,owner_org_id,name,status,capabilities_json,
              default_max_output_tokens,max_output_tokens,created_at,updated_at)
            VALUES(#{modelKey},#{organizationId},#{name},'ACTIVE',CAST(#{capabilitiesJson} AS JSON),
              #{defaultMaxTokens},#{maxTokens},#{now},#{now})
            """)
    int insertPrivateLogicalModel(@Param("modelKey") String modelKey,
            @Param("organizationId") long organizationId, @Param("name") String name,
            @Param("capabilitiesJson") String capabilitiesJson,
            @Param("defaultMaxTokens") Integer defaultMaxTokens, @Param("maxTokens") int maxTokens,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("SELECT id FROM provider_model WHERE namespace_key=#{organizationId} AND provider_account_scope=#{accountId} AND provider_code=#{providerCode} AND provider_model_name=#{modelName} LIMIT 1")
    Long findPrivateProviderModel(@Param("organizationId") long organizationId,
            @Param("accountId") long accountId, @Param("providerCode") String providerCode,
            @Param("modelName") String modelName);

    @Insert("""
            INSERT INTO provider_model(provider_code,owner_org_id,provider_account_id,model_id,
              provider_model_name,status,routing_eligible,capabilities_json,created_at,updated_at)
            VALUES(#{providerCode},#{organizationId},#{accountId},#{modelId},#{modelName},
              'ACTIVE',TRUE,CAST(#{capabilitiesJson} AS JSON),#{now},#{now})
            """)
    int insertPrivateProviderModel(@Param("providerCode") String providerCode,
            @Param("organizationId") long organizationId, @Param("accountId") long accountId,
            @Param("modelId") long modelId, @Param("modelName") String modelName,
            @Param("capabilitiesJson") String capabilitiesJson, @Param("now") Instant now);

    @Select("""
            SELECT id,org_id,provider_connection_profile_id,provider_model_name,display_name,source,
              availability,protocol_code,pricing_classification,
              CAST(declared_capabilities_json AS CHAR) AS declared_capabilities_json,
              CAST(verified_capabilities_json AS CHAR) AS verified_capabilities_json,
              last_seen_at,last_probed_at,last_probe_status,last_probe_error_code,created_at,updated_at
            FROM provider_model_discovery
            WHERE org_id=#{organizationId} AND provider_connection_profile_id=#{profileId}
            ORDER BY provider_model_name ASC
            """)
    List<DiscoveryRow> listByProfile(@Param("organizationId") long organizationId, @Param("profileId") long profileId);

    @Select("""
            SELECT id,org_id,provider_connection_profile_id,provider_model_name,display_name,source,
              availability,protocol_code,pricing_classification,
              CAST(declared_capabilities_json AS CHAR) AS declared_capabilities_json,
              CAST(verified_capabilities_json AS CHAR) AS verified_capabilities_json,
              last_seen_at,last_probed_at,last_probe_status,last_probe_error_code,created_at,updated_at
            FROM provider_model_discovery
            WHERE id=#{id} AND org_id=#{organizationId}
            """)
    DiscoveryRow find(@Param("id") long id, @Param("organizationId") long organizationId);

    @Insert("""
            INSERT INTO provider_model_discovery(org_id,provider_connection_profile_id,provider_model_name,
              display_name,source,availability,protocol_code,pricing_classification,
              declared_capabilities_json,verified_capabilities_json,last_seen_at,last_probed_at,
              last_probe_status,last_probe_error_code,created_at,updated_at)
            VALUES(#{organizationId},#{profileId},#{modelName},#{displayName},#{source},#{availability},
              #{protocolCode},#{pricingClassification},CAST(#{declaredJson} AS JSON),CAST(#{verifiedJson} AS JSON),
              #{now},NULL,NULL,NULL,#{now},#{now})
            ON DUPLICATE KEY UPDATE availability=VALUES(availability),last_seen_at=VALUES(last_seen_at),
              display_name=COALESCE(VALUES(display_name),display_name),updated_at=VALUES(updated_at)
            """)
    int upsertObservation(@Param("organizationId") long organizationId, @Param("profileId") long profileId,
            @Param("modelName") String modelName, @Param("displayName") String displayName,
            @Param("source") String source, @Param("availability") String availability,
            @Param("protocolCode") String protocolCode, @Param("pricingClassification") String pricingClassification,
            @Param("declaredJson") String declaredJson, @Param("verifiedJson") String verifiedJson,
            @Param("now") Instant now);

    @Update("UPDATE provider_model_discovery SET availability='UNAVAILABLE',updated_at=#{now} WHERE org_id=#{organizationId} AND provider_connection_profile_id=#{profileId} AND availability='AVAILABLE' AND provider_model_name=#{modelName}")
    int markOneUnavailable(@Param("organizationId") long organizationId, @Param("profileId") long profileId,
            @Param("modelName") String modelName, @Param("now") Instant now);

    @Update("""
            UPDATE provider_model_discovery
            SET verified_capabilities_json=CAST(#{verifiedJson} AS JSON),last_probed_at=#{now},
              last_probe_status=#{probeStatus},last_probe_error_code=#{errorCode},updated_at=#{now}
            WHERE id=#{id} AND org_id=#{organizationId}
            """)
    int recordProbe(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("verifiedJson") String verifiedJson, @Param("probeStatus") String probeStatus,
            @Param("errorCode") String errorCode, @Param("now") Instant now);

    record DiscoveryRow(
            long id, long orgId, long providerConnectionProfileId, String providerModelName,
            String displayName, String source, String availability, String protocolCode,
            String pricingClassification, String declaredCapabilitiesJson, String verifiedCapabilitiesJson,
            Instant lastSeenAt, Instant lastProbedAt, String lastProbeStatus, String lastProbeErrorCode,
            Instant createdAt, Instant updatedAt) {
    }
}
