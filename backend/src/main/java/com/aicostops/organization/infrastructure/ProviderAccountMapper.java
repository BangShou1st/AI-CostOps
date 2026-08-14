package com.aicostops.organization.infrastructure;

import com.aicostops.organization.domain.ProviderAccount;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProviderAccountMapper {

    String CURRENT_ORGANIZATION_PREDICATE = """
            pa.org_id=#{organizationId}
              AND (#{status} IS NULL OR pa.status=#{status})
            """;

    String PROVIDER_ACCOUNT_COLUMNS = """
            pa.id,pa.org_id,pa.provider_code,pa.display_name,pa.external_account_ref,pa.status,
            CAST(pa.metadata_json AS CHAR) AS metadata_json,pa.created_at,pa.updated_at
            """;

    @Select("""
            SELECT COUNT(*)
            FROM provider_account pa
            WHERE
            """ + CURRENT_ORGANIZATION_PREDICATE)
    long countCurrentOrganization(
            @Param("organizationId") long organizationId,
            @Param("status") String status);

    @Select("""
            SELECT
            """ + PROVIDER_ACCOUNT_COLUMNS + """
            FROM provider_account pa
            WHERE
            """ + CURRENT_ORGANIZATION_PREDICATE + """
            ORDER BY pa.id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ProviderAccount> findCurrentOrganizationPage(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("""
            SELECT
            """ + PROVIDER_ACCOUNT_COLUMNS + """
            FROM provider_account pa
            WHERE
            """ + CURRENT_ORGANIZATION_PREDICATE + """
              AND pa.id=#{providerAccountId}
            FOR UPDATE
            """)
    ProviderAccount findCurrentOrganizationForUpdate(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("providerAccountId") long providerAccountId);

    @Insert("""
            INSERT INTO provider_account(
                org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
            VALUES (
                #{organizationId},#{providerCode},#{displayName},#{externalAccountRef},'ACTIVE',
                CAST(#{metadataJson} AS JSON),#{now},#{now})
            """)
    int insert(
            @Param("organizationId") long organizationId,
            @Param("providerCode") String providerCode,
            @Param("displayName") String displayName,
            @Param("externalAccountRef") String externalAccountRef,
            @Param("metadataJson") String metadataJson,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT
            """ + PROVIDER_ACCOUNT_COLUMNS + """
            FROM provider_account pa
            WHERE
            """ + CURRENT_ORGANIZATION_PREDICATE + """
              AND pa.id=#{providerAccountId}
            """)
    ProviderAccount findCurrentOrganization(
            @Param("providerAccountId") long providerAccountId,
            @Param("organizationId") long organizationId,
            @Param("status") String status);

    @Update("""
            UPDATE provider_account pa
            SET pa.display_name=#{displayName},
                pa.external_account_ref=#{externalAccountRef},
                pa.status=#{newStatus},
                pa.metadata_json=CAST(#{metadataJson} AS JSON),
                pa.updated_at=#{now}
            WHERE
            """ + CURRENT_ORGANIZATION_PREDICATE + """
              AND pa.id=#{providerAccountId}
            """)
    int updateCurrentOrganization(
            @Param("providerAccountId") long providerAccountId,
            @Param("organizationId") long organizationId,
            @Param("status") String currentStatusFilter,
            @Param("displayName") String displayName,
            @Param("externalAccountRef") String externalAccountRef,
            @Param("newStatus") String newStatus,
            @Param("metadataJson") String metadataJson,
            @Param("now") Instant now);
}
