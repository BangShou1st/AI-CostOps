package com.aicostops.routing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.gatewayadmin.ControlPlaneApiTestSupport;
import java.util.HashMap;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Routing policy API surface: the list page keeps the candidates of every policy. */
@SpringBootTest
@Tag("integration")
@AutoConfigureMockMvc
class RoutingPolicyApiIntegrationTest extends ControlPlaneApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsCandidatesForEveryPolicyOnThePage() throws Exception {
        var first = mockMvc.perform(post("/api/v1/routing-policies")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routingPolicyJson()))
                .andExpect(status().isCreated())
                .andReturn();
        long firstId = readLong(first, "id");
        var second = mockMvc.perform(post("/api/v1/routing-policies")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routingPolicyJson()))
                .andExpect(status().isCreated())
                .andReturn();
        long secondId = readLong(second, "id");

        var response = mockMvc.perform(get("/api/v1/routing-policies")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var items = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("items");
        var candidatesByPolicy = new HashMap<Long, com.fasterxml.jackson.databind.JsonNode>();
        for (var item : items) {
            candidatesByPolicy.put(item.path("id").asLong(), item.path("candidates"));
        }

        Assertions.assertThat(candidatesByPolicy).containsKeys(firstId, secondId);
        for (long policyId : List.of(firstId, secondId)) {
            var candidates = candidatesByPolicy.get(policyId);
            Assertions.assertThat(candidates).hasSize(1);
            Assertions.assertThat(candidates.get(0).path("providerModelId").asText())
                    .isEqualTo(Long.toString(providerModelId));
            Assertions.assertThat(candidates.get(0).path("priority").asInt()).isZero();
        }
    }

    private String routingPolicyJson() {
        return """
                {"modelId":%d,"candidates":[{"providerAccountId":%d,"providerModelId":%d,
                  "priority":0,"status":"ACTIVE"}]}
                """.formatted(modelId, providerAccountId, providerModelId);
    }

    private static long readLong(org.springframework.test.web.servlet.MvcResult result, String name)
            throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                result.getResponse().getContentAsByteArray());
        return Long.parseLong(json.get(name).asText());
    }
}
