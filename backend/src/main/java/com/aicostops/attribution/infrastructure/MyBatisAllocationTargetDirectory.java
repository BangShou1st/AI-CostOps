package com.aicostops.attribution.infrastructure;

import com.aicostops.attribution.application.AllocationTargetDirectory;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/** Active same-org target lookups backed by one shared mapper interface. */
@Repository
public class MyBatisAllocationTargetDirectory implements AllocationTargetDirectory {

    private final TargetDirectoryMapper mapper;

    public MyBatisAllocationTargetDirectory(TargetDirectoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean activeProjectExists(long organizationId, long projectId) {
        return mapper.activeProjectExists(organizationId, projectId);
    }

    @Override
    public boolean activeCostCenterExists(long organizationId, long costCenterId) {
        return mapper.activeCostCenterExists(organizationId, costCenterId);
    }

    @Override
    public boolean activeTeamExists(long organizationId, long teamId) {
        return mapper.activeTeamExists(organizationId, teamId);
    }

    @Override
    public boolean providerAccountExists(long organizationId, long providerAccountId,
            String providerCode) {
        return mapper.providerAccountExists(organizationId, providerAccountId, providerCode);
    }

    @Override
    public List<TargetRef> activeTargets(long organizationId) {
        var refs = new ArrayList<TargetRef>();
        mapper.activeProjects(organizationId).forEach(
                row -> refs.add(new TargetRef("PROJECT", row.id(), row.name())));
        mapper.activeCostCenters(organizationId).forEach(
                row -> refs.add(new TargetRef("COST_CENTER", row.id(), row.name())));
        mapper.activeTeams(organizationId).forEach(
                row -> refs.add(new TargetRef("TEAM", row.id(), row.name())));
        return List.copyOf(refs);
    }

    record NamedRow(long id, String name) {
    }

    @Mapper
    interface TargetDirectoryMapper {

        @Select("""
                SELECT EXISTS (
                    SELECT 1 FROM project WHERE id=#{targetId} AND org_id=#{organizationId}
                      AND status='ACTIVE')
                """)
        boolean activeProjectExists(
                @Param("organizationId") long organizationId,
                @Param("targetId") long targetId);

        @Select("""
                SELECT EXISTS (
                    SELECT 1 FROM cost_center WHERE id=#{targetId} AND org_id=#{organizationId}
                      AND status='ACTIVE')
                """)
        boolean activeCostCenterExists(
                @Param("organizationId") long organizationId,
                @Param("targetId") long targetId);

        @Select("""
                SELECT EXISTS (
                    SELECT 1 FROM team WHERE id=#{targetId} AND org_id=#{organizationId}
                      AND status='ACTIVE')
                """)
        boolean activeTeamExists(
                @Param("organizationId") long organizationId,
                @Param("targetId") long targetId);

        @Select("""
                SELECT EXISTS (
                    SELECT 1 FROM provider_account
                    WHERE id=#{providerAccountId} AND org_id=#{organizationId}
                      AND BINARY provider_code = BINARY #{providerCode})
                """)
        boolean providerAccountExists(
                @Param("organizationId") long organizationId,
                @Param("providerAccountId") long providerAccountId,
                @Param("providerCode") String providerCode);

        @Select("""
                SELECT id, name FROM project
                WHERE org_id=#{organizationId} AND status='ACTIVE'
                ORDER BY id ASC
                """)
        List<NamedRow> activeProjects(@Param("organizationId") long organizationId);

        @Select("""
                SELECT id, name FROM cost_center
                WHERE org_id=#{organizationId} AND status='ACTIVE'
                ORDER BY id ASC
                """)
        List<NamedRow> activeCostCenters(@Param("organizationId") long organizationId);

        @Select("""
                SELECT id, name FROM team
                WHERE org_id=#{organizationId} AND status='ACTIVE'
                ORDER BY id ASC
                """)
        List<NamedRow> activeTeams(@Param("organizationId") long organizationId);
    }
}
