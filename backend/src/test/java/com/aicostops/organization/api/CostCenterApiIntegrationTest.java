package com.aicostops.organization.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=team-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class CostCenterApiIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;

    private long organizationId;
    private long foreignOrganizationId;
    private long actorUserId;
    private long actorMemberId;
    private long activeCostCenterId;
    private long disabledCostCenterId;
    private long archivedCostCenterId;
    private long foreignCostCenterId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cleanDatabase();

        organizationId = insertOrganization("Cost Center API", "cost-center-api");
        foreignOrganizationId = insertOrganization("Foreign", "cost-center-api-foreign");
        actorUserId = insertUser("cost-center-admin@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        activeCostCenterId = insertCostCenter(organizationId, "ALPHA", "Alpha", "ACTIVE");
        disabledCostCenterId = insertCostCenter(organizationId, "BETA", "Beta", "DISABLED");
        archivedCostCenterId = insertCostCenter(organizationId, "GAMMA", "Gamma", "ARCHIVED");
        foreignCostCenterId = insertCostCenter(foreignOrganizationId, "FOREIGN", "Foreign", "ACTIVE");
        jdbc.update("UPDATE organization_member SET default_cost_center_id=? WHERE id=?",
                disabledCostCenterId, actorMemberId);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void orgGrantListsCostCenters() throws Exception {
        assign("SYSTEM_ADMIN", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/cost-centers?page=0&size=2").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(
                        Long.toString(activeCostCenterId), Long.toString(disabledCostCenterId))))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/cost-centers?status=DISABLED&page=0&size=1")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(disabledCostCenterId)))
                .andExpect(jsonPath("$.items[0].status").value("DISABLED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void costCenterGrantListsExplicitIds() throws Exception {
        assign("SYSTEM_ADMIN", "COST_CENTER", activeCostCenterId);
        assign("SYSTEM_ADMIN", "COST_CENTER", archivedCostCenterId);
        assign("SYSTEM_ADMIN", "COST_CENTER", foreignCostCenterId);

        mockMvc.perform(get("/api/v1/cost-centers").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(
                        Long.toString(activeCostCenterId), Long.toString(archivedCostCenterId))))
                .andExpect(jsonPath("$.totalElements").value(2));

        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        assign("SYSTEM_ADMIN", "ORG", foreignOrganizationId);
        flushAuthorizationCache();

        mockMvc.perform(get("/api/v1/cost-centers").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void createCostCenterRequiresOrg() throws Exception {
        assign("EMPLOYEE", "ORG", organizationId);
        mockMvc.perform(get("/api/v1/cost-centers").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/cost-centers").header("Authorization", bearer())
                        .contentType("application/json").content("{\"code\":\"DELTA\",\"name\":\"Delta\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        assign("SYSTEM_ADMIN", "COST_CENTER", activeCostCenterId);
        flushAuthorizationCache();
        mockMvc.perform(post("/api/v1/cost-centers").header("Authorization", bearer())
                        .contentType("application/json").content("{\"code\":\"DELTA\",\"name\":\"Delta\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        assign("SYSTEM_ADMIN", "ORG", organizationId);
        flushAuthorizationCache();
        mockMvc.perform(post("/api/v1/cost-centers").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"code\":\"  DELTA  \",\"name\":\"  Delta Center  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.code").value("DELTA"))
                .andExpect(jsonPath("$.name").value("Delta Center"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(jdbc.queryForObject(
                "SELECT org_id FROM cost_center WHERE code='DELTA'", Long.class)).isEqualTo(organizationId);
    }

    @Test
    void wrongCostCenterScopeIsNotFound() throws Exception {
        assign("SYSTEM_ADMIN", "COST_CENTER", activeCostCenterId);
        var body = "{\"name\":\"Renamed\"}";

        mockMvc.perform(patch("/api/v1/cost-centers/{id}", activeCostCenterId)
                        .header("Authorization", bearer()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));

        for (var id : new long[] {disabledCostCenterId, foreignCostCenterId, Long.MAX_VALUE}) {
            mockMvc.perform(patch("/api/v1/cost-centers/{id}", id)
                            .header("Authorization", bearer()).contentType("application/json").content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail")
                            .value("The cost center is not available in the current organization."));
        }
    }

    @Test
    void costCenterCodeIsImmutable() throws Exception {
        assign("SYSTEM_ADMIN", "ORG", organizationId);

        mockMvc.perform(patch("/api/v1/cost-centers/{id}", activeCostCenterId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"code\":\"CHANGED\",\"name\":\"Alpha Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ALPHA"))
                .andExpect(jsonPath("$.name").value("Alpha Renamed"));
        assertThat(jdbc.queryForObject(
                "SELECT code FROM cost_center WHERE id=?", String.class, activeCostCenterId)).isEqualTo("ALPHA");

        mockMvc.perform(post("/api/v1/cost-centers").header("Authorization", bearer())
                        .contentType("application/json").content("{\"code\":\" ALPHA \",\"name\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mockMvc.perform(post("/api/v1/cost-centers").header("Authorization", bearer())
                        .contentType("application/json").content("{\"code\":\"FOREIGN\",\"name\":\"Local\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("FOREIGN"));
    }

    @Test
    void costCenterLifecyclePreservesRow() throws Exception {
        assign("SYSTEM_ADMIN", "ORG", organizationId);

        mockMvc.perform(patch("/api/v1/cost-centers/{id}", activeCostCenterId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(patch("/api/v1/cost-centers/{id}", activeCostCenterId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mockMvc.perform(patch("/api/v1/cost-centers/{id}", activeCostCenterId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(patch("/api/v1/cost-centers/{id}", activeCostCenterId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cost_center WHERE id=? AND status='ARCHIVED'",
                Integer.class, activeCostCenterId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/cost-centers?status=ARCHIVED").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.hasItem(
                        Long.toString(activeCostCenterId))));
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,'Cost Center Admin','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }

    private long insertCostCenter(long orgId, String code, String name, String status) {
        jdbc.update("""
                INSERT INTO cost_center(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, code, name, status);
        return jdbc.queryForObject(
                "SELECT id FROM cost_center WHERE org_id=? AND code=?", Long.class, orgId, code);
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, scopeType, scopeId, roleCode);
    }

    private void flushAuthorizationCache() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private String bearer() {
        return "Bearer " + tokens.issue(actorUserId, 7).token();
    }
}
