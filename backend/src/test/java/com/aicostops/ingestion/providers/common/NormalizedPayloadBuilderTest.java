package com.aicostops.ingestion.providers.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NormalizedPayloadBuilderTest {

    @Test
    void sourceSchemaAndRecordKindAreAlwaysPresent() {
        var payload = new NormalizedPayloadBuilder("mimo.usage-workbook.v1", "USAGE").build();

        assertThat(payload).containsEntry("sourceSchema", "mimo.usage-workbook.v1")
                .containsEntry("recordKind", "USAGE");
    }

    @Test
    void emptySectionsAreOmitted() {
        var payload = new NormalizedPayloadBuilder("kimi.billing-summary-workbook.v1", "BILLING_SUMMARY").build();

        assertThat(payload).doesNotContainKeys("dimensions", "usage", "money", "providerFields");
    }

    @Test
    void nullAndBlankValuesAreOmitted() {
        var payload = new NormalizedPayloadBuilder("deepseek.usage-zip.v1", "COST")
                .dimension("model", null)
                .dimension("providerUser", "   ")
                .providerField("walletType", "prepaid")
                .usage("tokens", null)
                .build();

        assertThat(payload).doesNotContainKeys("dimensions", "usage");
        assertThat(payload.get("providerFields")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsOnlyKeys("walletType");
    }

    @Test
    void zeroValuesAreRetained() {
        var payload = new NormalizedPayloadBuilder("glm.monthly-billing-summary-workbook.v1", "BILLING_SUMMARY")
                .moneyComponent("consumptionAmount", BigDecimal.ZERO)
                .build();

        assertThat(payload.get("money")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsKey("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> components =
                (Map<String, Object>) ((Map<?, ?>) payload.get("money")).get("components");
        assertThat(components).containsEntry("consumptionAmount", BigDecimal.ZERO);
    }

    @Test
    void fullShapeNestsDeterministically() {
        var payload = new NormalizedPayloadBuilder("openai.organization-usage-completions-json.v1", "USAGE")
                .dimension("model", "gpt-example")
                .dimension("providerUser", "user_fake")
                .usage("inputTokens", 100L)
                .money("currency", "usd")
                .money("reportedAmount", new BigDecimal("1.23"))
                .moneyComponent("breakdown", new BigDecimal("0.40"))
                .providerField("batch", false)
                .build();

        assertThat(payload.keySet()).containsExactly("sourceSchema", "recordKind",
                "dimensions", "usage", "money", "providerFields");
        assertThat(payload.get("dimensions")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("model", "gpt-example").containsEntry("providerUser", "user_fake");
        assertThat(payload.get("money")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("currency", "usd")
                .containsEntry("reportedAmount", new BigDecimal("1.23"));
        @SuppressWarnings("unchecked")
        Map<String, Object> components =
                (Map<String, Object>) ((Map<?, ?>) payload.get("money")).get("components");
        assertThat(components).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("breakdown", new BigDecimal("0.40"));
        assertThat(payload.get("providerFields")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("batch", false);
    }

    @Test
    void repeatedKeysDoNotDuplicate() {
        var payload = new NormalizedPayloadBuilder("deepseek.usage-zip.v1", "USAGE")
                .dimension("model", "a")
                .dimension("model", "b")
                .build();

        assertThat(payload.get("dimensions")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsExactlyEntriesOf(Map.of("model", "b"));
    }
}
