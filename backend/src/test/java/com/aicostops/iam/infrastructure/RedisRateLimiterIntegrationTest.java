package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.RedisContainerSupport;
import com.aicostops.iam.domain.TokenDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(properties = "spring.flyway.enabled=false")
@Tag("integration")
class RedisRateLimiterIntegrationTest extends RedisContainerSupport {

    @Autowired
    private StringRedisTemplate redis;

    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        limiter = new RedisRateLimiter(redis, Clock.systemUTC(), 20, 8, Duration.ofMinutes(15));
    }

    @Test
    void enforcesAccountAndIpFixedWindowsWithHashedKeysAndTtl() {
        for (var attempt = 1; attempt <= 8; attempt++) {
            assertThat(limiter.checkLogin("203.0.113.1", "person@example.com").allowed()).isTrue();
        }
        var accountDenied = limiter.checkLogin("203.0.113.2", "person@example.com");
        assertThat(accountDenied.allowed()).isFalse();
        assertThat(accountDenied.retryAfterSeconds()).isBetween(1L, 900L);

        for (var attempt = 1; attempt <= 20; attempt++) {
            assertThat(limiter.checkLogin("198.51.100.5", "person" + attempt + "@example.com").allowed())
                    .isEqualTo(attempt <= 20);
        }
        assertThat(limiter.checkLogin("198.51.100.5", "another@example.com").allowed()).isFalse();

        var keys = redis.keys("aicostops:v1:ratelimit:login:*");
        assertThat(keys).isNotEmpty().allSatisfy(key -> {
            assertThat(key).doesNotContain("person@example.com", "203.0.113.1", "198.51.100.5");
            assertThat(redis.getExpire(key)).isBetween(1L, 900L);
        });
    }

    @Test
    void keepsIpBucketsDistinctOrSharedWhileAccountBucketsRemainAccountScoped() {
        var fixedClock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);
        limiter = new RedisRateLimiter(redis, fixedClock, 20, 8, Duration.ofMinutes(15));

        limiter.checkLogin("198.51.100.10", "person@example.com");
        limiter.checkLogin("198.51.100.11", "person@example.com");
        limiter.checkLogin("198.51.100.10", "other@example.com");

        var window = fixedClock.instant().getEpochSecond() / Duration.ofMinutes(15).toSeconds();
        assertThat(redis.opsForValue().get(key("ip", "198.51.100.10", window))).isEqualTo("2");
        assertThat(redis.opsForValue().get(key("ip", "198.51.100.11", window))).isEqualTo("1");
        assertThat(redis.opsForValue().get(key("account", "person@example.com", window))).isEqualTo("2");
        assertThat(redis.opsForValue().get(key("account", "other@example.com", window))).isEqualTo("1");
    }

    private String key(String scope, String value, long window) {
        return "aicostops:v1:ratelimit:login:" + scope + ":" + TokenDigest.sha256(value) + ":" + window;
    }
}
