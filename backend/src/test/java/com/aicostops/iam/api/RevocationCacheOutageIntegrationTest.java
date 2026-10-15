package com.aicostops.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The afterCommit cache maintenance of a real revocation is best-effort: a
 * Redis outage during the maintenance must NOT undo the durable security
 * version bump or the audit row, and the next request must still reject the
 * old access token because the durable truth (MySQL) no longer matches the
 * token version. This class injects the outage into each collaborator
 * separately while the revocation command runs through the real HTTP entry.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=revocation-outage-test-only-signing-secret-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class RevocationCacheOutageIntegrationTest extends AuthenticationContainersSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService jwt;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @MockitoSpyBean StringRedisTemplate redis;
    @MockitoSpyBean RedisRefreshSessionRepository refreshSessions;

    private long orgId;
    private long adminUserId;
    private long employeeUserId;
    private String employeeOldJwt;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        orgId = insertOrganization("Revocation Outage", "revocation-outage");
        adminUserId = insertUser("admin@revocation-outage.test");
        insertMember(orgId, adminUserId);
        employeeUserId = insertUser("employee@revocation-outage.test");
        insertMember(orgId, employeeUserId);
        createPermissionRole("REV_OUTAGE_ADMIN", List.of("ROLE_ASSIGN"));
        createPermissionRole("REV_OUTAGE_EMPLOYEE", List.of("EXPENSE_SUBMIT_OWN"));
        assign(adminUserId, "REV_OUTAGE_ADMIN");
        assign(employeeUserId, "REV_OUTAGE_EMPLOYEE");

        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        employeeOldJwt = bearer(employeeUserId, 7);
        // Warm every runtime cache so the outage hits a live maintenance path,
        // not an empty cache.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", employeeOldJwt))
                .andExpect(status().isOk());
        assertThat(redis.opsForValue().get(securityKey(employeeUserId))).isEqualTo("7");
        assertThat(contextCacheValue(employeeUserId, 7)).isNotNull();
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.reset(redis, refreshSessions);
        clean();
    }

    /**
     * The Redis SET behind the security-version write fails. MySQL committed
     * the new version, so the old token is still rejected by the filter's
     * durable comparison; the next successful read repopulates the Redis key
     * with the new version. The injection lands on the value-operations SET so
     * the production try-catch inside {@link SecurityVersionService#put} is
     * exercised for real.
     */
    @Test
    @SuppressWarnings("unchecked")
    void failedSecurityVersionPutStillRejectsOldTokenThroughDurableVersion()
            throws Exception {
        // Wrap the REAL value operations: exactly one SET against the
        // security-version key fails (the afterCommit put), so the in-service
        // catch inside SecurityVersionService.put is exercised for real while
        // later reads and cache refreshes stay live.
        var realOps = redis.opsForValue();
        var injectOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        var failingOps = (org.springframework.data.redis.core.ValueOperations<String, String>)
                java.lang.reflect.Proxy.newProxyInstance(
                        org.springframework.data.redis.core.ValueOperations.class.getClassLoader(),
                        new Class<?>[] {org.springframework.data.redis.core.ValueOperations.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("set") && args != null && args.length >= 2
                                    && String.valueOf(args[0])
                                            .equals("aicostops:v1:auth:security:" + employeeUserId)
                                    && injectOnce.compareAndSet(true, false)) {
                                throw new org.springframework.dao.DataAccessResourceFailureException(
                                        "redis down");
                            }
                            try {
                                return method.invoke(realOps, args);
                            } catch (java.lang.reflect.InvocationTargetException target) {
                                throw target.getCause();
                            }
                        });
        doReturn(failingOps).when(redis).opsForValue();

        mockMvc.perform(delete("/api/v1/role-assignments/{id}", assignmentId())
                        .header("Authorization", bearer(adminUserId, 7)))
                .andExpect(status().isNoContent());

        assertThat(securityVersion(employeeUserId)).isEqualTo(8L);
        // The in-service catch swallowed the Redis failure: the stale key
        // value remains until a later successful read refreshes it.
        assertThat(realOps.get(securityKey(employeeUserId))).isEqualTo("7");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", employeeOldJwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        // The next successful read refreshes the cache from durable truth.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(employeeUserId, 8)))
                .andExpect(status().isOk());
        assertThat(realOps.get(securityKey(employeeUserId))).isEqualTo("8");
    }

    /**
     * Context eviction fails in afterCommit: the stale cached context for the
     * OLD version is unreachable anyway (the filter rejects the old token
     * before any context lookup), and the durable version bump guarantees no
     * request can ever re-read it. The injection lands on the underlying
     * Redis delete used by {@link RedisAuthorizationContextCache#evict}.
     */
    @Test
    void failedContextEvictionCannotResurrectOldGrants() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("redis down"))
                .when(redis).delete(org.mockito.ArgumentMatchers.startsWith(
                        "aicostops:v1:iam:context:"));

        mockMvc.perform(delete("/api/v1/role-assignments/{id}", assignmentId())
                        .header("Authorization", bearer(adminUserId, 7)))
                .andExpect(status().isNoContent());

        assertThat(securityVersion(employeeUserId)).isEqualTo(8L);
        // The stale old-version context row physically remains, but it is
        // unreachable: every old-version token is rejected by the filter.
        assertThat(contextCacheValue(employeeUserId, 7)).isNotNull();
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", employeeOldJwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        // The fresh context is reloaded from MySQL and no longer grants the
        // revoked permission.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(employeeUserId, 8)))
                .andExpect(status().isOk());
    }

    /**
     * Refresh-session revokeAll fails in afterCommit: the old refresh session
     * physically survives in Redis, but the rotation path re-checks the
     * durable security version before issuing tokens, so the old session can
     * never obtain a fresh access token.
     */
    @Test
    void failedRefreshRevokeAllStillBlocksOldSessionThroughDurableVersion()
            throws Exception {
        var cookie = login("employee@revocation-outage.test");
        assertThat(refreshSessionCount(employeeUserId)).isEqualTo(1);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("redis down"))
                .when(refreshSessions).revokeAll(anyLong());
        mockMvc.perform(delete("/api/v1/role-assignments/{id}", assignmentId())
                        .header("Authorization", bearer(adminUserId, 7)))
                .andExpect(status().isNoContent());

        assertThat(securityVersion(employeeUserId)).isEqualTo(8L);
        // The session physically survives the failed revokeAll...
        assertThat(refreshSessionCount(employeeUserId)).isEqualTo(1);
        // ...but refresh re-checks the durable version and revokes the session
        // instead of issuing tokens.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080")
                        .cookie(new Cookie("aicostops_refresh", cookie)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        assertThat(refreshSessionCount(employeeUserId)).isZero();
    }

    // -- helpers ---------------------------------------------------------------

    private long assignmentId() {
        return jdbc.queryForObject("""
                SELECT ra.id FROM role_assignment ra
                JOIN `role` r ON r.id=ra.role_id WHERE r.code='REV_OUTAGE_EMPLOYEE'
                """, Long.class);
    }

    private String login(String email) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"valid-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("aicostops_refresh").getValue();
    }

    private String bearer(long userId, long securityVersion) {
        return "Bearer " + jwt.issue(userId, securityVersion).token();
    }

    private String securityKey(long userId) {
        return "aicostops:v1:auth:security:" + userId;
    }

    private String contextCacheValue(long userId, long version) {
        return redis.opsForValue().get("aicostops:v1:iam:context:" + userId + ":" + version);
    }

    private long securityVersion(long userId) {
        return jdbc.queryForObject(
                "SELECT security_version FROM app_user WHERE id=?", Long.class, userId);
    }

    private int refreshSessionCount(long userId) {
        var members = redis.opsForSet().members("aicostops:v1:auth:user-sessions:" + userId);
        return members == null ? 0 : members.size();
    }

    private void clean() {
        // M2DatabaseCleaner is FK-safe in one pass and also removes the
        // expense/evidence rows the revoked-identity tests create; the seeded
        // role/permission catalogs must survive, so only this test's roles
        // are removed.
        com.aicostops.testsupport.M2DatabaseCleaner.clean(jdbc);
        for (var code : List.of("REV_OUTAGE_ADMIN", "REV_OUTAGE_EMPLOYEE")) {
            jdbc.update("""
                    DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id
                    WHERE r.code=?
                    """, code);
            jdbc.update("DELETE FROM `role` WHERE code=?", code);
        }
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)"
                        + " VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO app_user(email_normalized,display_name,status,security_version,"
                        + "created_at,updated_at) VALUES (?,'Outage User','ACTIVE',7,"
                        + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", email);
        var userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
        jdbc.update("INSERT INTO user_credential(user_id,password_hash,password_changed_at,updated_at)"
                        + " VALUES (?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                userId, passwordEncoder.encode("valid-password"));
        return userId;
    }

    private void insertMember(long orgId, long userId) {
        jdbc.update("INSERT INTO organization_member(org_id,user_id,status,joined_at)"
                + " VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))", orgId, userId);
    }

    private void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("INSERT INTO role_permission(role_id,permission_id)"
                            + " SELECT r.id,p.id FROM `role` r JOIN permission p WHERE r.code=? AND p.code=?",
                    roleCode, permission);
        }
    }

    private void assign(long userId, String roleCode) {
        var memberId = jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE user_id=?", Long.class, userId);
        jdbc.update("INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,"
                        + "assigned_by,created_at) SELECT ?,id,'ORG',?,NULL,UTC_TIMESTAMP(6)"
                        + " FROM `role` WHERE code=?", memberId, orgId, roleCode);
    }
}
