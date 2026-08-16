package com.aicostops.attribution.application;

/**
 * Read-only directory of allocation targets. The Group 3 rule and allocation
 * workflows must consult it before writing a rule or a line: only rows of the
 * same organization with status ACTIVE are valid targets.
 */
public interface AllocationTargetDirectory {

    boolean activeProjectExists(long organizationId, long projectId);

    boolean activeCostCenterExists(long organizationId, long costCenterId);

    boolean activeTeamExists(long organizationId, long teamId);
}
