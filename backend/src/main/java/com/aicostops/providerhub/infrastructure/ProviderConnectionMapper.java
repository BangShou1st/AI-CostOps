package com.aicostops.providerhub.infrastructure;

import com.aicostops.providerhub.domain.ProviderConnection;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProviderConnectionMapper {

    String COLUMNS = """
            id,org_id,provider_account_id,version,connection_kind,template_code,
            protocol_code,base_url,completion_path,models_path,auth_type,
            auth_header_name,network_policy,user_agent,connect_timeout_ms,
            response_timeout_ms,status,created_by,created_at,activated_at,retired_at
            """;

    @Select("SELECT " + COLUMNS + " FROM provider_connection_profile WHERE id=#{id} AND org_id=#{organizationId}")
    ProviderConnection find(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT " + COLUMNS + " FROM provider_connection_profile WHERE id=#{id} AND org_id=#{organizationId} FOR UPDATE")
    ProviderConnection findForUpdate(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT " + COLUMNS + " FROM provider_connection_profile WHERE org_id=#{organizationId} AND provider_account_id=#{accountId} AND status='ACTIVE' LIMIT 1")
    ProviderConnection findActive(@Param("organizationId") long organizationId, @Param("accountId") long accountId);

    @Select("SELECT " + COLUMNS + " FROM provider_connection_profile WHERE org_id=#{organizationId} AND provider_account_id=#{accountId} AND status='ACTIVE' LIMIT 1 FOR UPDATE")
    ProviderConnection findActiveForUpdate(@Param("organizationId") long organizationId, @Param("accountId") long accountId);

    @Select("SELECT " + COLUMNS + " FROM provider_connection_profile WHERE org_id=#{organizationId} AND provider_account_id=#{accountId} ORDER BY version DESC")
    List<ProviderConnection> listRevisions(@Param("organizationId") long organizationId, @Param("accountId") long accountId);

    @Select("SELECT " + COLUMNS + " FROM provider_connection_profile WHERE org_id=#{organizationId} ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ProviderConnection> findPage(@Param("organizationId") long organizationId, @Param("offset") long offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM provider_connection_profile WHERE org_id=#{organizationId}")
    long count(@Param("organizationId") long organizationId);

    @Select("SELECT COALESCE(MAX(version),0)+1 FROM provider_connection_profile WHERE org_id=#{organizationId} AND provider_account_id=#{accountId}")
    int nextVersion(@Param("organizationId") long organizationId, @Param("accountId") long accountId);

    @Select("SELECT provider_code FROM provider_account WHERE id=#{accountId} AND org_id=#{organizationId} AND status='ACTIVE'")
    String findActiveAccountProviderCode(@Param("organizationId") long organizationId, @Param("accountId") long accountId);

    @Insert("""
            INSERT INTO provider_connection_profile(org_id,provider_account_id,version,connection_kind,template_code,
              protocol_code,base_url,completion_path,models_path,auth_type,auth_header_name,network_policy,
              user_agent,connect_timeout_ms,response_timeout_ms,status,created_by,created_at,activated_at,retired_at)
            VALUES(#{organizationId},#{accountId},#{version},#{kind},#{templateCode},#{protocolCode},
              #{baseUrl},#{completionPath},#{modelsPath},#{authType},#{authHeaderName},#{networkPolicy},
              #{userAgent},#{connectTimeoutMs},#{responseTimeoutMs},#{status},#{createdBy},#{now},#{activatedAt},NULL)
            """)
    int insert(@Param("organizationId") long organizationId, @Param("accountId") long accountId,
            @Param("version") int version, @Param("kind") String kind, @Param("templateCode") String templateCode,
            @Param("protocolCode") String protocolCode, @Param("baseUrl") String baseUrl,
            @Param("completionPath") String completionPath, @Param("modelsPath") String modelsPath,
            @Param("authType") String authType, @Param("authHeaderName") String authHeaderName,
            @Param("networkPolicy") String networkPolicy, @Param("userAgent") String userAgent,
            @Param("connectTimeoutMs") int connectTimeoutMs, @Param("responseTimeoutMs") int responseTimeoutMs,
            @Param("status") String status, @Param("createdBy") Long createdBy,
            @Param("now") Instant now, @Param("activatedAt") Instant activatedAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE provider_connection_profile
            SET base_url=#{baseUrl},completion_path=#{completionPath},models_path=#{modelsPath},
                auth_type=#{authType},auth_header_name=#{authHeaderName},user_agent=#{userAgent},
                connect_timeout_ms=#{connectTimeoutMs},response_timeout_ms=#{responseTimeoutMs}
            WHERE id=#{id} AND org_id=#{organizationId} AND status='DRAFT'
            """)
    int updateDraft(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("baseUrl") String baseUrl, @Param("completionPath") String completionPath,
            @Param("modelsPath") String modelsPath, @Param("authType") String authType,
            @Param("authHeaderName") String authHeaderName, @Param("userAgent") String userAgent,
            @Param("connectTimeoutMs") int connectTimeoutMs, @Param("responseTimeoutMs") int responseTimeoutMs);

    @Update("UPDATE provider_connection_profile SET status='RETIRED',retired_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND status='ACTIVE'")
    int retireActive(@Param("id") long id, @Param("organizationId") long organizationId, @Param("now") Instant now);

    @Update("UPDATE provider_connection_profile SET status='ACTIVE',activated_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND status='DRAFT'")
    int activateDraft(@Param("id") long id, @Param("organizationId") long organizationId, @Param("now") Instant now);
}
