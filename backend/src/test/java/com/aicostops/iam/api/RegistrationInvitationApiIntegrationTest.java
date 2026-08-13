package com.aicostops.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.domain.TokenDigest;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "aicostops.auth.allow-public-registration=true",
        "aicostops.auth.public-registration-org-slug=api-registration-org"
})
@AutoConfigureMockMvc
@Tag("integration")
class RegistrationInvitationApiIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long organizationId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM audit_event");
        jdbcTemplate.update("DELETE FROM invitation");
        jdbcTemplate.update("DELETE FROM role_assignment");
        jdbcTemplate.update("DELETE FROM organization_member");
        jdbcTemplate.update("DELETE FROM user_credential");
        jdbcTemplate.update("DELETE FROM app_user");
        jdbcTemplate.update("DELETE FROM organization WHERE slug='api-registration-org'");
        jdbcTemplate.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES ('API Registration','api-registration-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        organizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug='api-registration-org'", Long.class);
    }

    @Test
    void exposesPublicRegistrationWithStringIds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"api@registration.test","displayName":"API User","password":"valid-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.organizationMemberId").isString())
                .andExpect(jsonPath("$.organizationId").value(Long.toString(organizationId)));
    }

    @Test
    void exposesInvitationAcceptanceWithoutReturningTheInvitationSecret() throws Exception {
        var token = "api-invitation-secret";
        jdbcTemplate.update("""
                INSERT INTO invitation(org_id,email_normalized,token_hash,initial_role_code,status,expires_at,created_at)
                VALUES (?,?,?,'EMPLOYEE','PENDING',?,UTC_TIMESTAMP(6))
                """, organizationId, "api-invite@registration.test", TokenDigest.sha256(token),
                Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Invited API User","password":"valid-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.organizationId").value(Long.toString(organizationId)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
    }
}
