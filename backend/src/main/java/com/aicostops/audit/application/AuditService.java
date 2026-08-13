package com.aicostops.audit.application;

import com.aicostops.audit.infrastructure.AuditMapper;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditService {

    private static final String[] SECRET_KEY_FRAGMENTS = {
            "password", "token", "secret", "jwt", "apikey", "api_key"
    };

    private final AuditMapper auditMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditMapper auditMapper, ObjectMapper objectMapper, Clock clock) {
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void append(
            String eventType,
            Long organizationId,
            Long actorUserId,
            String subjectType,
            Long subjectId,
            Map<String, ?> metadata) {
        metadata.keySet().forEach(AuditService::rejectSecretKey);
        auditMapper.insert(organizationId, actorUserId, eventType, subjectType, subjectId,
                objectMapper.writeValueAsString(metadata), clock.instant());
    }

    private static void rejectSecretKey(String key) {
        var normalized = key.toLowerCase(Locale.ROOT);
        for (var fragment : SECRET_KEY_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                throw new IllegalArgumentException("Audit metadata contains a forbidden secret key");
            }
        }
    }
}
