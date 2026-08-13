package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.iam.infrastructure.AuthorizationContextMapper;
import com.aicostops.iam.infrastructure.AuthorizationIdentityRecord;
import com.aicostops.iam.infrastructure.RedisAuthorizationContextCache;
import com.aicostops.iam.infrastructure.ScopedPermissionGrantRecord;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

class AuthorizationContextCacheFallbackTest {

    @Test
    void loadsTheMysqlContextWhenCacheMisses() {
        var cache = mock(AuthorizationContextCache.class);
        var mapper = mapperWithCurrentIdentity();
        var service = new AuthorizationContextService(mapper, cache);

        var context = service.current(new AuthenticatedUser(11L, 7L));

        assertThat(context).isEqualTo(expectedContext());
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsTheMysqlContextWhenCacheJsonIsMalformed() {
        var redis = mock(StringRedisTemplate.class);
        var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("aicostops:v1:iam:context:11:7")).thenReturn("{not-json");
        var cache = new RedisAuthorizationContextCache(redis, new ObjectMapper(), Duration.ofSeconds(60));
        var mapper = mapperWithCurrentIdentity();
        var service = new AuthorizationContextService(mapper, cache);

        var context = service.current(new AuthenticatedUser(11L, 7L));

        assertThat(context).isEqualTo(expectedContext());
    }

    @Test
    void loadsTheMysqlContextWhenRedisIsUnavailableInsteadOfReturningEmptyGrants() {
        var cache = mock(AuthorizationContextCache.class);
        when(cache.get(11L, 7L)).thenThrow(new DataAccessResourceFailureException("redis down"));
        var mapper = mapperWithCurrentIdentity();
        var service = new AuthorizationContextService(mapper, cache);

        var context = service.current(new AuthenticatedUser(11L, 7L));

        assertThat(context).isEqualTo(expectedContext());
        assertThat(context.grants()).isNotEmpty();
    }

    @Test
    void deniesAnInvalidMysqlIdentityAfterCacheFailure() {
        var cache = mock(AuthorizationContextCache.class);
        when(cache.get(11L, 7L)).thenThrow(new DataAccessResourceFailureException("redis down"));
        var mapper = mock(AuthorizationContextMapper.class);
        var service = new AuthorizationContextService(mapper, cache);

        assertThatThrownBy(() -> service.current(new AuthenticatedUser(11L, 7L)))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ProblemCode.AUTH_SESSION_EXPIRED);
                    assertThat(exception.status().value()).isEqualTo(401);
                });
    }

    private AuthorizationContextMapper mapperWithCurrentIdentity() {
        var mapper = mock(AuthorizationContextMapper.class);
        when(mapper.findIdentity(11L)).thenReturn(new AuthorizationIdentityRecord(11L, 7L, 33L, 22L));
        when(mapper.findGrants(33L)).thenReturn(List.of(
                new ScopedPermissionGrantRecord("FINANCE_REVIEWER", "LEDGER_POST", "COST_CENTER", 44L)));
        return mapper;
    }

    private AuthorizationContext expectedContext() {
        return new AuthorizationContext(
                11L, 22L, 33L, 7L,
                java.util.Set.of(new ScopedPermissionGrant("LEDGER_POST", ScopeType.COST_CENTER, 44L)),
                java.util.Set.of("FINANCE_REVIEWER"));
    }
}
