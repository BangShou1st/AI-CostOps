package com.aicostops.organization.domain;

/**
 * Read-only Provider Account snapshot for ingestion. Carries only the fields the
 * import pipeline needs; never credentials or metadata secrets.
 */
public record ProviderAccountSnapshot(
        long id,
        long organizationId,
        String providerCode,
        String displayName,
        String externalAccountRef,
        String status) {
}
