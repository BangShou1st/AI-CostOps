package com.aicostops.organization.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrganizationMapper {

    @Insert("""
            INSERT INTO organization(name, slug, status, settings_json, created_at, updated_at)
            SELECT #{name}, #{slug}, 'ACTIVE', JSON_OBJECT(), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
            WHERE NOT EXISTS (SELECT 1 FROM organization WHERE slug = #{slug})
            """)
    int insertActiveOrganizationIfMissing(@Param("slug") String slug, @Param("name") String name);
}
