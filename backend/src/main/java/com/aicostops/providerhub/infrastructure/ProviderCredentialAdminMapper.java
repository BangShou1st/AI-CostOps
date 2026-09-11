package com.aicostops.providerhub.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Provider secret lifecycle (M18 V3). Reads never expose ciphertext; list
 * and detail project safe labels and status only.
 */
@Mapper
public interface ProviderCredentialAdminMapper {

    @Select("""
            SELECT id,credential_type,safe_label,status,created_at,rotated_at,revoked_at
            FROM provider_credential
            WHERE org_id=#{organizationId} AND provider_account_id=#{accountId}
            ORDER BY id DESC
            """)
    List<SafeCredentialRow> listSafe(@Param("organizationId") long organizationId,
            @Param("accountId") long accountId);

    @Select("""
            SELECT id FROM provider_credential
            WHERE org_id=#{organizationId} AND provider_account_id=#{accountId} AND status='ACTIVE'
            ORDER BY id LIMIT 1
            """)
    Long findActiveId(@Param("organizationId") long organizationId, @Param("accountId") long accountId);

    @Insert("""
            INSERT INTO provider_credential(org_id,provider_account_id,credential_type,ciphertext,nonce,
              encryption_key_version,safe_label,status,predecessor_credential_id,created_at,rotated_at,revoked_at)
            VALUES(#{organizationId},#{accountId},#{credentialType},#{ciphertext},#{nonce},
              1,#{safeLabel},'ACTIVE',#{predecessorId},#{now},NULL,NULL)
            """)
    int insert(@Param("organizationId") long organizationId, @Param("accountId") long accountId,
            @Param("credentialType") String credentialType, @Param("ciphertext") byte[] ciphertext,
            @Param("nonce") byte[] nonce, @Param("safeLabel") String safeLabel,
            @Param("predecessorId") Long predecessorId, @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("UPDATE provider_credential SET status='REVOKED',revoked_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND provider_account_id=#{accountId} AND status='ACTIVE'")
    int revoke(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("accountId") long accountId, @Param("now") Instant now);

    record SafeCredentialRow(long id, String credentialType, String safeLabel, String status,
            Instant createdAt, Instant rotatedAt, Instant revokedAt) {
    }
}
