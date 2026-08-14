package com.aicostops.ingestion.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ingestion.application.InspectionResult;
import com.aicostops.ingestion.application.NormalizedProviderRecord;
import com.aicostops.ingestion.application.ProviderInput;
import com.aicostops.ingestion.application.ProviderRecordSink;
import com.aicostops.ingestion.application.ProviderSource;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiProviderAdapterTest {

    private static final String EMPTY_EXPORT_HEADER =
            "start_time,end_time,start_time_iso,end_time_iso";

    private final OpenAiProviderAdapter adapter =
            new OpenAiProviderAdapter(tools.jackson.databind.json.JsonMapper.builder().build());

    // ------------------------------------------------------------------
    // Part A: observed empty CSV export
    // ------------------------------------------------------------------

    @Test
    void observedEmptyExportHeaderIsCompatibleUnderFileExport() {
        var result = adapter.inspect(input(ImportSourceType.FILE_EXPORT,
                csv("completions_usage_2026-08-01.csv", EMPTY_EXPORT_HEADER + "\n"), "completions_usage_2026-08-01.csv"));

        assertThat(result.compatible()).isTrue();
        assertThat(result.detectedProviderCode()).isEqualTo("OPENAI");
        assertThat(result.schemaVariant()).isEqualTo("openai.observed-empty-export.v1");
        assertThat(result.schemaFingerprint()).hasSize(64);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void wrongSourceTypeForObservedCsvIsIncompatible() {
        var result = adapter.inspect(input(ImportSourceType.USAGE_API_JSON,
                csv("completions_usage_2026-08-01.csv", EMPTY_EXPORT_HEADER + "\n"),
                "completions_usage_2026-08-01.csv"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.severity())
                .containsOnly(ImportIssueSeverity.ERROR);
    }

    @Test
    void unknownAdditionalCsvColumnsWarnButStayCompatible() {
        var result = adapter.inspect(input(ImportSourceType.FILE_EXPORT,
                csv("cost_2026-08-01.csv",
                        EMPTY_EXPORT_HEADER + ",input_tokens\n"
                                + "1780000000,1780003600,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,10\n"),
                "cost_2026-08-01.csv"));

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("UNKNOWN_COLUMN");
        assertThat(result.issues()).extracting(i -> i.severity())
                .containsOnly(ImportIssueSeverity.WARN);
        // The variant stays the narrow observed-empty-export contract; the extra
        // column does not upgrade this to a claimed populated-CSV schema.
        assertThat(result.schemaVariant()).isEqualTo("openai.observed-empty-export.v1");
    }

    @Test
    void missingRequiredTimeFieldIsIncompatible() {
        var result = adapter.inspect(input(ImportSourceType.FILE_EXPORT,
                csv("completions_usage_2026-08-01.csv", "start_time,end_time,start_time_iso\n"),
                "completions_usage_2026-08-01.csv"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_COLUMN");
    }

    @Test
    void nonCsvContentIsIncompatible() {
        var result = adapter.inspect(input(ImportSourceType.FILE_EXPORT,
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3},
                "usage.png"));

        assertThat(result.compatible()).isFalse();
    }

    @Test
    void usageFilenamePrefixProducesEmptyUsageBucketRecords() {
        var records = parse(ImportSourceType.FILE_EXPORT,
                csv("completions_usage_2026-08-01.csv",
                        EMPTY_EXPORT_HEADER + "\n"
                                + "1780000000,1780003600,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z\n"),
                "completions_usage_2026-08-01.csv");

        assertThat(records).hasSize(1);
        var record = records.get(0);
        assertThat(record.locator()).isEqualTo("export.csv:row:1");
        assertThat(record.normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
        assertThat(record.normalizedPayload()).containsEntry("recordKind", "EMPTY_USAGE_BUCKET")
                .containsEntry("sourceSchema", "openai.observed-empty-export.v1");
        assertThat(record.normalizedPayload()).doesNotContainKeys("usage", "money");
        assertThat(record.normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("exportKind", "USAGE");
    }

    @Test
    void costFilenamePrefixProducesEmptyCostBucketRecords() {
        var records = parse(ImportSourceType.FILE_EXPORT,
                csv("cost_2026-08-01.csv", EMPTY_EXPORT_HEADER + "\n"
                        + "1780000000,1780003600,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z\n"),
                "cost_2026-08-01.csv");

        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizedPayload()).containsEntry("recordKind", "EMPTY_COST_BUCKET");
        assertThat(records.get(0).normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("exportKind", "COST");
    }

    @Test
    void otherExactHeaderFilenameWarnsWithUnknownExportKind() {
        var records = parse(ImportSourceType.FILE_EXPORT,
                csv("activity_2026-08-01.csv", EMPTY_EXPORT_HEADER + "\n"
                        + "1780000000,1780003600,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z\n"),
                "activity_2026-08-01.csv");

        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.WARN);
        assertThat(records.get(0).issues()).extracting(i -> i.issueCode())
                .containsExactly("UNKNOWN_EXPORT_KIND");
        assertThat(records.get(0).normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("exportKind", "UNKNOWN");
    }

    @Test
    void bucketTimesNormalizeToInstantsWithoutMetrics() {
        var records = parse(ImportSourceType.FILE_EXPORT,
                csv("completions_usage_2026-08-01.csv", EMPTY_EXPORT_HEADER + "\n"
                        + "1780000000,1780003600,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z\n"),
                "completions_usage_2026-08-01.csv");

        var record = records.get(0);
        assertThat(record.usageStart()).isEqualTo(Instant.ofEpochSecond(1780000000L));
        assertThat(record.usageEnd()).isEqualTo(Instant.ofEpochSecond(1780003600L));
        assertThat(record.normalizedPayload().toString())
                .doesNotContain("tokens").doesNotContain("requests").doesNotContain("amount")
                .doesNotContain("usage").doesNotContain("money");
    }

    @Test
    void isoFallbackIsUsedWhenEpochSecondsAreUnparseable() {
        var records = parse(ImportSourceType.FILE_EXPORT,
                csv("completions_usage_2026-08-01.csv", EMPTY_EXPORT_HEADER + "\n"
                        + "not-a-epoch,also-bad,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z\n"),
                "completions_usage_2026-08-01.csv");

        var record = records.get(0);
        assertThat(record.usageStart()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(record.usageEnd()).isEqualTo(Instant.parse("2026-08-01T01:00:00Z"));
    }

    // ------------------------------------------------------------------
    // Part B/C: official Usage and Costs JSON
    // ------------------------------------------------------------------

    @Test
    void usageJsonIsCompatibleUnderUsageApiJson() {
        var result = adapter.inspect(input(ImportSourceType.USAGE_API_JSON,
                classpath("/provider-fixtures/openai/official-usage-completions.json"),
                "usage.json"));

        assertThat(result.compatible()).isTrue();
        assertThat(result.schemaVariant()).isEqualTo("openai.organization-usage-completions-json.v1");
        assertThat(result.schemaFingerprint()).hasSize(64);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void usageBytesAreRejectedUnderCostsApiJson() {
        var result = adapter.inspect(input(ImportSourceType.COSTS_API_JSON,
                classpath("/provider-fixtures/openai/official-usage-completions.json"),
                "usage.json"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.severity())
                .contains(ImportIssueSeverity.ERROR);
    }

    @Test
    void costsJsonIsCompatibleUnderCostsApiJson() {
        var result = adapter.inspect(input(ImportSourceType.COSTS_API_JSON,
                classpath("/provider-fixtures/openai/official-costs.json"),
                "costs.json"));

        assertThat(result.compatible()).isTrue();
        assertThat(result.schemaVariant()).isEqualTo("openai.organization-costs-json.v1");
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void costsBytesAreRejectedUnderUsageApiJson() {
        var result = adapter.inspect(input(ImportSourceType.USAGE_API_JSON,
                classpath("/provider-fixtures/openai/official-costs.json"),
                "costs.json"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.severity())
                .contains(ImportIssueSeverity.ERROR);
    }

    @Test
    void malformedJsonPageShapeIsIncompatible() {
        var result = adapter.inspect(input(ImportSourceType.USAGE_API_JSON,
                json("{\"data\": \"not-an-array\"}"),
                "usage.json"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MALFORMED_JSON");
    }

    @Test
    void unknownJsonResultFieldWarnsAndRawIsPreserved() {
        var drifted = json("""
                {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                  {"object":"organization.usage.completions.result","input_tokens":100,"output_tokens":20,"num_model_requests":2,
                   "model":"gpt-example","future_metric":1}]}]}
                """);
        var inspection = adapter.inspect(input(ImportSourceType.USAGE_API_JSON, drifted, "usage.json"));

        assertThat(inspection.compatible()).isTrue();
        assertThat(inspection.issues()).extracting(i -> i.issueCode()).contains("UNKNOWN_FIELD");

        var records = parse(ImportSourceType.USAGE_API_JSON, drifted, "usage.json");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).rawPayload()).containsEntry("future_metric", 1L);
    }

    @Test
    void missingRequiredUsageFieldIsIncompatible() {
        var result = adapter.inspect(input(ImportSourceType.USAGE_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.usage.completions.result","output_tokens":20,"num_model_requests":2}]}]}
                        """),
                "usage.json"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_FIELD");
    }

    @Test
    void usageTokensNormalizeAsSeparateMetersWithoutCachedAddition() {
        var records = parse(ImportSourceType.USAGE_API_JSON,
                classpath("/provider-fixtures/openai/official-usage-completions.json"),
                "usage.json");

        assertThat(records).hasSize(1);
        var record = records.get(0);
        assertThat(record.locator()).isEqualTo("data[0].results[0]");
        assertThat(record.normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
        assertThat(record.usageStart()).isEqualTo(Instant.ofEpochSecond(1780000000L));
        assertThat(record.usageEnd()).isEqualTo(Instant.ofEpochSecond(1780003600L));
        assertThat(record.normalizedPayload()).containsEntry("recordKind", "USAGE")
                .containsEntry("sourceSchema", "openai.organization-usage-completions-json.v1");
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) record.normalizedPayload().get("usage");
        assertThat(usage).containsEntry("inputTokens", 100L)
                .containsEntry("outputTokens", 20L)
                .containsEntry("inputCachedTokens", 40L)
                .containsEntry("inputCacheWriteTokens", 5L)
                .containsEntry("inputUncachedTokens", 55L)
                .containsEntry("inputTextTokens", 90L)
                .containsEntry("inputAudioTokens", 0L)
                .containsEntry("inputImageTokens", 10L)
                .containsEntry("inputCachedTextTokens", 35L)
                .containsEntry("inputCachedAudioTokens", 0L)
                .containsEntry("inputCachedImageTokens", 5L)
                .containsEntry("outputTextTokens", 18L)
                .containsEntry("outputAudioTokens", 0L)
                .containsEntry("outputImageTokens", 2L)
                .containsEntry("numModelRequests", 2L);
        assertThat(usage).doesNotContainKeys("inputTokensPlusCached", "summedTokens");
        assertThat(record.normalizedPayload()).doesNotContainKeys("money");
        assertThat(record.normalizedPayload().get("dimensions"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("model", "gpt-example")
                .containsEntry("providerUser", "user_fake")
                .containsEntry("providerProject", "proj_fake")
                .containsEntry("credentialId", "keyid_fake");
        assertThat(record.normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("batch", false)
                .containsEntry("serviceTier", "default");
        assertThat(record.normalizedPayload()).doesNotContainKey("apiKeyId");
    }

    @Test
    void costsNormalizeMoneyAndDimensionsWithCurrentContract() {
        var records = parse(ImportSourceType.COSTS_API_JSON,
                classpath("/provider-fixtures/openai/official-costs.json"),
                "costs.json");

        assertThat(records).hasSize(1);
        var record = records.get(0);
        assertThat(record.locator()).isEqualTo("data[0].results[0]");
        assertThat(record.normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
        assertThat(record.normalizedPayload()).containsEntry("recordKind", "COST")
                .containsEntry("sourceSchema", "openai.organization-costs-json.v1");
        assertThat(record.normalizedPayload().get("money"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("currency", "usd")
                .containsEntry("reportedAmount", new java.math.BigDecimal("1.23"));
        assertThat(record.normalizedPayload().get("dimensions"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("providerProject", "proj_fake")
                .containsEntry("credentialId", "keyid_fake");
        assertThat(record.normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("lineItem", "example-line-item")
                .containsEntry("quantity", 1500L);
        assertThat(record.normalizedPayload()).doesNotContainKey("apiKeyId");
    }

    @Test
    void costsOptionalDimensionsMayBeAbsentWhenNotGrouped() {
        var result = adapter.inspect(input(ImportSourceType.COSTS_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.costs.result","amount":{"value":0.5,"currency":"usd"}}]}]}
                        """),
                "costs.json"));

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).extracting(i -> i.severity())
                .containsOnly(ImportIssueSeverity.WARN);

        var records = parse(ImportSourceType.COSTS_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.costs.result","amount":{"value":0.5,"currency":"usd"}}]}]}
                        """),
                "costs.json");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
        assertThat(records.get(0).normalizedPayload().get("money"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("reportedAmount", new java.math.BigDecimal("0.5"));
    }

    @Test
    void nullRequiredTokenIsRowError() {
        var result = adapter.inspect(input(ImportSourceType.USAGE_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.usage.completions.result","input_tokens":null,"output_tokens":20,"num_model_requests":2}]}]}
                        """),
                "usage.json"));
        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode())
                .contains("MISSING_REQUIRED_FIELD");

        var records = parseRows(ImportSourceType.USAGE_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.usage.completions.result","input_tokens":null,"output_tokens":20,"num_model_requests":2}]}]}
                        """),
                "usage.json");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.ERROR);
        assertThat(records.get(0).issues()).extracting(i -> i.issueCode())
                .contains("INVALID_REQUIRED_NUMBER");
    }

    @Test
    void floatingRequestCountIsRowError() {
        var records = parseRows(ImportSourceType.USAGE_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.usage.completions.result","input_tokens":100,"output_tokens":20,"num_model_requests":2.5}]}]}
                        """),
                "usage.json");

        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.ERROR);
        assertThat(records.get(0).issues()).extracting(i -> i.issueCode())
                .contains("INVALID_REQUIRED_NUMBER");
    }

    @Test
    void nullOptionalBreakdownIsRowErrorButMissingIsOmission() {
        var nullBreakdown = parseRows(ImportSourceType.USAGE_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.usage.completions.result","input_tokens":100,"output_tokens":20,"num_model_requests":2,"input_cached_tokens":null}]}]}
                        """),
                "usage.json");
        assertThat(nullBreakdown.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.ERROR);
        assertThat(nullBreakdown.get(0).issues()).extracting(i -> i.issueCode())
                .contains("INVALID_OPTIONAL_NUMBER");

        var missingBreakdown = parseRows(ImportSourceType.USAGE_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.usage.completions.result","input_tokens":100,"output_tokens":20,"num_model_requests":2}]}]}
                        """),
                "usage.json");
        assertThat(missingBreakdown.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
        assertThat(missingBreakdown.get(0).normalizedPayload().get("usage"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .doesNotContainKey("inputCachedTokens");
    }

    @Test
    void nullAmountValueIsRowError() {
        var records = parseRows(ImportSourceType.COSTS_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.costs.result","amount":{"value":null,"currency":"usd"}}]}]}
                        """),
                "costs.json");

        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.ERROR);
        assertThat(records.get(0).issues()).extracting(i -> i.issueCode())
                .contains("INVALID_REQUIRED_MONEY");
    }

    @Test
    void missingBucketTimeIsRowError() {
        var records = parseRows(ImportSourceType.USAGE_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","results":[
                          {"object":"organization.usage.completions.result","input_tokens":100,"output_tokens":20,"num_model_requests":2}]}]}
                        """),
                "usage.json");

        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.ERROR);
        assertThat(records.get(0).issues()).extracting(i -> i.issueCode())
                .contains("INVALID_BUCKET_TIME");
    }

    @Test
    void manyResultsStreamThroughCountingSinkWithoutResultLists() {
        var json = new StringBuilder();
        json.append("{\"object\":\"page\",\"data\":[{\"object\":\"bucket\",\"start_time\":1780000000,"
                + "\"end_time\":1780003600,\"results\":[");
        for (var i = 0; i < 10_000; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"object\":\"organization.usage.completions.result\",\"input_tokens\":100,"
                    + "\"output_tokens\":20,\"num_model_requests\":2,\"model\":\"gpt-example\"}");
        }
        json.append("]}]}");
        var bytes = json.toString().getBytes(StandardCharsets.UTF_8);

        var records = parse(ImportSourceType.USAGE_API_JSON, bytes, "usage.json");

        assertThat(records).hasSize(10_000);
        assertThat(records.get(0).locator()).isEqualTo("data[0].results[0]");
        assertThat(records.get(9_999).locator()).isEqualTo("data[0].results[9999]");
        assertThat(records.get(9_999).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
    }

    @Test
    void missingRequiredCostsAmountIsIncompatible() {
        var result = adapter.inspect(input(ImportSourceType.COSTS_API_JSON,
                json("""
                        {"object":"page","data":[{"object":"bucket","start_time":1780000000,"end_time":1780003600,"results":[
                          {"object":"organization.costs.result","line_item":"example-line-item","project_id":"proj_fake"}]}]}
                        """),
                "costs.json"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_FIELD");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<NormalizedProviderRecord> parse(
            ImportSourceType type, byte[] bytes, String filename) {
        var input = input(type, bytes, filename);
        var inspection = adapter.inspect(input);
        assertThat(inspection.compatible()).as("fixture must inspect as compatible").isTrue();
        var records = new ArrayList<NormalizedProviderRecord>();
        adapter.parse(input, inspection, (ProviderRecordSink) records::add);
        return records;
    }

    /** Row-level tests may bypass the compatible-inspection assertion. */
    private List<NormalizedProviderRecord> parseRows(
            ImportSourceType type, byte[] bytes, String filename) {
        var input = input(type, bytes, filename);
        var inspection = adapter.inspect(input);
        var records = new ArrayList<NormalizedProviderRecord>();
        adapter.parse(input, inspection, (ProviderRecordSink) records::add);
        return records;
    }

    private static ProviderInput input(ImportSourceType type, byte[] bytes, String filename) {
        return new ProviderInput(new ByteArraySource(bytes), type, filename, "text/csv");
    }

    private static byte[] csv(String ignored, String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] json(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] classpath(String path) {
        try (var in = OpenAiProviderAdapterTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + path);
            }
            return in.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Failed to read fixture " + path, failure);
        }
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
