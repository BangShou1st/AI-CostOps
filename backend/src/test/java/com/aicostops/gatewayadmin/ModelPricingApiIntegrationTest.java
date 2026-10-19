package com.aicostops.gatewayadmin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import org.assertj.core.api.Assertions;
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
        seedProviderCatalog("OTHERPC");
        long incompatibleProviderModelId = insertProviderModel(
                "OTHERPC", modelId, "cp-incompatible-" + fixtureCounter + "-" + System.nanoTime());
        mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJsonFor(incompatibleProviderModelId, providerAccountId, 10.0, 20.0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJsonFor(providerModelId, foreignProviderAccountId, 10.0, 20.0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void privateCatalogModelsAreNotVisibleAcrossOrganizations() throws Exception {
        var foreignModel = insertForeignPrivateModel();

        var catalog = mockMvc.perform(get("/api/v1/model-catalog")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(catalog)
                .doesNotContain(foreignModel.modelKey(), foreignModel.modelName());
    }

    @Test
    void privateProviderModelsAreNotVisibleAcrossOrganizations() throws Exception {
        var foreignModel = insertForeignPrivateModel();
        var providerModels = mockMvc.perform(get("/api/v1/provider-models")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(providerModels)
                .doesNotContain(foreignModel.providerModelName());
    }

    @Test
    void cannotCreatePricingForAnotherOrganizationsPrivateProviderModel() throws Exception {
        var foreignModel = insertForeignPrivateModel();
        int before = pricingVersionCount(foreignModel.providerModelId());

        mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJsonFor(
                                foreignModel.providerModelId(), providerAccountId, 10.0, 20.0)))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(pricingVersionCount(foreignModel.providerModelId()))
                .isEqualTo(before);
    }

    @Test
    void routingOptionsHideAnotherOrganizationsPrivateModel() throws Exception {
        var foreignModel = insertForeignPrivateModel();

        mockMvc.perform(get("/api/v1/routing-options")
                        .param("modelId", Long.toString(foreignModel.modelId()))
                        .header("Authorization", readerBearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void routingOptionsExposeGlobalAndCorrectlyBoundPrivateModelsOnly() throws Exception {
        var suffix = fixtureCounter + "-" + System.nanoTime();
        long ownPrivateProviderModelId = insertScopedProviderModel(
                orgId, providerAccountId, "TESTPC", modelId, "cp-own-private-route-" + suffix);
        long foreignPrivateProviderModelId = insertScopedProviderModel(
                foreignOrgId, foreignProviderAccountId, "TESTPC", modelId, "cp-foreign-private-route-" + suffix);

        var response = mockMvc.perform(get("/api/v1/routing-options")
                        .param("modelId", Long.toString(modelId))
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var options = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        var returnedProviderModelIds = new java.util.ArrayList<String>();
        for (var option : options) {
            returnedProviderModelIds.add(option.path("providerModelId").asText());
        }
        Assertions.assertThat(returnedProviderModelIds)
                .contains(Long.toString(providerModelId), Long.toString(ownPrivateProviderModelId))
                .doesNotContain(Long.toString(foreignPrivateProviderModelId));
        Assertions.assertThat(response).doesNotContain("cp-foreign-private-route-" + suffix);
    }

    @Test
    void routingPolicyAcceptsGlobalAndOwnBoundModelsButRejectsForeignPrivateModel() throws Exception {
        var suffix = fixtureCounter + "-" + System.nanoTime();
        long ownPrivateProviderModelId = insertScopedProviderModel(
                orgId, providerAccountId, "TESTPC", modelId, "cp-own-private-policy-" + suffix);
        long foreignPrivateProviderModelId = insertScopedProviderModel(
                foreignOrgId, foreignProviderAccountId, "TESTPC", modelId, "cp-foreign-private-policy-" + suffix);

        mockMvc.perform(post("/api/v1/routing-policies")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routingPolicyJson(modelId, providerAccountId, providerModelId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/routing-policies")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routingPolicyJson(modelId, providerAccountId, ownPrivateProviderModelId)))
                .andExpect(status().isCreated());

        int policiesBefore = routingPolicyCount();
        int candidatesBefore = routingCandidateCount();
        mockMvc.perform(post("/api/v1/routing-policies")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routingPolicyJson(modelId, providerAccountId, foreignPrivateProviderModelId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Assertions.assertThat(routingPolicyCount()).isEqualTo(policiesBefore);
        Assertions.assertThat(routingCandidateCount()).isEqualTo(candidatesBefore);
    }

    @Test
    void pricingCreationDoesNotRevealWhetherAnInvisibleProviderModelExists() throws Exception {
        var suffix = fixtureCounter + "-" + System.nanoTime();
        long foreignPrivateProviderModelId = insertScopedProviderModel(
                foreignOrgId, foreignProviderAccountId, "TESTPC", modelId, "cp-foreign-pricing-" + suffix);
        seedProviderCatalog("OTHERPC");
        long incompatibleGlobalProviderModelId = insertProviderModel(
                "OTHERPC", modelId, "cp-incompatible-pricing-" + suffix);
        long ownPrivateProviderModelId = insertScopedProviderModel(
                orgId, providerAccountId, "TESTPC", modelId, "cp-own-private-pricing-" + suffix);
        long nonexistentProviderModelId = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id),0)+1000 FROM provider_model", Long.class);
        Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_model WHERE id=?", Integer.class, nonexistentProviderModelId))
                .isZero();

        int foreignVersionsBefore = pricingVersionCount(foreignPrivateProviderModelId);
        int missingVersionsBefore = pricingVersionCount(nonexistentProviderModelId);
        int incompatibleVersionsBefore = pricingVersionCount(incompatibleGlobalProviderModelId);
        int legalVersionsBefore = pricingVersionCount(ownPrivateProviderModelId);
        int ratesBefore = pricingRateCount();

        var hidden = postPricing(foreignPrivateProviderModelId, providerAccountId);
        var missing = postPricing(nonexistentProviderModelId, providerAccountId);
        var incompatible = postPricing(incompatibleGlobalProviderModelId, providerAccountId);
        var compatible = postPricing(ownPrivateProviderModelId, providerAccountId);

        var hiddenJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                hidden.getResponse().getContentAsByteArray());
        var missingJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                missing.getResponse().getContentAsByteArray());
        var incompatibleJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                incompatible.getResponse().getContentAsByteArray());
        var compatibleJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                compatible.getResponse().getContentAsByteArray());

        Assertions.assertThat(List.of(hidden.getResponse().getStatus(), missing.getResponse().getStatus(),
                incompatible.getResponse().getStatus(), compatible.getResponse().getStatus()))
                .as("foreign private, nonexistent, visible incompatible, compatible pricing outcomes")
                .containsExactly(404, 404, 400, 201);
        Assertions.assertThat(hiddenJson.path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        Assertions.assertThat(missingJson.path("code").asText()).isEqualTo(hiddenJson.path("code").asText());
        Assertions.assertThat(missingJson.path("title").asText()).isEqualTo(hiddenJson.path("title").asText());
        Assertions.assertThat(missingJson.path("detail").asText()).isEqualTo(hiddenJson.path("detail").asText());
        Assertions.assertThat(incompatibleJson.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        Assertions.assertThat(compatibleJson.path("status").asText()).isEqualTo("DRAFT");

        Assertions.assertThat(pricingVersionCount(foreignPrivateProviderModelId)).isEqualTo(foreignVersionsBefore);
        Assertions.assertThat(pricingVersionCount(nonexistentProviderModelId)).isEqualTo(missingVersionsBefore);
        Assertions.assertThat(pricingVersionCount(incompatibleGlobalProviderModelId))
                .isEqualTo(incompatibleVersionsBefore);
        Assertions.assertThat(pricingVersionCount(ownPrivateProviderModelId)).isEqualTo(legalVersionsBefore + 1);
        Assertions.assertThat(pricingRateCount()).isEqualTo(ratesBefore + 2);
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

    private ForeignPrivateModel insertForeignPrivateModel() {
        var suffix = fixtureCounter + "-" + System.nanoTime();
        var modelKey = "cp-private-" + suffix;
        var modelName = "CP Private Model " + suffix;
        var providerModelName = "cp-private-provider-model-" + suffix;
        var accountId = insertProviderAccount(
                foreignOrgId, "TESTPC", "cp-private-account-" + suffix);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,owner_org_id,status,capabilities_json,
                    default_max_output_tokens,max_output_tokens,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),8192,131072,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, modelKey, modelName, foreignOrgId);
        long privateModelId = jdbc.queryForObject(
                "SELECT id FROM model_catalog WHERE model_key=?", Long.class, modelKey);
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                    routing_eligible,capabilities_json,owner_org_id,provider_account_id,created_at,updated_at)
                VALUES ('TESTPC',?,?,'ACTIVE',TRUE,JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),
                    ?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, privateModelId, providerModelName, foreignOrgId, accountId);
        long privateProviderModelId = jdbc.queryForObject(
                "SELECT id FROM provider_model WHERE provider_model_name=?", Long.class, providerModelName);
        return new ForeignPrivateModel(modelKey, modelName, providerModelName,
                privateModelId, privateProviderModelId);
    }

    private int pricingVersionCount(long providerModelId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pricing_version WHERE org_id=? AND provider_model_id=?",
                Integer.class, orgId, providerModelId);
    }

    private int pricingRateCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM pricing_rate pr
                JOIN pricing_version pv ON pv.id=pr.pricing_version_id AND pv.org_id=pr.org_id
                WHERE pv.org_id=?
                """, Integer.class, orgId);
    }

    private int routingPolicyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM routing_policy WHERE org_id=?",
                Integer.class, orgId);
    }

    private int routingCandidateCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM routing_policy_candidate WHERE org_id=?",
                Integer.class, orgId);
    }

    private long insertScopedProviderModel(long ownerOrgId, long accountId, String providerCode,
            long logicalModelId, String name) {
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                    routing_eligible,capabilities_json,owner_org_id,provider_account_id,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),
                    ?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, logicalModelId, name, ownerOrgId, accountId);
        return jdbc.queryForObject("SELECT id FROM provider_model WHERE provider_model_name=?",
                Long.class, name);
    }

    private String routingPolicyJson(long logicalModelId, long accountId, long routeProviderModelId) {
        return """
                {"modelId":%d,"candidates":[{"providerAccountId":%d,"providerModelId":%d,
                  "priority":0,"status":"ACTIVE"}]}
                """.formatted(logicalModelId, accountId, routeProviderModelId);
    }

    private org.springframework.test.web.servlet.MvcResult postPricing(long routeProviderModelId,
            long accountId) throws Exception {
        return mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pricingJsonFor(routeProviderModelId, accountId, 11.0, 22.0)))
                .andReturn();
    }

    private record ForeignPrivateModel(String modelKey, String modelName, String providerModelName,
            long modelId, long providerModelId) {
    }

    private static long readLong(org.springframework.test.web.servlet.MvcResult result, String name)
            throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                result.getResponse().getContentAsByteArray());
        return Long.parseLong(json.get(name).asText());
    }
}
