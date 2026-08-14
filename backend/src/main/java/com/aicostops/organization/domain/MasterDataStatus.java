package com.aicostops.organization.domain;

public enum MasterDataStatus {
    ACTIVE,
    DISABLED,
    ARCHIVED;

    public boolean canTransitionTo(MasterDataStatus requested) {
        return switch (this) {
            case ACTIVE -> requested == DISABLED || requested == ARCHIVED;
            case DISABLED -> requested == ACTIVE || requested == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
