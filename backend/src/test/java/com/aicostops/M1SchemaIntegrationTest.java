package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class M1SchemaIntegrationTest extends MySqlContainerSupport {

    private static final Set<String> M1_TABLES = Set.of(
            "organization", "app_user", "user_credential", "organization_member",
            "role", "permission", "role_permission", "role_assignment", "invitation",
            "cost_center", "team", "team_member", "project", "project_member",
            "provider_account", "audit_event", "api_idempotency");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesEveryM1IdentityOrganizationAndEarlyAuditTable() {
        var tables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class));

        assertThat(tables).containsAll(M1_TABLES);
    }

    @Test
    void enforcesNormalizedEmailAndOrganizationMembershipNaturalKeys() {
        var orgId = insertOrganization("schema-natural-key");
        var firstUser = insertUser("schema-key@example.com");

        assertThatThrownBy(() -> insertUser("schema-key@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("INSERT INTO organization_member(org_id,user_id,status,joined_at) VALUES (?,?, 'ACTIVE', UTC_TIMESTAMP(6))", orgId, firstUser);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO organization_member(org_id,user_id,status,joined_at) VALUES (?,?, 'ACTIVE', UTC_TIMESTAMP(6))",
                orgId, firstUser)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesCredentialForeignKeyAndCoreLookupIndexes() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO user_credential(user_id,password_hash,password_changed_at,updated_at) VALUES (999999,'{bcrypt}x',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"))
                .isInstanceOf(DataIntegrityViolationException.class);

        var indexes = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema=DATABASE()",
                String.class));
        assertThat(indexes).contains(
                "uq_app_user_email_normalized",
                "idx_app_user_status",
                "uq_organization_member_org_user",
                "idx_organization_member_user_status",
                "uq_role_assignment_natural",
                "idx_invitation_token_hash");
    }

    private long insertOrganization(String slug) {
        jdbcTemplate.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES (?,?, 'ACTIVE', JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                "Schema Test", slug);
        return jdbcTemplate.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbcTemplate.update("INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at) VALUES (?,?, 'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                email, "Schema User");
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }
}
