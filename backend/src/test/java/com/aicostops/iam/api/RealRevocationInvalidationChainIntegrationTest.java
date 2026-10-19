package com.aicostops.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.expense.application.ExpenseClaimCommandService;
import com.aicostops.expense.application.ExpenseCommands.CreateExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.SubmitExpenseCommand;
import com.aicostops.expense.application.ExpenseEvidenceUploadService;
import com.aicostops.iam.application.AuthorizationContextCache;
import com.aicostops.iam.application.AuthorizationInvalidationService;
import com.aicostops.iam.application.SecurityVersionService;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import com.aicostops.testsupport.MinioAuthenticationContainersSupport;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real revocation entry points (DELETE /api/v1/role-assignments/{id} and
 * PATCH /api/v1/users/{id}/status) exercised over MockMvc after a deliberately
 * warm authorization cache: the warm-up reads are served from Redis, so the
 * post-revocation rejections below prove the afterCommit cache maintenance
 * (version put, context evict, refresh-session revokeAll), not cache absence.
 *
 * <p>No flushAll is used after warm-up: the invalidation that rejects the old
 * identities must come from the real authorization-change transaction.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=revocation-chain-test-only-signing-secret-more-than-32-bytes",
        "aicostops.auth.refresh-session-lifetime=7d"
})
@AutoConfigureMockMvc
@Tag("integration")
class RealRevocationInvalidationChainIntegrationTest extends MinioAuthenticationContainersSupport {

    private static final byte[] RECEIPT = "revoke-chain-receipt".getBytes(StandardCharsets.UTF_8);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtTokenService jwt;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired ExpenseClaimCommandService expenseCommands;
    @Autowired ExpenseEvidenceUploadService expenseEvidence;

    private long orgId;
    private long adminUserId;
    private long employeeUserId;
    private String adminRefreshCookie;

    /**
     * Gate for the abort injection below. A real revocation command must run
     * entirely (business write, version update, afterCommit registration) before
     * it is aborted, which no plain Mockito spy can do on an AOP-proxied
     * {@code @Transactional} bean, so a {@code @Primary} subclass runs the real
     * bump and then throws only while this flag is set.
     */
    private static final AtomicBoolean ABORT_REVOCATION = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();

        orgId = insertOrganization("Revocation Chain", "revocation-chain");
        adminUserId = insertUser("admin@revocation-chain.test");
        insertMember(orgId, adminUserId);
        employeeUserId = insertUser("employee@revocation-chain.test");
        insertMember(orgId, employeeUserId);
        createPermissionRole("REV_ADMIN", List.of("ROLE_ASSIGN", "USER_MANAGE", "USER_READ"));
        createPermissionRole("REV_EMPLOYEE", List.of(
                "EXPENSE_CREATE_OWN", "EXPENSE_SUBMIT_OWN", "EVIDENCE_UPLOAD_OWN", "EXPENSE_READ_OWN"));
        assign(adminUserId, "REV_ADMIN");
        assign(employeeUserId, "REV_EMPLOYEE");

        // Real login entry: the admin's refresh session lives in Redis with the
        // initial security version.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@revocation-chain.test\",\"password\":\"valid-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        adminRefreshCookie = login.getResponse().getCookie("aicostops_refresh").getValue();
    }

    /**
     * Warm the admin context cache with a USER_READ request, then revoke the
     * employee's role through the real API. Every stale identity must be
     * rejected while the fresh login keeps working: the old access token is
     * unauthorized by the bumped security version, the old refresh session is
     * gone while a post-revocation login succeeds, the warm cached context no
     * longer grants EXPENSE_SUBMIT_OWN for the submit replay, and the same-key
     * replay never returns the cached success.
     */
    @Test
    void roleRevocationThroughRealApiRejectsEveryStaleIdentityWithoutFlushingCaches()
            throws Exception {
        var expenseId = createSubmittedExpense(employeeUserId);

        // Warm the admin's authorization context into Redis (USER_READ is only
        // needed for this warm-up call).
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(adminUserId)))
                .andExpect(status().isOk());
        assertThat(contextCacheValue(adminUserId, 7)).isNotNull();

        var assignmentId = jdbc.queryForObject("""
                SELECT ra.id FROM role_assignment ra
                JOIN `role` r ON r.id=ra.role_id WHERE r.code='REV_EMPLOYEE'
                """, Long.class);
        var oldEmployeeJwt = bearer(employeeUserId);
        var preRevocationCookie = login("employee@revocation-chain.test");

        mockMvc.perform(delete("/api/v1/role-assignments/{id}", assignmentId)
                        .header("Authorization", bearer(adminUserId)))
                .andExpect(status().isNoContent());
        assertThat(securityVersion(employeeUserId)).isEqualTo(8L);

        // 1) Old access token: the filter's version check rejects it.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", oldEmployeeJwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));

        // 2) Old refresh session: afterCommit revokeAll removed it; a replayed
        // refresh is rejected and no new session appears.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080")
                        .cookie(new Cookie("aicostops_refresh", preRevocationCookie)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080")
                        .cookie(new Cookie("aicostops_refresh", preRevocationCookie)))
                .andExpect(status().isUnauthorized());
        assertThat(refreshSessionCount(employeeUserId)).isZero();

        // 3) The warm admin context cache still works for the unchanged actor
        // (same security version, untouched grants).
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(adminUserId)))
                .andExpect(status().isOk());

        // 4) Same-key submit replay after revocation: the permission gate runs
        // before idempotency reserve/replay, so the cached success is not
        // returned even though the key and request hash still match.
        mockMvc.perform(post("/api/v1/expenses/{id}/submit", expenseId)
                        .header("Authorization", oldEmployeeJwt)
                        .header("Idempotency-Key", "revoke-chain-submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":\"2\"}"))
                .andExpect(status().isUnauthorized());

        // 5) Fresh login keeps working and issues a new working session.
        var freshLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"employee@revocation-chain.test\",\"password\":\"valid-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var freshAccess = com.jayway.jsonpath.JsonPath.<String>read(
                freshLogin.getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + freshAccess))
                .andExpect(status().isOk());
    }

    /**
     * Disabling the user through the real API must reject the warm cached
     * context, the old access token and the refresh session created before the
     * change, while the durable version bump and audit remain intact.
     */
    @Test
    void userDisableThroughRealApiInvalidatesWarmIdentityState() throws Exception {
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(adminUserId)))
                .andExpect(status().isOk());
        assertThat(contextCacheValue(adminUserId, 7)).isNotNull();

        var oldEmployeeJwt = bearer(employeeUserId);
        var preDisableCookie = login("employee@revocation-chain.test");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", oldEmployeeJwt))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/users/{id}/status", employeeUserId)
                        .header("Authorization", bearer(adminUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"expectedVersion\":\"7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityVersion").value("8"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", oldEmployeeJwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080")
                        .cookie(new Cookie("aicostops_refresh", preDisableCookie)))
                .andExpect(status().isUnauthorized());
        // The afterCommit put still writes the new version key; the durable
        // status gate is what makes every version unusable: a token bearing
        // the new version is also rejected because findActiveSecurityVersion
        // is status-gated in MySQL.
        assertThat(redis.opsForValue().get(securityKey(employeeUserId))).isEqualTo("8");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(employeeUserId, 8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type='USER_DISABLED'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * Identity rejection BEFORE the transaction: the actor's own security version
     * is bumped out-of-band, so the bearer token presented for the command is
     * already stale and the request filter rejects it. Nothing of the command
     * runs, so version, assignment and audit are all untouched and the previously
     * valid identities keep working. This is a stale-identity rejection, not a
     * revocation-transaction rollback (that is covered by
     * {@link #revocationTransactionFailureRollsBackPermissionVersionAuditAndAfterCommitMaintenance}).
     */
    @Test
    void revocationRequestWithStaleActorIdentityIsRejectedBeforeTheTransactionRuns()
            throws Exception {
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(adminUserId)))
                .andExpect(status().isOk());
        var assignmentId = jdbc.queryForObject("""
                SELECT ra.id FROM role_assignment ra
                JOIN `role` r ON r.id=ra.role_id WHERE r.code='REV_EMPLOYEE'
                """, Long.class);
        var oldEmployeeJwt = bearer(employeeUserId);
        var cookie = login("employee@revocation-chain.test");

        // Bump the actor's own security version out-of-band so the presented
        // token (version 7) can never pass the request filter.
        jdbc.update("UPDATE app_user SET security_version=8 WHERE id=?", adminUserId);

        mockMvc.perform(delete("/api/v1/role-assignments/{id}", assignmentId)
                        .header("Authorization", bearer(adminUserId, 7)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));

        assertThat(assignmentExists(assignmentId)).isTrue();
        assertThat(securityVersion(employeeUserId)).isEqualTo(7L);
        // Only the two setUp/logins audited LOGIN_SUCCESS; the rejected command
        // never entered its transaction so it contributed nothing.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type IN "
                        + "('ROLE_REVOKED','USER_DISABLED','USER_ENABLED')",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type='LOGIN_SUCCESS'",
                Integer.class)).isEqualTo(2);
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", oldEmployeeJwt))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080")
                        .cookie(new Cookie("aicostops_refresh", cookie)))
                .andExpect(status().isOk());
    }

    /**
     * A valid actor enters the REAL revocation transaction; the business write
     * (assignment delete) and the security-version bump happen and the afterCommit
     * cache maintenance is registered; then the command aborts and the whole
     * transaction rolls back. Permission, version and audit must all be restored,
     * the afterCommit cache maintenance must NOT run, and the revokee's previously
     * valid identity, warmed context cache and refresh session must keep working.
     */
    @Test
    void revocationTransactionFailureRollsBackPermissionVersionAuditAndAfterCommitMaintenance()
            throws Exception {
        // Warm the REVOKEE's own runtime caches (not the admin's): the durable
        // security version, the context cache for the current version, and the
        // Redis refresh session.
        var oldEmployeeJwt = bearer(employeeUserId);
        var employeeCookie = login("employee@revocation-chain.test");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", oldEmployeeJwt))
                .andExpect(status().isOk());
        assertThat(redis.opsForValue().get(securityKey(employeeUserId))).isEqualTo("7");
        assertThat(contextCacheValue(employeeUserId, 7)).isNotNull();
        assertThat(refreshSessionCount(employeeUserId)).isEqualTo(1);

        var assignmentId = jdbc.queryForObject("""
                SELECT ra.id FROM role_assignment ra
                JOIN `role` r ON r.id=ra.role_id WHERE r.code='REV_EMPLOYEE'
                """, Long.class);

        // Abort AFTER the real version bump + afterCommit registration: the
        // @Primary subclass runs the real method, then throws so the transaction
        // rolls back. A DomainException keeps a real HTTP response (409) instead
        // of an unhandled servlet exception.
        ABORT_REVOCATION.set(true);
        try {
            mockMvc.perform(delete("/api/v1/role-assignments/{id}", assignmentId)
                            .header("Authorization", bearer(adminUserId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        } finally {
            ABORT_REVOCATION.set(false);
        }

        // Permission, version and audit rolled back together.
        assertThat(assignmentExists(assignmentId)).isTrue();
        assertThat(securityVersion(employeeUserId)).isEqualTo(7L);
        // The in-transaction delete and version UPDATE did run and the ROLE_REVOKED
        // audit was appended; the rollback removed all three.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type='ROLE_REVOKED'",
                Integer.class)).isZero();

        // afterCommit cache maintenance did not run: the warmed security key still
        // reads the old version, the old context cache row is still present, and
        // the refresh session was not revoked.
        assertThat(redis.opsForValue().get(securityKey(employeeUserId))).isEqualTo("7");
        assertThat(contextCacheValue(employeeUserId, 7)).isNotNull();
        assertThat(refreshSessionCount(employeeUserId)).isEqualTo(1);

        // The previously valid identity and refresh session keep working.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", oldEmployeeJwt))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080")
                        .cookie(new Cookie("aicostops_refresh", employeeCookie)))
                .andExpect(status().isOk());
    }

    private static final List<String> TEST_ROLE_CODES =
            List.of("REV_ADMIN", "REV_EMPLOYEE");

    @AfterEach
    void tearDown() {
        ABORT_REVOCATION.set(false);
        // The IAM suites that share this container do not clean audit_event or
        // expense-side rows in their own setup, so rows left here would fail
        // their app_user cleanup. M2DatabaseCleaner is FK-safe in one pass;
        // the seeded role/permission catalogs must survive, so only the roles
        // this test created are removed.
        com.aicostops.testsupport.M2DatabaseCleaner.clean(jdbc);
        deleteTestRoles();
    }

    private void cleanDatabase() {
        com.aicostops.testsupport.M2DatabaseCleaner.clean(jdbc);
        deleteTestRoles();
    }

    private void deleteTestRoles() {
        for (var code : TEST_ROLE_CODES) {
            jdbc.update("""
                    DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id
                    WHERE r.code=?
                    """, code);
            jdbc.update("DELETE FROM `role` WHERE code=?", code);
        }
    }

    // -- abort injection -------------------------------------------------------

    @TestConfiguration
    static class AbortableRevocationConfig {

        /**
         * Replaces the real {@link AuthorizationInvalidationService} with a
         * {@code @Primary} subclass that runs the real bump (version update +
         * afterCommit registration) and only then aborts while
         * {@link #ABORT_REVOCATION} is set. All other tests in this class keep the
         * unmodified behaviour.
         */
        @Bean
        @Primary
        AuthorizationInvalidationService abortableInvalidationService(
                IamMapper iamMapper,
                SecurityVersionService securityVersions,
                AuthorizationContextCache authorizationContexts,
                RedisRefreshSessionRepository refreshSessions,
                Clock clock) {
            return new AuthorizationInvalidationService(
                    iamMapper, securityVersions, authorizationContexts, refreshSessions, clock) {
                @Override
                @Transactional(propagation = Propagation.MANDATORY)
                public long bumpInTransaction(long targetUserId, long oldVersion) {
                    var newVersion = super.bumpInTransaction(targetUserId, oldVersion);
                    if (ABORT_REVOCATION.get()) {
                        throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                                "Revocation aborted",
                                "The revocation command was aborted after the security version update.");
                    }
                    return newVersion;
                }
            };
        }
    }

    // -- helpers ---------------------------------------------------------------

    private long createSubmittedExpense(long employeeId) {
        var employee = new AuthenticatedUser(employeeId, 7);
        var expenseId = expenseCommands.create(employee,
                new CreateExpenseCommand(LocalDate.parse("2026-08-01"),
                        new BigDecimal("50.00000000"), "CNY"),
                "revoke-chain-create").id();
        expenseEvidence.attach(employee, expenseId, 0, "receipt.pdf", "application/pdf",
                new ByteArrayInputStream(RECEIPT));
        expenseCommands.submit(employee, expenseId, new SubmitExpenseCommand(1),
                "revoke-chain-submit");
        return expenseId;
    }

    private String login(String email) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"valid-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("aicostops_refresh").getValue();
    }

    private String bearer(long userId) {
        return bearer(userId, 7);
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

    private boolean assignmentExists(long assignmentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_assignment WHERE id=?", Integer.class, assignmentId) == 1;
    }

    private int refreshSessionCount(long userId) {
        var members = redis.opsForSet().members("aicostops:v1:auth:user-sessions:" + userId);
        return members == null ? 0 : members.size();
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)"
                        + " VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO app_user(email_normalized,display_name,status,security_version,"
                        + "created_at,updated_at) VALUES (?,'Revocation User','ACTIVE',7,"
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
