package com.aicostops.organization.infrastructure;

import com.aicostops.iam.domain.ScopeType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrganizationMapper {

    @Insert("""
            INSERT INTO organization(name, slug, status, settings_json, created_at, updated_at)
            SELECT #{name}, #{slug}, 'ACTIVE', JSON_OBJECT(), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
            WHERE NOT EXISTS (SELECT 1 FROM organization WHERE slug = #{slug})
            """)
    int insertActiveOrganizationIfMissing(@Param("slug") String slug, @Param("name") String name);

    default boolean scopeResourceExists(ScopeType scopeType, long resourceId, long organizationId) {
        return switch (scopeType) {
            case PROJECT -> projectScopeResourceExists(resourceId, organizationId);
            case TEAM -> teamScopeResourceExists(resourceId, organizationId);
            case COST_CENTER -> costCenterScopeResourceExists(resourceId, organizationId);
            case ORG -> false;
        };
    }

    @Select("SELECT EXISTS(SELECT 1 FROM project WHERE id=#{resourceId} AND org_id=#{organizationId})")
    boolean projectScopeResourceExists(
            @Param("resourceId") long resourceId, @Param("organizationId") long organizationId);

    @Select("SELECT EXISTS(SELECT 1 FROM team WHERE id=#{resourceId} AND org_id=#{organizationId})")
    boolean teamScopeResourceExists(
            @Param("resourceId") long resourceId, @Param("organizationId") long organizationId);

    @Select("SELECT EXISTS(SELECT 1 FROM cost_center WHERE id=#{resourceId} AND org_id=#{organizationId})")
    boolean costCenterScopeResourceExists(
            @Param("resourceId") long resourceId, @Param("organizationId") long organizationId);
}
