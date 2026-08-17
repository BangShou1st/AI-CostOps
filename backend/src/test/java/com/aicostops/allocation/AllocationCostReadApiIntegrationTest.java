package com.aicostops.allocation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Cost read API: COST_READ at ORG scope, pagination, reviewStatus filter,
 * decimal-string money, detail lineage state, and privacy-preserving 404s.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=duplicate-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class AllocationCostReadApiIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/costs/charges"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingCostReadPermissionIsForbidden() throws Exception {
        revokeAllAssignments();

        mockMvc.perform(get("/api/v1/costs/charges").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void listsChargesWithStringIdsAndDecimalStringMoney() throws Exception {
        insertCharge("10.00000000");
        insertCharge("20.00000000");

        mockMvc.perform(get("/api/v1/costs/charges")
                        .header("Authorization", bearer())
                        .queryParam("page", "0").queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].providerCode").value("GLM"))
                .andExpect(jsonPath("$.items[0].chargeCategory").value("USAGE"))
                .andExpect(jsonPath("$.items[0].amount").value("20.00000000"))
                .andExpect(jsonPath("$.items[0].currency").value("CNY"))
                .andExpect(jsonPath("$.items[0].periodStart").exists())
                .andExpect(jsonPath("$.items[0].reviewStatus").value("CLEAN"))
                .andExpect(jsonPath("$.items[1].amount").value("10.00000000"))
                .andExpect(jsonPath("$.items[1].currentAllocationDecisionId").doesNotExist());
    }

    @Test
    void listFiltersByReviewStatusAndPagination() throws Exception {
        insertCharge("10.00000000");
        var suspected = insertCharge(orgId, rawRecordId, "30.00000000", "CNY",
                "SUSPECTED_DUPLICATE", JAN_1, FEB_1);

        mockMvc.perform(get("/api/v1/costs/charges")
                        .header("Authorization", bearer())
                        .queryParam("reviewStatus", "SUSPECTED_DUPLICATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(suspected)))
                .andExpect(jsonPath("$.items[0].reviewStatus").value("SUSPECTED_DUPLICATE"));

        mockMvc.perform(get("/api/v1/costs/charges")
                        .header("Authorization", bearer())
                        .queryParam("page", "1").queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listRejectsInvalidFilterAndPagination() throws Exception {
        mockMvc.perform(get("/api/v1/costs/charges")
                        .header("Authorization", bearer())
                        .queryParam("reviewStatus", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/costs/charges")
                        .header("Authorization", bearer())
                        .queryParam("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/costs/charges")
                        .header("Authorization", bearer())
                        .queryParam("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void detailExposesAllocationStateAndConfirmedLineage() throws Exception {
        var chargeId = insertCharge("10.00000000");

        mockMvc.perform(get("/api/v1/costs/charges/{chargeFactId}", chargeId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.toString(chargeId)))
                .andExpect(jsonPath("$.amount").value("10.00000000"))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.reviewStatus").value("CLEAN"))
                .andExpect(jsonPath("$.confirmedImport").value(true))
                .andExpect(jsonPath("$.duplicateOfChargeId").doesNotExist())
                .andExpect(jsonPath("$.currentAllocationDecisionId").doesNotExist());
    }

    @Test
    void detailShowsUnconfirmedImportAsNotEligibleForLineage() throws Exception {
        var unconfirmedRaw = insertUnconfirmedRawRecord(
                orgId, actorMemberId, accountId, "unconfirmed-" + System.nanoTime());
        var chargeId = insertCharge(orgId, unconfirmedRaw, "5.00000000", "CNY", "CLEAN", JAN_1, FEB_1);

        mockMvc.perform(get("/api/v1/costs/charges/{chargeFactId}", chargeId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedImport").value(false));
    }

    @Test
    void crossOrgChargeIsNotFound() throws Exception {
        var foreignUser = insertUser("alloc-foreign-" + System.nanoTime() + "@example.com");
        var foreignMember = insertMember(foreignOrgId, foreignUser);
        var foreignRaw = insertConfirmedRawRecord(
                foreignOrgId, foreignMember, insertProviderAccount(foreignOrgId, "GLM"),
                "foreign-" + System.nanoTime());
        var foreignCharge = insertCharge(foreignOrgId, foreignRaw, "7.00000000", "CNY", "CLEAN",
                JAN_1, FEB_1);

        mockMvc.perform(get("/api/v1/costs/charges/{chargeFactId}", foreignCharge)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unknownChargeIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/costs/charges/{chargeFactId}", 999999L)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
