package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRateLimiterTest {
    @SuppressWarnings("unchecked")
    @Test
    void failsClosedWithStableServiceUnavailableProblem() {
        var redis = mock(StringRedisTemplate.class); var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        var limiter = new RedisRateLimiter(redis,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC), 20, 8, Duration.ofMinutes(15));

        var error = catchThrowableOfType(DomainException.class,
                () -> limiter.checkLogin("203.0.113.1", "person@example.com"));

        assertThat(error.code()).isEqualTo(ProblemCode.REDIS_UNAVAILABLE_FOR_AUTH);
        assertThat(error.status().value()).isEqualTo(503);
    }
}
