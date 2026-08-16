package com.aicostops.attribution.infrastructure;

import com.aicostops.attribution.application.AllocationTargetDirectory;
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
    }
}
