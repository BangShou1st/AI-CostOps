package com.aicostops.iam.application;

import java.util.Objects;
import java.util.Set;

public record ResourceScope(boolean organizationWide, Set<Long> resourceIds) {

    public ResourceScope {
        resourceIds = Set.copyOf(Objects.requireNonNull(resourceIds, "Resource IDs are required"));
    }
}
