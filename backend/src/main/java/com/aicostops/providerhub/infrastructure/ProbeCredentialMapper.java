package com.aicostops.providerhub.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProbeCredentialMapper {

    @Select("""
            SELECT credential_type,ciphertext,nonce,encryption_key_version
            FROM provider_credential
            WHERE org_id=#{organizationId} AND provider_account_id=#{accountId} AND status='ACTIVE'
            ORDER BY id LIMIT 1
            """)
    CredentialRow findActive(@Param("organizationId") long organizationId, @Param("accountId") long accountId);

    record CredentialRow(String credentialType, byte[] ciphertext, byte[] nonce, short encryptionKeyVersion) {
    }
}
