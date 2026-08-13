package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Tag("integration")
class AuthorizationInvalidationServiceIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private AuthorizationInvalidationService invalidationService;

    @Autowired
    private RedisRefreshSessionRepository refreshSessions;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("DELETE FROM invitation");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    void authorizationChangeBumpsDurableVersion() {
        var targetUserId = insertUser("disabled-target@invalidation.test", "DISABLED", 7L);
        var otherUserId = insertUser("other-user@invalidation.test", "ACTIVE", 13L);

        var newVersion = new TransactionTemplate(transactionManager).execute(
                status -> invalidationService.bumpInTransaction(targetUserId, 7L));

        assertThat(newVersion).isEqualTo(8L);
        assertThat(securityVersion(targetUserId)).isEqualTo(8L);
        assertThat(securityVersion(otherUserId)).isEqualTo(13L);
    }

    @Test
    void afterCommitMaintainsRuntimeCaches() {
        var targetUserId = insertUser("runtime-target@invalidation.test", "ACTIVE", 7L);
        var securityKey = "aicostops:v1:auth:security:" + targetUserId;
        var oldContextKey = "aicostops:v1:iam:context:" + targetUserId + ":7";
        var newContextKey = "aicostops:v1:iam:context:" + targetUserId + ":8";
        redis.opsForValue().set(securityKey, "7");
        redis.opsForValue().set(oldContextKey, "stale-old-context");
        redis.opsForValue().set(newContextKey, "stale-new-context");
        var refresh = refreshSessions.create(targetUserId, 22L, 7L, "browser");

        var newVersion = new TransactionTemplate(transactionManager).execute(status -> {
            var version = invalidationService.bumpInTransaction(targetUserId, 7L);
            assertThat(redis.opsForValue().get(securityKey)).isEqualTo("7");
            assertThat(redis.opsForValue().get(oldContextKey)).isEqualTo("stale-old-context");
            assertThat(redis.opsForValue().get(newContextKey)).isEqualTo("stale-new-context");
            assertThat(refreshSessions.load(refresh.value())).isNotNull();
            return version;
        });

        assertThat(newVersion).isEqualTo(8L);
        assertThat(securityVersion(targetUserId)).isEqualTo(8L);
        assertThat(redis.opsForValue().get(securityKey)).isEqualTo("8");
        assertThat(redis.opsForValue().get(oldContextKey)).isNull();
        assertThat(redis.opsForValue().get(newContextKey)).isNull();
        assertThat(refreshSessions.load(refresh.value())).isNull();
    }

    private long insertUser(String email, String status, long securityVersion) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?, 'Invalidation Test', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, email, status, securityVersion);
        return jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long securityVersion(long userId) {
        return jdbc.queryForObject(
                "SELECT security_version FROM app_user WHERE id=?", Long.class, userId);
    }
}
