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
import org.junit.jupiter.api.Test;

class OpenAiProviderAdapterTest {

    private static final String EMPTY_EXPORT_HEADER =
            "start_time,end_time,start_time_iso,end_time_iso";

    private final OpenAiProviderAdapter adapter = new OpenAiProviderAdapter();

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
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("WRONG_SOURCE_TYPE");
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

    private static ProviderInput input(ImportSourceType type, byte[] bytes, String filename) {
        return new ProviderInput(new ByteArraySource(bytes), type, filename, "text/csv");
    }

    private static byte[] csv(String ignored, String content) {
        return content.getBytes(StandardCharsets.UTF_8);
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
