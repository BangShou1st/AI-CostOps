package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicostops.iam.infrastructure.IamMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class SecurityVersionServiceTest {
    @SuppressWarnings("unchecked")
    @Test
    void fallsBackToMysqlWhenRedisIsUnavailable() {
        var redis = mock(StringRedisTemplate.class);
        var values = mock(ValueOperations.class);
        var iam = mock(IamMapper.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("aicostops:v1:auth:security:42"))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(iam.findActiveSecurityVersion(42)).thenReturn(7L);

        var service = new SecurityVersionService(redis, iam, Duration.ofMinutes(1));

        assertThat(service.current(42)).isEqualTo(7L);
    }
}
