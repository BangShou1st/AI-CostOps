package com.aicostops.attribution.application;

import java.util.List;

/**
 * Read-only directory of allocation targets. The Group 3 rule and allocation
 * workflows must consult it before writing a rule or a line: only rows of the
 * same organization with status ACTIVE are valid targets.
 */
public interface AllocationTargetDirectory {

    /** Safe reference of one allocatable row: its kind, id, and display name. */
    record TargetRef(String type, long id, String name) {
    }

    boolean activeProjectExists(long organizationId, long projectId);

    boolean activeCostCenterExists(long organizationId, long costCenterId);

    boolean activeTeamExists(long organizationId, long teamId);

    /** Provider account of the same organization with a matching provider code. */
    boolean providerAccountExists(long organizationId, long providerAccountId, String providerCode);

    /** Every ACTIVE project, cost center, and team of the organization. */
    List<TargetRef> activeTargets(long organizationId);
}
