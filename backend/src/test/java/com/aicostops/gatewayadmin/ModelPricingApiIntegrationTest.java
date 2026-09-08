package com.aicostops.gatewayadmin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Governed Model/Pricing setup surface: catalog reads, decimal money, version lineage. */
@SpringBootTest
@Tag("integration")
@AutoConfigureMockMvc
class ModelPricingApiIntegrationTest extends ControlPlaneApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void readerListsActiveModelsAndProviderModels() throws Exception {
        mockMvc.perform(get("/api/v1/model-catalog")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(Long.toString(modelId)))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/provider-models")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(Long.toString(providerModelId)))
                .andExpect(jsonPath("$[0].routingEligible").value(true));
    }

    @Test
    void createKeepsDecimalMoneyExactAndIncrementsVersionLineage() throws Exception {
        var create = mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJson(30.00000000, 60.0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.rates[0].dimensionCode").value("INPUT_TOKEN"))
                .andExpect(jsonPath("$.rates[0].unitPrice").value(30.0))
                .andReturn();

        long versionId = readLong(create, "id");
        var rateRow = jdbc.queryForMap(
                "SELECT unit_price FROM pricing_rate WHERE pricing_version_id=? AND dimension_code='INPUT_TOKEN'",
                versionId);
        org.assertj.core.api.Assertions.assertThat(
                rateRow.get("unit_price").toString()).isEqualTo("30.00000000");

        // Second creation for the same scope gets the next version number.
        mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJson(31.0, 62.0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void incompatibleProviderModelIsRejectedAndForeignAccountIsInvisible() throws Exception {
        long otherModelId = insertModelCatalog("cp-other-" + fixtureCounter);
        mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJsonFor(otherModelId, providerAccountId, 10.0, 20.0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJsonFor(providerModelId, foreignProviderAccountId, 10.0, 20.0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void activationPromotesDraftRetiresPriorActiveVersionsAndConflictsOnRepeat() throws Exception {
        var first = mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJson(30.0, 60.0)))
                .andExpect(status().isCreated())
                .andReturn();
        long firstId = readLong(first, "id");
        mockMvc.perform(post("/api/v1/pricing-versions/" + firstId + "/activate")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.activatedAt").isString());

        var second = mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJson(31.0, 62.0)))
                .andExpect(status().isCreated())
                .andReturn();
        long secondId = readLong(second, "id");
        mockMvc.perform(post("/api/v1/pricing-versions/" + secondId + "/activate")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Version lineage is preserved: only the newest is ACTIVE for the scope.
        mockMvc.perform(get("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].status").value("RETIRED"))
                .andExpect(jsonPath("$[1].version").value(1));

        mockMvc.perform(post("/api/v1/pricing-versions/" + secondId + "/activate")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void plainMemberCannotReadOrMutatePricing() throws Exception {
        mockMvc.perform(get("/api/v1/model-catalog")
                        .header("Authorization", plainBearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", plainBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJson(10.0, 20.0)))
                .andExpect(status().isForbidden());
    }

    @Test
    void pricingLifecycleIsAudited() throws Exception {
        var create = mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJson(1.0, 2.0)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = readLong(create, "id");
        mockMvc.perform(post("/api/v1/pricing-versions/" + id + "/activate")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("PRICING_VERSION_ACTIVATED"))
                .andExpect(jsonPath("$.items[1].eventType").value("PRICING_VERSION_CREATED"));
    }

    @Test
    void moneyRemainsBigDecimalCompatibleAtRest() throws Exception {
        var create = mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJson(0.0001, 12345.67890000)))
                .andExpect(status().isCreated())
                .andReturn();
        long versionId = readLong(create, "id");
        var rows = jdbc.queryForList(
                "SELECT unit_price FROM pricing_rate WHERE pricing_version_id=? ORDER BY dimension_code",
                versionId);
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(2);
        BigDecimal input = new BigDecimal(((java.util.Map<?, ?>) rows.get(0)).get("unit_price").toString());
        BigDecimal output = new BigDecimal(((java.util.Map<?, ?>) rows.get(1)).get("unit_price").toString());
        org.assertj.core.api.Assertions.assertThat(input).isEqualByComparingTo("0.0001");
        org.assertj.core.api.Assertions.assertThat(output).isEqualByComparingTo("12345.6789");
    }

    private String pricingJson(double input, double output) {
        return pricingJsonFor(providerModelId, providerAccountId, input, output);
    }

    private String pricingJsonFor(long providerModel, long account, double input, double output) {
        return """
                {
                  "providerAccountId":%d,
                  "providerModelId":%d,
                  "currency":"USD",
                  "effectiveFrom":"2026-09-08T00:00:00Z",
                  "rates":[
                    {"dimensionCode":"INPUT_TOKEN","unitQuantity":1000000,"unitPrice":%s},
                    {"dimensionCode":"OUTPUT_TOKEN","unitQuantity":1000000,"unitPrice":%s}
                  ]
                }
                """.formatted(account, providerModel, new BigDecimal(Double.toString(input)).toPlainString(),
                new BigDecimal(Double.toString(output)).toPlainString());
    }

    private static long readLong(org.springframework.test.web.servlet.MvcResult result, String name)
            throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                result.getResponse().getContentAsByteArray());
        return Long.parseLong(json.get(name).asText());
    }
}