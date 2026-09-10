package com.aicostops.gatewayadmin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Governed Service Identity lifecycle at the HTTP boundary. */
@SpringBootTest
@Tag("integration")
@AutoConfigureMockMvc
class ServiceIdentityApiIntegrationTest extends ControlPlaneApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managerCreatesListsAndReadsOwnOrganizationIdentities() throws Exception {
        var create = mockMvc.perform(post("/api/v1/service-identities")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"m16-uat-bot","name":"M16 UAT Bot"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.code").value("m16-uat-bot"))
                .andExpect(jsonPath("$.name").value("M16 UAT Bot"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        var id = readLong(create, "id");
        mockMvc.perform(get("/api/v1/service-identities")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/service-identities/" + id)
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("m16-uat-bot"));
    }

    @Test
    void readerCanReadWithoutManaging() throws Exception {
        long id = insertServiceIdentity(orgId, "readable-" + fixtureCounter);
        mockMvc.perform(get("/api/v1/service-identities/" + id)
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/service-identities")
                        .header("Authorization", readerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"reader-forbidden\",\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unrelatedPermissionCannotSeeOrCreateIdentities() throws Exception {
        mockMvc.perform(get("/api/v1/service-identities")
                        .header("Authorization", plainBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/service-identities")
                        .header("Authorization", plainBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"plain\",\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void foreignOrganizationIdentityIsInvisibleAndNotAddressable() throws Exception {
        long foreignId = insertServiceIdentity(foreignOrgId, "foreign-" + fixtureCounter);
        mockMvc.perform(get("/api/v1/service-identities/" + foreignId)
                        .header("Authorization", managerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/service-identities")
                        .header("Authorization", foreignManagerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(Long.toString(foreignId)));
    }

    @Test
    void createIsAuditedWithoutAnySecretShapedMetadata() throws Exception {
        var create = mockMvc.perform(post("/api/v1/service-identities")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"audited-bot\",\"name\":\"Audited Bot\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = readLong(create, "id");

        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("SERVICE_IDENTITY_CREATED"))
                .andExpect(jsonPath("$.items[0].subjectType").value("SERVICE_IDENTITY"))
                .andExpect(jsonPath("$.items[0].subjectId").value(Long.toString(id)))
                .andExpect(jsonPath("$.items[0].metadata.status").value("ACTIVE"));
    }

    private static long readLong(org.springframework.test.web.servlet.MvcResult result, String name)
            throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                result.getResponse().getContentAsByteArray());
        return Long.parseLong(json.get(name).asText());
    }
}