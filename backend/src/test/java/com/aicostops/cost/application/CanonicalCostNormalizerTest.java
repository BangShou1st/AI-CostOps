package com.aicostops.cost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.cost.domain.AttributionHint;
import com.aicostops.cost.domain.CanonicalFactBatch;
import com.aicostops.cost.domain.ChargeFact;
import com.aicostops.cost.domain.ConsumptionFact;
import com.aicostops.cost.domain.DocumentType;
import com.aicostops.cost.domain.ExternalDocument;
import com.aicostops.cost.domain.HintType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Canonical mapping matrix (§4): every (sourceSchema, recordKind) whitelist entry,
 * whitelist non-escalation, fail-closed behavior, and zero-fact empty batches.
 */
class CanonicalCostNormalizerTest {

    private final CanonicalCostNormalizer normalizer =
            new CanonicalCostNormalizer(new ObjectMapper());

    private static final Instant USAGE_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant USAGE_END = Instant.parse("2026-01-02T00:00:00Z");

    private CanonicalFactBatch normalize(String providerCode, String payloadJson) {
        var input = new CanonicalizationInput(42L, providerCode, 7L, 99L, 0L,
                "data[0].results[0]", payloadJson, USAGE_START, USAGE_END);
        return normalizer.normalize(input);
    }

    // ------------------------------------------------------------------
    // OpenAI usage
    // ------------------------------------------------------------------

    @Test
    void openAiUsageMapsRequiredMetersWithIndependentFactIndexes() {
        var batch = normalize("OPENAI", """
                {"sourceSchema":"openai.organization-usage-completions-json.v1","recordKind":"USAGE",
                 "dimensions":{"model":"gpt-fake","providerUser":"user_x","providerProject":"proj_y","credentialId":"key_123"},
                 "usage":{"inputTokens":100,"outputTokens":50,"numModelRequests":3}}
                """);

        assertThat(batch.documents()).isEmpty();
        assertThat(batch.pricings()).isEmpty();
        assertThat(batch.charges()).isEmpty();
        assertThat(batch.consumptions()).hasSize(3);
        assertThat(batch.consumptions()).extracting(ConsumptionFact::meterCode)
                .containsExactly("input_tokens", "output_tokens", "num_model_requests");
        assertThat(batch.consumptions()).extracting(ConsumptionFact::unit)
                .containsExactly("tokens", "tokens", "requests");
        assertThat(batch.consumptions()).extracting(ConsumptionFact::factIndex)
                .containsExactly(0, 1, 2);
        assertThat(batch.consumptions()).extracting(ConsumptionFact::quantity)
                .allSatisfy(q -> assertThat(q).isNotNull());
        assertThat(batch.consumptions()).allSatisfy(c -> {
            assertThat(c.orgId()).isEqualTo(42L);
            assertThat(c.rawRecordId()).isEqualTo(99L);
            assertThat(c.providerCode()).isEqualTo("OPENAI");
            assertThat(c.model()).isEqualTo("gpt-fake");
            assertThat(c.usageStart()).isEqualTo(USAGE_START);
            assertThat(c.usageEnd()).isEqualTo(USAGE_END);
        });
    }

    @Test
    void openAiUsageUpgradesWhitelistedBreakdownMetersButNeverUnknownKeys() {
        var batch = normalize("OPENAI", """
                {"sourceSchema":"openai.organization-usage-completions-json.v1","recordKind":"USAGE",
                 "dimensions":{"providerUser":"user_x"},
                 "usage":{"inputTokens":100,"outputTokens":50,"numModelRequests":3,
                          "inputCachedTokens":10,"outputAudioTokens":20,"futureMetric":999}}
                """);

        assertThat(batch.consumptions()).hasSize(5);
        assertThat(batch.consumptions()).extracting(ConsumptionFact::meterCode)
                .containsExactly("input_tokens", "output_tokens", "num_model_requests",
                        "input_cached_tokens", "output_audio_tokens");
        assertThat(batch.consumptions()).extracting(ConsumptionFact::quantity)
                .extracting(q -> q.toPlainString())
                .containsExactly("100", "50", "3", "10", "20");
    }

    @Test
    void openAiUsageEmitsThreeProviderNativeHintClasses() {
        var batch = normalize("OPENAI", """
                {"sourceSchema":"openai.organization-usage-completions-json.v1","recordKind":"USAGE",
                 "dimensions":{"model":"gpt-fake","providerUser":"user_x","providerProject":"proj_y","credentialId":"key_123"},
                 "usage":{"inputTokens":100,"outputTokens":50,"numModelRequests":3}}
                """);

        assertThat(batch.hints()).hasSize(3);
        assertThat(batch.hints()).extracting(AttributionHint::hintType)
                .containsExactly(HintType.PROVIDER_USER, HintType.PROVIDER_PROJECT, HintType.PROVIDER_API_KEY);
        assertThat(batch.hints()).extracting(AttributionHint::providerValue)
                .containsExactly("user_x", "proj_y", "key_123");
        assertThat(batch.hints()).allSatisfy(h -> {
            assertThat(h.confidence()).isNull();
            assertThat(h.candidateScopeType()).isNull();
            assertThat(h.candidateScopeId()).isNull();
            assertThat(h.factIndex()).isGreaterThanOrEqualTo(0);
        });
        assertThat(batch.consumptions().get(0).providerUserRef()).isEqualTo("user_x");
        assertThat(batch.consumptions().get(0).providerProjectRef()).isEqualTo("proj_y");
        assertThat(batch.consumptions().get(0).providerApiKeyLabel()).isEqualTo("key_123");
        assertThat(batch.consumptions().get(0).providerApiKeyHash()).isNull();
    }

    @Test
    void openAiUsageMaskedCredentialNeverBecomesApiKeyHintOrLabel() {
        var batch = normalize("OPENAI", """
                {"sourceSchema":"openai.organization-usage-completions-json.v1","recordKind":"USAGE",
                 "dimensions":{"providerUser":"user_x","credentialId":"********"},
                 "usage":{"inputTokens":100,"outputTokens":50,"numModelRequests":3}}
                """);

        assertThat(batch.hints()).extracting(AttributionHint::hintType)
                .containsExactly(HintType.PROVIDER_USER);
        assertThat(batch.consumptions().get(0).providerApiKeyLabel()).isNull();
    }

    // ------------------------------------------------------------------
    // OpenAI costs
    // ------------------------------------------------------------------

    @Test
    void openAiCostsMapsSingleChargeWithProviderFieldMetadata() {
        var batch = normalize("OPENAI", """
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"COST",
                 "dimensions":{"providerProject":"proj_y","credentialId":"key_123"},
                 "providerFields":{"lineItem":"Chat","quantity":1234},
                 "money":{"currency":"USD","reportedAmount":12.34}}
                """);

        assertThat(batch.consumptions()).isEmpty();
        assertThat(batch.documents()).isEmpty();
        assertThat(batch.charges()).hasSize(1);
        var charge = batch.charges().get(0);
        assertThat(charge.factIndex()).isZero();
        assertThat(charge.providerCode()).isEqualTo("OPENAI");
        assertThat(charge.chargeCategory().name()).isEqualTo("USAGE");
        assertThat(charge.amount()).isEqualByComparingTo("12.34");
        assertThat(charge.currency()).isEqualTo("USD");
        assertThat(charge.periodStart()).isEqualTo(USAGE_START);
        assertThat(charge.periodEnd()).isEqualTo(USAGE_END);
        assertThat(charge.reviewStatus().name()).isEqualTo("CLEAN");
        assertThat(charge.metadata()).containsEntry("lineItem", "Chat")
                .containsEntry("quantity", "1234");
    }

    @Test
    void openAiCostsNeverEmitsProviderUserHint() {
        var batch = normalize("OPENAI", """
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"COST",
                 "dimensions":{"providerProject":"proj_y","credentialId":"key_123"},
                 "money":{"currency":"USD","reportedAmount":12.34}}
                """);

        assertThat(batch.hints()).hasSize(2);
        assertThat(batch.hints()).extracting(AttributionHint::hintType)
                .containsExactly(HintType.PROVIDER_PROJECT, HintType.PROVIDER_API_KEY);
    }

    // ------------------------------------------------------------------
    // OpenAI observed empty export
    // ------------------------------------------------------------------

    @Test
    void openAiEmptyExportReturnsExplicitEmptyBatch() {
        var usage = normalize("OPENAI", """
                {"sourceSchema":"openai.observed-empty-export.v1","recordKind":"EMPTY_USAGE_BUCKET",
                 "providerFields":{"exportKind":"USAGE"}}
                """);
        var cost = normalize("OPENAI", """
                {"sourceSchema":"openai.observed-empty-export.v1","recordKind":"EMPTY_COST_BUCKET",
                 "providerFields":{"exportKind":"COST"}}
                """);

        assertThat(usage.documents()).isEmpty();
        assertThat(usage.consumptions()).isEmpty();
        assertThat(usage.pricings()).isEmpty();
        assertThat(usage.charges()).isEmpty();
        assertThat(usage.hints()).isEmpty();
        assertThat(cost.documents()).isEmpty();
        assertThat(cost.consumptions()).isEmpty();
        assertThat(cost.pricings()).isEmpty();
        assertThat(cost.charges()).isEmpty();
        assertThat(cost.hints()).isEmpty();
    }

    // ------------------------------------------------------------------
    // MiMo
    // ------------------------------------------------------------------

    @Test
    void mimoModelSheetMapsFiveMetersAndChargeWithComponentsMetadata() {
        var batch = normalize("MIMO", """
                {"sourceSchema":"mimo.usage-workbook.v1","recordKind":"USAGE",
                 "dimensions":{"model":"mimo-model","credentialHint":"********"},
                 "providerFields":{"date":"2026-01-01"},
                 "usage":{"totalTokens":1000,"inputHitTokens":600,"inputMissTokens":400,"outputTokens":200,
                          "requestCount":5,"totalAudioDurationRaw":"0:10:00"},
                 "money":{"currency":"CNY","reportedAmount":9.99,
                          "components":{"inputHitAmount":1.1,"inputMissAmount":2.2,"outputAmount":3.3}}}
                """);

        assertThat(batch.consumptions()).hasSize(5);
        assertThat(batch.consumptions()).extracting(ConsumptionFact::meterCode)
                .containsExactly("total_tokens", "input_hit_tokens", "input_miss_tokens",
                        "output_tokens", "request_count");
        assertThat(batch.consumptions()).extracting(ConsumptionFact::unit)
                .containsExactly("tokens", "tokens", "tokens", "tokens", "requests");
        assertThat(batch.consumptions()).extracting(ConsumptionFact::factIndex)
                .containsExactly(0, 1, 2, 3, 4);
        assertThat(batch.hints()).isEmpty();
        assertThat(batch.charges()).hasSize(1);
        assertThat(batch.charges().get(0).amount()).isEqualByComparingTo("9.99");
        assertThat(batch.charges().get(0).currency()).isEqualTo("CNY");
        assertThat(batch.charges().get(0).metadata()).containsEntry("inputHitAmount", "1.1")
                .containsEntry("inputMissAmount", "2.2")
                .containsEntry("outputAmount", "3.3")
                .containsEntry("date", "2026-01-01")
                .containsEntry("totalAudioDurationRaw", "0:10:00");
    }

    @Test
    void mimoPluginSheetMapsRequestCountWithProviderPluginAsServiceCode() {
        var batch = normalize("MIMO", """
                {"sourceSchema":"mimo.usage-workbook.v1","recordKind":"PLUGIN_USAGE",
                 "dimensions":{"credentialHint":"********"},
                 "providerFields":{"plugin":"web-search","date":"2026-01-01"},
                 "usage":{"requestCount":7},
                 "money":{"currency":"CNY","reportedAmount":1.5}}
                """);

        assertThat(batch.consumptions()).hasSize(1);
        var consumption = batch.consumptions().get(0);
        assertThat(consumption.meterCode()).isEqualTo("request_count");
        assertThat(consumption.unit()).isEqualTo("requests");
        assertThat(consumption.serviceCode()).isEqualTo("web-search");
        assertThat(consumption.quantity()).isEqualByComparingTo("7");
        assertThat(batch.charges()).hasSize(1);
        assertThat(batch.charges().get(0).amount()).isEqualByComparingTo("1.5");
        assertThat(batch.charges().get(0).metadata()).containsEntry("date", "2026-01-01");
        assertThat(batch.hints()).isEmpty();
    }

    @Test
    void mimoPluginWithoutProviderPluginLeavesServiceCodeNull() {
        var batch = normalize("MIMO", """
                {"sourceSchema":"mimo.usage-workbook.v1","recordKind":"PLUGIN_USAGE",
                 "dimensions":{"credentialHint":"********"},
                 "providerFields":{"date":"2026-01-01"},
                 "usage":{"requestCount":7},
                 "money":{"currency":"CNY","reportedAmount":1.5}}
                """);

        assertThat(batch.consumptions().get(0).serviceCode()).isNull();
    }

    // ------------------------------------------------------------------
    // GLM
    // ------------------------------------------------------------------

    @Test
    void glmBillingSummaryMapsDirectSemanticCopiesWithNullCurrency() {
        var batch = normalize("GLM", """
                {"sourceSchema":"glm.monthly-billing-summary-workbook.v1","recordKind":"BILLING_SUMMARY",
                 "providerFields":{"billingMonth":"2026-01","settlementStatus":"SETTLED"},
                 "money":{"components":{"catalogAmount":100.0,"consumptionAmount":90.0,
                          "creditPaymentAmount":5.0,"promotionalDeductionAmount":5.0,
                          "payableAmount":80.0,"paidAmount":80.0,"outstandingAmount":0.0}}}
                """);

        assertThat(batch.documents()).hasSize(1);
        var document = batch.documents().get(0);
        assertThat(document.documentType()).isEqualTo(DocumentType.BILL_SUMMARY);
        assertThat(document.currency()).isNull();
        assertThat(document.periodStart()).isNull();
        assertThat(document.periodEnd()).isNull();
        assertThat(document.reportedTotalAmount()).isEqualByComparingTo("90.0");
        assertThat(document.reportedPayableAmount()).isEqualByComparingTo("80.0");
        assertThat(document.reportedPaidAmount()).isEqualByComparingTo("80.0");
        assertThat(document.reportedOutstandingAmount()).isEqualByComparingTo("0.0");
        assertThat(document.metadata()).containsEntry("catalogAmount", "100.0")
                .containsEntry("creditPaymentAmount", "5.0")
                .containsEntry("promotionalDeductionAmount", "5.0")
                .containsEntry("billingMonth", "2026-01")
                .containsEntry("settlementStatus", "SETTLED")
                .doesNotContainKeys("consumptionAmount", "payableAmount", "paidAmount", "outstandingAmount");
        assertThat(batch.consumptions()).isEmpty();
        assertThat(batch.charges()).isEmpty();
        assertThat(batch.hints()).isEmpty();
    }

    @Test
    void glmMissingComponentsStayNullOnReportedColumns() {
        var batch = normalize("GLM", """
                {"sourceSchema":"glm.monthly-billing-summary-workbook.v1","recordKind":"BILLING_SUMMARY",
                 "providerFields":{"billingMonth":"2026-01"},
                 "money":{"components":{"consumptionAmount":90.0}}}
                """);

        var document = batch.documents().get(0);
        assertThat(document.reportedTotalAmount()).isEqualByComparingTo("90.0");
        assertThat(document.reportedPayableAmount()).isNull();
        assertThat(document.reportedPaidAmount()).isNull();
        assertThat(document.reportedOutstandingAmount()).isNull();
    }

    // ------------------------------------------------------------------
    // Kimi
    // ------------------------------------------------------------------

    @Test
    void kimiBillingSummaryNeverSumsComponentsAndNeverMapsProject() {
        var batch = normalize("KIMI", """
                {"sourceSchema":"kimi.billing-summary-workbook.v1","recordKind":"BILLING_SUMMARY",
                 "dimensions":{"providerUser":"kimi-user","providerOrganization":"kimi-org"},
                 "providerFields":{"billingEntity":"Entity","periodText":"2026-01"},
                 "money":{"currency":"CNY",
                          "components":{"paidBalanceConsumption":1.0,"promotionalBalanceConsumption":2.0}}}
                """);

        assertThat(batch.documents()).hasSize(1);
        var document = batch.documents().get(0);
        assertThat(document.documentType()).isEqualTo(DocumentType.BILL_SUMMARY);
        assertThat(document.currency()).isEqualTo("CNY");
        assertThat(document.reportedTotalAmount()).isNull();
        assertThat(document.reportedPayableAmount()).isNull();
        assertThat(document.reportedPaidAmount()).isNull();
        assertThat(document.reportedOutstandingAmount()).isNull();
        assertThat(document.metadata()).containsEntry("paidBalanceConsumption", "1.0")
                .containsEntry("promotionalBalanceConsumption", "2.0")
                .containsEntry("billingEntity", "Entity")
                .containsEntry("periodText", "2026-01")
                .containsEntry("providerOrganization", "kimi-org");
        assertThat(batch.hints()).hasSize(1);
        assertThat(batch.hints().get(0).hintType()).isEqualTo(HintType.PROVIDER_USER);
        assertThat(batch.hints().get(0).providerValue()).isEqualTo("kimi-user");
        assertThat(batch.hints()).extracting(AttributionHint::hintType)
                .doesNotContain(HintType.PROVIDER_PROJECT);
        assertThat(batch.charges()).isEmpty();
        assertThat(batch.consumptions()).isEmpty();
    }

    // ------------------------------------------------------------------
    // DeepSeek
    // ------------------------------------------------------------------

    @Test
    void deepSeekAmountCsvNeverUpgradesPriceOrAmount() {
        var batch = normalize("DEEPSEEK", """
                {"sourceSchema":"deepseek.usage-zip.v1","recordKind":"USAGE",
                 "dimensions":{"model":"deepseek-chat","providerUser":"ds-user"},
                 "providerFields":{"credentialLabel":"my-key","type":"prepaid","price":"0.001","amount":"0"}}
                """);

        assertThat(batch.consumptions()).isEmpty();
        assertThat(batch.charges()).isEmpty();
        assertThat(batch.documents()).isEmpty();
        assertThat(batch.hints()).hasSize(2);
        assertThat(batch.hints()).extracting(AttributionHint::hintType)
                .containsExactly(HintType.PROVIDER_USER, HintType.PROVIDER_API_KEY);
        assertThat(batch.hints()).extracting(AttributionHint::providerValue)
                .containsExactly("ds-user", "my-key");
    }

    @Test
    void deepSeekAmountMaskedCredentialLabelNeverBecomesApiKeyHint() {
        var batch = normalize("DEEPSEEK", """
                {"sourceSchema":"deepseek.usage-zip.v1","recordKind":"USAGE",
                 "dimensions":{"providerUser":"ds-user"},
                 "providerFields":{"credentialLabel":"********"}}
                """);

        assertThat(batch.hints()).extracting(AttributionHint::hintType)
                .containsExactly(HintType.PROVIDER_USER);
    }

    @Test
    void deepSeekCostCsvMetadataIsExactlyModelAndWalletType() {
        var batch = normalize("DEEPSEEK", """
                {"sourceSchema":"deepseek.usage-zip.v1","recordKind":"COST",
                 "dimensions":{"model":"deepseek-chat","providerUser":"ds-user"},
                 "providerFields":{"walletType":"prepaid"},
                 "money":{"currency":"CNY","reportedAmount":0.123456}}
                """);

        assertThat(batch.charges()).hasSize(1);
        var charge = batch.charges().get(0);
        assertThat(charge.amount()).isEqualByComparingTo("0.123456");
        assertThat(charge.currency()).isEqualTo("CNY");
        assertThat(charge.metadata()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("model", "deepseek-chat", "walletType", "prepaid"));
        assertThat(batch.hints()).hasSize(1);
        assertThat(batch.hints().get(0).hintType()).isEqualTo(HintType.PROVIDER_USER);
        assertThat(batch.consumptions()).isEmpty();
        assertThat(batch.documents()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Fail closed
    // ------------------------------------------------------------------

    @Test
    void unknownSourceSchemaFailsClosed() {
        assertThatThrownBy(() -> normalize("OPENAI", """
                {"sourceSchema":"provider.future-schema.v1","recordKind":"USAGE",
                 "usage":{"inputTokens":1}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported canonicalization pair")
                .hasMessageContaining("provider.future-schema.v1");
    }

    @Test
    void knownSchemaWithUnknownRecordKindFailsClosed() {
        assertThatThrownBy(() -> normalize("OPENAI", """
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"REFUND",
                 "money":{"currency":"USD","reportedAmount":1.0}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported canonicalization pair")
                .hasMessageContaining("REFUND");
    }

    @Test
    void requiredUsageFieldMissingFailsClosed() {
        assertThatThrownBy(() -> normalize("OPENAI", """
                {"sourceSchema":"openai.organization-usage-completions-json.v1","recordKind":"USAGE",
                 "dimensions":{"providerUser":"user_x"},
                 "usage":{"inputTokens":100,"outputTokens":50}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("numModelRequests");
    }

    @Test
    void chargeWithoutReportedAmountFailsClosed() {
        assertThatThrownBy(() -> normalize("OPENAI", """
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"COST",
                 "dimensions":{"providerProject":"proj_y"},
                 "money":{"currency":"USD"}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reportedAmount");
    }
}
