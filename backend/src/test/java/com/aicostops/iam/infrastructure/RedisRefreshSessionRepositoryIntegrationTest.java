package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.RedisContainerSupport;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(properties = "spring.flyway.enabled=false")
@Tag("integration")
class RedisRefreshSessionRepositoryIntegrationTest extends RedisContainerSupport {

    @Autowired
    private StringRedisTemplate redis;

    private MutableClock clock;

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        clock = new MutableClock(Instant.parse("2026-08-12T12:00:00Z"));
    }

    @Test
    void storesOnlyDigestAndTheFrozenSessionHashWithTtl() {
        var repository = repository(Duration.ofDays(7));

        var credential = repository.create(11L, 22L, 3L, "browser");
        var sessionId = credential.value().substring(0, credential.value().indexOf('.'));
        var secret = credential.value().substring(credential.value().indexOf('.') + 1);
        var key = "aicostops:v1:auth:refresh:" + sessionId;
        var fields = redis.opsForHash().entries(key);

        assertThat(fields.keySet()).containsExactlyInAnyOrder(
                "user_id", "org_member_id", "security_version", "current_token_hash",
                "previous_token_hash", "previous_valid_until_ms", "created_at_ms",
                "last_rotated_at_ms", "absolute_expires_at_ms", "device_label");
        assertThat(fields.get("current_token_hash")).isEqualTo(com.aicostops.iam.domain.TokenDigest.sha256(secret));
        assertThat(fields.values()).doesNotContain(secret, credential.value());
        assertThat(redis.getExpire(key)).isBetween(Duration.ofDays(7).minusSeconds(5).toSeconds(), Duration.ofDays(7).toSeconds());
    }

    @Test
    void rotatesAtomicallyReportsRaceThenRevokesReplay() {
        var repository = repository(Duration.ofDays(7));
        var first = repository.create(11L, 22L, 3L, "browser");

        var rotated = repository.rotate(first.value());
        assertThat(rotated.outcome()).isEqualTo(RefreshRotationOutcome.ROTATED);
        assertThat(rotated.nextCredential()).isNotNull().isNotEqualTo(first.value());
        assertThat(repository.rotate(first.value()).outcome()).isEqualTo(RefreshRotationOutcome.RACE);

        clock.advance(Duration.ofSeconds(11));
        assertThat(repository.rotate(first.value()).outcome()).isEqualTo(RefreshRotationOutcome.REPLAY);
        assertThat(repository.rotate(rotated.nextCredential()).outcome()).isEqualTo(RefreshRotationOutcome.EXPIRED);
    }

    @Test
    void reportsAbsoluteExpiryAndSupportsExplicitRevoke() {
        var shortRepository = repository(Duration.ofSeconds(1));
        var expired = shortRepository.create(11L, 22L, 3L, "browser");
        clock.advance(Duration.ofSeconds(2));
        assertThat(shortRepository.rotate(expired.value()).outcome()).isEqualTo(RefreshRotationOutcome.EXPIRED);

        var repository = repository(Duration.ofDays(7));
        var revoked = repository.create(11L, 22L, 3L, "browser");
        repository.revoke(revoked.value());
        assertThat(repository.rotate(revoked.value()).outcome()).isEqualTo(RefreshRotationOutcome.EXPIRED);
    }

    @Test
    void revokeAllUsesExistingSessionIndex() {
        var repository = repository(Duration.ofDays(7));
        var first = repository.create(11L, 22L, 3L, "browser");
        var second = repository.create(11L, 23L, 3L, "mobile");
        var otherUser = repository.create(12L, 24L, 5L, "browser");

        repository.revokeAll(11L);

        assertThat(repository.load(first.value())).isNull();
        assertThat(repository.load(second.value())).isNull();
        assertThat(repository.load(otherUser.value())).isNotNull();
        assertThat(redis.hasKey("aicostops:v1:auth:user-sessions:11")).isFalse();
    }

    private RedisRefreshSessionRepository repository(Duration duration) {
        return new RedisRefreshSessionRepository(redis, clock, new SecureRandom(), duration, Duration.ofSeconds(10));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
