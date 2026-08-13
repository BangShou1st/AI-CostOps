package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.testsupport.RedisContainerSupport;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.flyway.enabled=false")
@Tag("integration")
class RedisAuthorizationContextCacheIntegrationTest extends RedisContainerSupport {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void storesAndLoadsTheCompleteContextAtTheVersionedKeyForSixtySeconds() {
        var cache = new RedisAuthorizationContextCache(redis, objectMapper, Duration.ofSeconds(60));
        var context = new AuthorizationContext(
                11L, 22L, 33L, 7L,
                Set.of(new ScopedPermissionGrant("LEDGER_POST", ScopeType.COST_CENTER, 44L)),
                Set.of("FINANCE_REVIEWER"));

        cache.put(context);

        var key = "aicostops:v1:iam:context:11:7";
        assertThat(redis.opsForValue().get(key)).isNotBlank();
        assertThat(redis.getExpire(key)).isBetween(55L, 60L);
        assertThat(cache.get(11L, 7L)).isEqualTo(context);
        assertThat(cache.get(11L, 7L).roleCodes()).containsExactly("FINANCE_REVIEWER");
    }
}
