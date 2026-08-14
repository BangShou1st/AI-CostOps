package com.aicostops.ingestion.providers.mimo;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ingestion.application.InspectionResult;
import com.aicostops.ingestion.application.NormalizedProviderRecord;
import com.aicostops.ingestion.application.ProviderInput;
import com.aicostops.ingestion.application.ProviderRecordSink;
import com.aicostops.ingestion.application.ProviderSource;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.ingestion.providers.common.XlsxStreamingReader;
import com.aicostops.ingestion.providers.fixtures.ProviderFixtureFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MimoProviderAdapterTest {

    private static final List<String> MODEL_HEADER = List.of(
            "Date", "Model", "API Key", "Currency", "Consumed Amount",
            "Input Hit Amount", "Input Miss Amount", "Output Amount", "Total Tokens",
            "Input Hit Tokens", "Input Miss Tokens", "Output Tokens",
            "Total audio duration", "Request Count");
    private static final List<String> PLUGIN_HEADER = List.of(
            "Date", "Plugin", "API Key", "Currency", "Consumed Amount", "Request Count");

    private final MimoProviderAdapter adapter = new MimoProviderAdapter(new XlsxStreamingReader());

    // ------------------------------------------------------------------
    // inspection
    // ------------------------------------------------------------------

    @Test
    void populatedModelUsageShapeIsCompatible() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "key-1", "CNY", "1.234",
                        "0.5", "0.4", "0.334", "1000", "400", "300", "300", "3600", "10")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isTrue();
        assertThat(result.detectedProviderCode()).isEqualTo("MIMO");
        assertThat(result.schemaVariant()).isEqualTo("mimo.usage-workbook.v1");
        assertThat(result.schemaFingerprint()).hasSize(64);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void presentButEmptyPluginSheetIsCompatibleWithExactlyOneWarn() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "key-1", "CNY", "1.0",
                        "0.5", "0.4", "0.1", "100", "40", "30", "30", "60", "2")),
                sheet("Plugin usage detail", PLUGIN_HEADER));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).hasSize(1);
        var warn = result.issues().get(0);
        assertThat(warn.severity()).isEqualTo(ImportIssueSeverity.WARN);
        assertThat(warn.issueCode()).isEqualTo("EMPTY_OPTIONAL_SHEET");
        assertThat(warn.recordLocator()).isEqualTo("Plugin usage detail");
        assertThat(warn.fieldName()).isNull();
    }

    @Test
    void missingPluginSheetIsOptionalAbsence() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "key-1", "CNY", "1.0",
                        "0.5", "0.4", "0.1", "100", "40", "30", "30", "60", "2")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void populatedPluginSheetDoesNotWarnAboutEmptiness() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "key-1", "CNY", "1.0",
                        "0.5", "0.4", "0.1", "100", "40", "30", "30", "60", "2")),
                sheet("Plugin usage detail",
                        PLUGIN_HEADER,
                        row("2026-08-01", "plugin-search", "key-2", "CNY", "0.88", "3")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void missingRequiredModelSheetIsIncompatible() throws Exception {
        var bytes = fixture(sheet("Plugin usage detail", PLUGIN_HEADER));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_SHEET");
    }

    @Test
    void missingRequiredModelColumnIsIncompatible() throws Exception {
        var bytes = fixture(sheet("Model usage detail",
                row("Date", "Model", "Currency", "Consumed Amount")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_COLUMN");
    }

    @Test
    void unknownExtraSheetAndColumnWarn() throws Exception {
        var extraHeader = new ArrayList<>(MODEL_HEADER);
        extraHeader.add("future_column");
        var extraRow = new ArrayList<>(Arrays.asList(
                "2026-08-01", "mimo-example", "key-1", "CNY", "1.0",
                "0.5", "0.4", "0.1", "100", "40", "30", "30", "60", "2"));
        extraRow.add("x");
        var bytes = fixture(
                sheet("Model usage detail", extraHeader, extraRow),
                sheet("Future report", List.of("a")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).extracting(i -> i.issueCode())
                .contains("UNKNOWN_COLUMN", "UNKNOWN_SHEET");
    }

    @Test
    void wrongSourceTypeIsIncompatible() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "key-1", "CNY", "1.0",
                        "0.5", "0.4", "0.1", "100", "40", "30", "30", "60", "2")));

        var result = adapter.inspect(
                new ProviderInput(new ByteArraySource(bytes), ImportSourceType.COSTS_API_JSON,
                        "usage.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("WRONG_SOURCE_TYPE");
    }

    // ------------------------------------------------------------------
    // parse and normalize
    // ------------------------------------------------------------------

    @Test
    void modelRowNormalizesTotalsComponentsAndMoney() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "sk-SECRET-SENTINEL-DO-NOT-PERSIST", "CNY", "1.234",
                        "0.5", "0.4", "0.334", "1000", "400", "300", "300", "3600", "10")));

        var records = parse(bytes);

        assertThat(records).hasSize(1);
        var record = records.get(0);
        assertThat(record.locator()).isEqualTo("Model usage detail:row:2");
        assertThat(record.normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
        assertThat(record.usageStart()).isNull();
        assertThat(record.usageEnd()).isNull();
        assertThat(record.normalizedPayload()).containsEntry("sourceSchema", "mimo.usage-workbook.v1")
                .containsEntry("recordKind", "USAGE");
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) record.normalizedPayload().get("usage");
        assertThat(usage).containsEntry("totalTokens", 1000L)
                .containsEntry("inputHitTokens", 400L)
                .containsEntry("inputMissTokens", 300L)
                .containsEntry("outputTokens", 300L)
                .containsEntry("requestCount", 10L)
                .containsEntry("totalAudioDurationRaw", "3600");
        assertThat(usage).doesNotContainKeys("summedTokens");
        @SuppressWarnings("unchecked")
        Map<String, Object> money = (Map<String, Object>) record.normalizedPayload().get("money");
        assertThat(money).containsEntry("currency", "CNY")
                .containsEntry("reportedAmount", new BigDecimal("1.234"));
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) money.get("components");
        assertThat(components).containsEntry("inputHitAmount", new BigDecimal("0.5"))
                .containsEntry("inputMissAmount", new BigDecimal("0.4"))
                .containsEntry("outputAmount", new BigDecimal("0.334"));
        assertThat(money).doesNotContainKey("summedAmount");
        @SuppressWarnings("unchecked")
        Map<String, Object> dimensions = (Map<String, Object>) record.normalizedPayload().get("dimensions");
        assertThat(dimensions).containsEntry("model", "mimo-example")
                .containsEntry("credentialHint", "********");
        assertThat(record.normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("date", "2026-08-01");
    }

    @Test
    void noSeparateAdditiveChargeRowsAreEmittedForAmountComponents() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "key-1", "CNY", "1.234",
                        "0.5", "0.4", "0.334", "1000", "400", "300", "300", "3600", "10"),
                row("2026-08-02", "mimo-example-2", "key-2", "CNY", "2.0",
                        "1.0", "0.5", "0.5", "500", "200", "150", "150", "1800", "5")));

        var records = parse(bytes);

        assertThat(records).hasSize(2);
        assertThat(records).extracting(NormalizedProviderRecord::normalizeStatus)
                .containsOnly(RawRecordNormalizeStatus.NORMALIZED);
        for (var record : records) {
            assertThat(record.normalizedPayload().toString()).doesNotContain("summed");
        }
    }

    @Test
    void apiKeySentinelNeverSurvivesIntoRawOrNormalizedPayloads() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "sk-SECRET-SENTINEL-DO-NOT-PERSIST", "CNY", "1.0",
                        "0.5", "0.4", "0.1", "100", "40", "30", "30", "60", "2")));

        var records = parse(bytes);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).rawPayload().toString())
                .doesNotContain("SECRET-SENTINEL");
        assertThat(records.get(0).rawPayload()).doesNotContainKey("API Key");
        assertThat(records.get(0).normalizedPayload().toString()).doesNotContain("SECRET-SENTINEL");
    }

    @Test
    void pluginRowNormalizesAsPluginUsage() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "key-1", "CNY", "1.0",
                        "0.5", "0.4", "0.1", "100", "40", "30", "30", "60", "2")),
                sheet("Plugin usage detail",
                        PLUGIN_HEADER,
                        row("2026-08-01", "plugin-search", "sk-SECRET-SENTINEL-DO-NOT-PERSIST", "CNY", "0.88", "3")));

        var records = parse(bytes);

        assertThat(records).hasSize(2);
        var plugin = records.get(1);
        assertThat(plugin.locator()).isEqualTo("Plugin usage detail:row:2");
        assertThat(plugin.normalizedPayload()).containsEntry("recordKind", "PLUGIN_USAGE");
        assertThat(plugin.normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("plugin", "plugin-search")
                .containsEntry("date", "2026-08-01");
        assertThat(plugin.normalizedPayload().get("usage"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("requestCount", 3L);
        assertThat(plugin.normalizedPayload().get("money"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("reportedAmount", new BigDecimal("0.88"))
                .containsEntry("currency", "CNY");
        assertThat(plugin.rawPayload().toString()).doesNotContain("SECRET-SENTINEL");
    }

    @Test
    void emptyPluginSheetEmitsNoFabricatedRecord() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "key-1", "CNY", "1.0",
                        "0.5", "0.4", "0.1", "100", "40", "30", "30", "60", "2")),
                sheet("Plugin usage detail", PLUGIN_HEADER));

        var records = parse(bytes);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).locator()).startsWith("Model usage detail");
    }

    @Test
    void malformedRequiredNumbersAndMoneyAreRowErrors() throws Exception {
        var bytes = fixture(modelSheet(
                row("2026-08-01", "mimo-example", "key-1", "CNY", "not-a-number",
                        "0.5", "0.4", "0.1", "many", "40", "30", "30", "60", "2")));

        var records = parse(bytes);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.ERROR);
        assertThat(records.get(0).issues()).extracting(i -> i.issueCode())
                .contains("INVALID_REQUIRED_MONEY", "INVALID_REQUIRED_NUMBER");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<NormalizedProviderRecord> parse(byte[] bytes) throws Exception {
        var input = input(bytes);
        var inspection = adapter.inspect(input);
        assertThat(inspection.compatible()).as("fixture must inspect as compatible").isTrue();
        var records = new ArrayList<NormalizedProviderRecord>();
        adapter.parse(input, inspection, (ProviderRecordSink) records::add);
        return records;
    }

    private static ProviderInput input(byte[] bytes) {
        return new ProviderInput(new ByteArraySource(bytes), ImportSourceType.FILE_EXPORT,
                "mimo-usage.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private static byte[] fixture(Map.Entry<String, List<List<String>>>... sheets) throws Exception {
        var map = new LinkedHashMap<String, List<List<String>>>();
        for (var sheet : sheets) {
            map.put(sheet.getKey(), sheet.getValue());
        }
        return ProviderFixtureFactory.xlsx(map);
    }

    @SafeVarargs
    private static Map.Entry<String, List<List<String>>> modelSheet(List<String>... rows) {
        var all = new ArrayList<List<String>>();
        all.add(MODEL_HEADER);
        all.addAll(Arrays.asList(rows));
        return Map.entry("Model usage detail", all);
    }

    @SafeVarargs
    private static Map.Entry<String, List<List<String>>> sheet(String name, List<String>... rows) {
        return Map.entry(name, Arrays.asList(rows));
    }

    @SafeVarargs
    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    private record ByteArraySource(byte[] bytes) implements ProviderSource {
        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public long sizeBytes() {
            return bytes.length;
        }

        @Override
        public String objectKey() {
            return "fixture";
        }
    }
}
