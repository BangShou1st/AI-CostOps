package com.aicostops.audit.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.aicostops.audit.infrastructure.AuditMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class AuditServiceTest {
    private final AuditService service = new AuditService(mock(AuditMapper.class), new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(strings = { "password", "refreshToken", "refresh_secret", "resetToken",
            "fullJwt", "jwtSigningSecret", "apiSecret", "api_key" })
    void rejectsSecretBearingMetadataKeys(String key) {
        assertThatThrownBy(() -> service.append("LOGIN_FAILED", 1L, 2L, "USER", 2L,
                Map.of(key, "must-not-be-written")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden secret key");
    }
}
