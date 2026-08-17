package com.aicostops.allocation.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Organization-row lock used to serialize same-organization rule version
 * creation (server-authoritative {@code maxVersion + 1}); the unique
 * {@code (org_id, rule_key, version)} constraint remains the hard backstop.
 */
@Mapper
public interface AllocationOrganizationLockMapper {

    @Select("""
            SELECT id FROM organization WHERE id=#{organizationId} FOR UPDATE
            """)
    Long lockOrganization(@Param("organizationId") long organizationId);
}
