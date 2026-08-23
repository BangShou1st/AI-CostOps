package com.aicostops.audit.application;

import java.time.Instant;

/**
 * One persisted audit event as exposed by the query API. {@code metadataJson}
 * is the stored JSON document; the API layer re-parses it so callers receive
 * real JSON rather than an implementation-detail string.
 */
public record AuditEventView(
        long id,
        Long organizationId,
        Long actorUserId,
        String eventType,
        String subjectType,
        Long subjectId,
        String metadataJson,
        Instant createdAt) {

    public static AuditEventView from(long id, Long organizationId, Long actorUserId,
            String eventType, String subjectType, Long subjectId,
            String metadataJson, Instant createdAt) {
        return new AuditEventView(id, organizationId, actorUserId, eventType,
                subjectType, subjectId, metadataJson, createdAt);
    }
}
