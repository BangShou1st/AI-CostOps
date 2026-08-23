package com.aicostops.audit;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared fixtures for the audit query tests: an org-wide AUDIT_READ reader,
 * a foreign-org AUDIT_READ reader, a permission-less member, a member holding
 * only an unrelated read permission, and raw {@code audit_event} seeding.
 */
public abstract class AuditQueryTestSupport extends AuthenticationContainersSupport {

    protected static final List<String> READER_PERMISSIONS = List.of("AUDIT_READ");
    protected static final List<String> FOREIGN_READER_PERMISSIONS = List.of("AUDIT_READ");
    protected static final List<String> UNRELATED_PERMISSIONS = List.of("BUDGET_READ");

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected JwtTokenService tokens;

    protected long fixtureCounter;

    protected long orgId;
    protected long foreignOrgId;
    protected long readerUserId;
    protected long readerMemberId;
    protected long foreignReaderUserId;
    protected long foreignReaderMemberId;
    protected long plainUserId;
    protected long plainMemberId;

    @BeforeEach
    void setUpBase() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgId = insertOrganization("Audit Org", "audit-" + suffix);
        foreignOrgId = insertOrganization("Audit Foreign", "audit-foreign-" + suffix);
        readerUserId = insertUser("audit-reader-" + suffix + "@example.com");
        readerMemberId = insertMember(orgId, readerUserId);
        foreignReaderUserId = insertUser("audit-foreign-" + suffix + "@example.com");
        foreignReaderMemberId = insertMember(foreignOrgId, foreignReaderUserId);
        plainUserId = insertUser("audit-plain-" + suffix + "@example.com");
        plainMemberId = insertMember(orgId, plainUserId);

        createPermissionRole("AUDIT_READER", READER_PERMISSIONS);
        assign("AUDIT_READER", "ORG", orgId, readerMemberId);
        createPermissionRole("AUDIT_FOREIGN_READER", FOREIGN_READER_PERMISSIONS);
        assign("AUDIT_FOREIGN_READER", "ORG", foreignOrgId, foreignReaderMemberId);
        createPermissionRole("AUDIT_UNRELATED", UNRELATED_PERMISSIONS);
        assign("AUDIT_UNRELATED", "ORG", orgId, plainMemberId);
    }

    @AfterEach
    void tearDownBase() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    // -- authentication --------------------------------------------------------

    protected String readerBearer() {
        return "Bearer " + tokens.issue(readerUserId, 7).token();
    }

    protected String foreignReaderBearer() {
        return "Bearer " + tokens.issue(foreignReaderUserId, 7).token();
    }

    protected String plainBearer() {
        return "Bearer " + tokens.issue(plainUserId, 7).token();
    }

    // -- fixtures --------------------------------------------------------------

    protected void insertAuditEvent(long org, long actorUserId, String eventType,
            String metadataJson, String createdAt) {
        jdbc.update("""
                INSERT INTO audit_event(
                    org_id,actor_user_id,event_type,subject_type,subject_id,metadata_json,created_at)
                VALUES (?,?,?,'USER',?,?,?)
                """, org, actorUserId, eventType, actorUserId, metadataJson, createdAt);
    }

    protected long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    protected long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Audit Worker");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?",
                Long.class, email);
    }

    protected long insertMember(long org, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, org, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, org, userId);
    }

    protected void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('AUDIT_READER','AUDIT_FOREIGN_READER','AUDIT_UNRELATED')
                """);
        jdbc.update("""
                DELETE FROM `role`
                WHERE code IN ('AUDIT_READER','AUDIT_FOREIGN_READER','AUDIT_UNRELATED')
                """);
    }

    protected void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, roleCode, permission);
        }
    }

    protected void assign(String roleCode, String scopeType, long scopeId, long targetMemberId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, targetMemberId, scopeType, scopeId, roleCode);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}
