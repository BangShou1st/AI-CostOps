package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.RedisContainerSupport;
import java.time.Clock;
import java.time.Duration;
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
}
