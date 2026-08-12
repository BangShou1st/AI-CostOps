package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private static final String TEST_SECRET = "test-only-signing-secret-with-more-than-32-bytes";
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void issuesHs256AccessTokenWithOnlyTheFrozenClaims() {
        var service = new JwtTokenService(TEST_SECRET, Duration.ofMinutes(15), clock);

        var issued = service.issue(123L, 7L);
        var jwt = service.decode(issued.token());

        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getClaims().keySet()).isEqualTo(Set.of("sub", "sv", "jti", "iat", "exp"));
        assertThat(jwt.getSubject()).isEqualTo("123");
        assertThat(jwt.getClaimAsString("sv")).isEqualTo("7");
        assertThat(jwt.getIssuedAt()).isEqualTo(clock.instant());
        assertThat(jwt.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void rejectsSigningSecretsShorterThan256Bits() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new JwtTokenService("too-short", Duration.ofMinutes(15), clock));
    }
}
