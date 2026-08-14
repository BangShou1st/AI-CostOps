package com.aicostops.organization.application;

import com.aicostops.organization.domain.ProviderAccountSnapshot;
import java.util.Optional;

/** Read-only provider-account lookup for the ingestion module. */
public interface ProviderAccountDirectory {

    /** Returns the ACTIVE provider account scoped to the organization, if any. */
    Optional<ProviderAccountSnapshot> findActive(long organizationId, long providerAccountId);
}
