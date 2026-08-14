package com.aicostops.ingestion.providers.deepseek;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ingestion.application.InspectionResult;
import com.aicostops.ingestion.application.NormalizedProviderRecord;
import com.aicostops.ingestion.application.ParsedProviderRecord;
import com.aicostops.ingestion.application.ProviderInput;
import com.aicostops.ingestion.application.ProviderRecordSink;
import com.aicostops.ingestion.application.ProviderSource;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.ingestion.providers.fixtures.ProviderFixtureFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeepSeekProviderAdapterTest {

    private static final String AMOUNT_HEADER =
            "user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount";
    private static final String COST_HEADER =
            "user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency";

    private final DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(
            new com.aicostops.ingestion.providers.common.SafeZipReader(
                    new com.aicostops.ingestion.providers.common.ProviderParserProperties(
                            64, 1_073_741_824L, 100.0d, 1_048_576L)));

    // ------------------------------------------------------------------
    // inspection
    // ------------------------------------------------------------------

    @Test
    void observedEmptyShapeZipIsCompatible() throws Exception {
        var zip = ProviderFixtureFactory.zip(Map.of(
                "amount-2026-08-01.csv", AMOUNT_HEADER + "\n",
                "cost-2026-08-01.csv", COST_HEADER + "\n"));

        var result = inspect(zip);

        assertThat(result.compatible()).isTrue();
        assertThat(result.detectedProviderCode()).isEqualTo("DEEPSEEK");
        assertThat(result.schemaVariant()).isEqualTo("deepseek.usage-zip.v1");
        assertThat(result.schemaFingerprint()).hasSize(64);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void dateBearingFilenamesDoNotChangeFingerprint() throws Exception {
        var august = ProviderFixtureFactory.zip(Map.of(
                "amount-2026-08-01.csv", AMOUNT_HEADER + "\n",
                "cost-2026-08-01.csv", COST_HEADER + "\n"));
        var september = ProviderFixtureFactory.zip(Map.of(
                "amount-2026-09-30.csv", AMOUNT_HEADER + "\n",
                "cost-2026-09-30.csv", COST_HEADER + "\n"));

        assertThat(inspect(august).schemaFingerprint())
                .isEqualTo(inspect(september).schemaFingerprint());
    }

    @Test
    void reorderedColumnsStayCompatibleWithSameFingerprint() throws Exception {
        var normal = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", AMOUNT_HEADER + "\n",
                "cost-1.csv", COST_HEADER + "\n"));
        var reordered = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", "model,user_id,amount,price,type,api_key,api_key_name,end_time_iso,start_time_iso\n",
                "cost-1.csv", "currency,cost,wallet_type,model,user_id,end_time_iso,start_time_iso\n"));

        assertThat(inspect(reordered).compatible()).isTrue();
        assertThat(inspect(reordered).schemaFingerprint())
                .isEqualTo(inspect(normal).schemaFingerprint());
    }

    @Test
    void unknownExtraColumnWarnsAndChangesFingerprint() throws Exception {
        var base = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", AMOUNT_HEADER + "\n",
                "cost-1.csv", COST_HEADER + "\n"));
        var drifted = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", AMOUNT_HEADER + ",future_column\n",
                "cost-1.csv", COST_HEADER + "\n"));

        var result = inspect(drifted);

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).extracting(i -> i.severity()).containsOnly(ImportIssueSeverity.WARN);
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("UNKNOWN_COLUMN");
        assertThat(result.schemaFingerprint()).isNotEqualTo(inspect(base).schemaFingerprint());
    }

    @Test
    void missingRequiredColumnIsIncompatible() throws Exception {
        var zip = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", "user_id,start_time_iso,model\n",
                "cost-1.csv", COST_HEADER + "\n"));

        var result = inspect(zip);

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_COLUMN");
    }

    @Test
    void missingLogicalCsvRoleIsIncompatible() throws Exception {
        var zip = ProviderFixtureFactory.zip(Map.of(
                "amount-2026-08-01.csv", AMOUNT_HEADER + "\n"));

        var result = inspect(zip);

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_ARCHIVE_ROLE");
    }

    @Test
    void duplicateLogicalRoleIsIncompatible() throws Exception {
        var zip = ProviderFixtureFactory.zip(Map.of(
                "amount-2026-08-01.csv", AMOUNT_HEADER + "\n",
                "amount-2026-08-02.csv", AMOUNT_HEADER + "\n",
                "cost-2026-08-01.csv", COST_HEADER + "\n"));

        var result = inspect(zip);

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("DUPLICATE_ARCHIVE_ROLE");
    }

    @Test
    void unsafePathIsIncompatible() throws Exception {
        var zip = ProviderFixtureFactory.zip(Map.of(
                "../amount-1.csv", AMOUNT_HEADER + "\n",
                "cost-1.csv", COST_HEADER + "\n"));

        var result = inspect(zip);

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("UNSAFE_ARCHIVE");
    }

    @Test
    void wrongSourceTypeIsIncompatible() throws Exception {
        var zip = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", AMOUNT_HEADER + "\n",
                "cost-1.csv", COST_HEADER + "\n"));

        var result = adapter.inspect(input(ImportSourceType.USAGE_API_JSON, zip, "fixture.zip"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("WRONG_SOURCE_TYPE");
    }

    @Test
    void unknownExtraEntryWarnsButStaysCompatible() throws Exception {
        var zip = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", AMOUNT_HEADER + "\n",
                "cost-1.csv", COST_HEADER + "\n",
                "readme.txt", "notes"));

        var result = inspect(zip);

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("UNKNOWN_ARCHIVE_ENTRY");
    }

    @Test
    void malformedArchiveIsIncompatible() {
        var garbage = new byte[256];
        new java.util.Random(3).nextBytes(garbage);

        var result = adapter.inspect(input(ImportSourceType.FILE_EXPORT, garbage, "bad.zip"));

        assertThat(result.compatible()).isFalse();
    }

    @Test
    void duplicateNormalizedCsvHeadersAreDeterministicInspectionError() throws Exception {
        // "model" and " model " collide after normalization; this must become a
        // DUPLICATE_COLUMN inspection ERROR, never a worker EXECUTION_FAILED.
        var zip = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", "user_id,model, model \n",
                "cost-1.csv", COST_HEADER + "\n"));

        var result = adapter.inspect(input(ImportSourceType.FILE_EXPORT, zip, "deepseek-export.zip"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("DUPLICATE_COLUMN");
        assertThat(result.issues()).extracting(i -> i.severity())
                .containsOnly(ImportIssueSeverity.ERROR);
    }

    // ------------------------------------------------------------------
    // parse and normalize
    // ------------------------------------------------------------------

    @Test
    void populatedRowsNormalizeWithRawFidelityAndDimensions() throws Exception {
        var entries = new java.util.LinkedHashMap<String, String>();
        entries.put("amount-2026-08-01.csv", AMOUNT_HEADER + "\n"
                + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,default,"
                + "sk-SECRET-SENTINEL-DO-NOT-PERSIST,api_call,0.000002,125\n");
        entries.put("cost-2026-08-01.csv", COST_HEADER + "\n"
                + "user-1,2026-08-01T00:00:00+08:00,2026-08-01T01:00:00+08:00,deepseek-chat,main_wallet,1.25,CNY\n");
        var zip = ProviderFixtureFactory.zip(entries);

        var records = parse(zip);

        assertThat(records).hasSize(2);
        var amount = records.get(0);
        assertThat(amount.locator()).isEqualTo("amount.csv:row:1");
        assertThat(amount.normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
        assertThat(amount.usageStart()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(amount.usageEnd()).isEqualTo(Instant.parse("2026-08-01T01:00:00Z"));
        assertThat(amount.rawPayload()).containsEntry("user_id", "user-1")
                .containsEntry("type", "api_call")
                .containsEntry("price", "0.000002")
                .containsEntry("amount", "125")
                .containsEntry("api_key_name", "default");
        assertThat(amount.normalizedPayload()).containsEntry("sourceSchema", "deepseek.usage-zip.v1")
                .containsEntry("recordKind", "USAGE");
        assertThat(amount.normalizedPayload().get("dimensions"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("model", "deepseek-chat")
                .containsEntry("providerUser", "user-1");

        var cost = records.get(1);
        assertThat(cost.locator()).isEqualTo("cost.csv:row:1");
        assertThat(cost.usageStart()).isEqualTo(Instant.parse("2026-07-31T16:00:00Z"));
        assertThat(cost.rawPayload()).containsEntry("wallet_type", "main_wallet");
        assertThat(cost.normalizedPayload()).containsEntry("recordKind", "COST");
        assertThat(cost.normalizedPayload().get("money"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("currency", "CNY")
                .containsEntry("reportedAmount", new BigDecimal("1.25"));
        assertThat(cost.normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("walletType", "main_wallet");
    }

    @Test
    void apiKeySentinelNeverSurvivesIntoRawOrNormalizedPayloads() throws Exception {
        var zip = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", AMOUNT_HEADER + "\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,m,default,"
                        + "sk-SECRET-SENTINEL-DO-NOT-PERSIST,api_call,1,2\n",
                "cost-1.csv", COST_HEADER + "\n"));

        var records = parse(zip);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).rawPayload().toString())
                .doesNotContain("SECRET-SENTINEL").doesNotContain("sk-");
        assertThat(records.get(0).rawPayload()).doesNotContainKey("api_key");
        assertThat(records.get(0).normalizedPayload().toString())
                .doesNotContain("SECRET-SENTINEL").doesNotContain("sk-");
        assertThat(records.get(0).normalizedPayload().get("dimensions"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("credentialHint", "********");
    }

    @Test
    void timezoneLessTimesDoNotBecomeInstants() throws Exception {
        var zip = ProviderFixtureFactory.zip(Map.of(
                "amount-1.csv", AMOUNT_HEADER + "\n"
                        + "user-1,2026-08-01T00:00:00,2026-08-01T01:00:00,m,default,k,api_call,1,2\n",
                "cost-1.csv", COST_HEADER + "\n"));

        var records = parse(zip);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).usageStart()).isNull();
        assertThat(records.get(0).usageEnd()).isNull();
    }

    @Test
    void malformedRequiredMoneyIsRowErrorNotZero() throws Exception {
        var entries = new java.util.LinkedHashMap<String, String>();
        entries.put("amount-1.csv", AMOUNT_HEADER + "\n"
                + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,m,default,k,api_call,1,not-a-number\n");
        entries.put("cost-1.csv", COST_HEADER + "\n"
                + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,m,main_wallet,12.5,CNY\n");
        var zip = ProviderFixtureFactory.zip(entries);

        var records = parse(zip);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.ERROR);
        assertThat(records.get(0).issues()).extracting(i -> i.issueCode())
                .containsExactly("INVALID_REQUIRED_MONEY");
        assertThat(records.get(0).issues()).extracting(i -> i.severity())
                .containsExactly(ImportIssueSeverity.ERROR);
        assertThat(records.get(1).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
    }

    @Test
    void rowLocatorsAreStableAcrossPhysicalFilenames() throws Exception {
        var entries = new java.util.LinkedHashMap<String, String>();
        entries.put("amount-anything.csv", AMOUNT_HEADER + "\n" + "u,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,m,k,kk,t,1,2\n");
        entries.put("cost-anything.csv", COST_HEADER + "\n" + "u,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,m,w,3,CNY\n");
        var zip = ProviderFixtureFactory.zip(entries);

        var records = parse(zip);

        assertThat(records).extracting(NormalizedProviderRecord::locator)
                .containsExactly("amount.csv:row:1", "cost.csv:row:1");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private InspectionResult inspect(byte[] bytes) {
        return adapter.inspect(input(ImportSourceType.FILE_EXPORT, bytes, "deepseek-export.zip"));
    }

    private List<NormalizedProviderRecord> parse(byte[] bytes) {
        var inspection = inspect(bytes);
        assertThat(inspection.compatible()).as("fixture must inspect as compatible").isTrue();
        var records = new ArrayList<NormalizedProviderRecord>();
        var input = input(ImportSourceType.FILE_EXPORT, bytes, "deepseek-export.zip");
        var sink = new ProviderRecordSink() {
            @Override
            public void accept(NormalizedProviderRecord record) {
                records.add(record);
            }
        };
        adapter.parse(input, inspection, sink);
        return records;
    }

    private static ProviderInput input(ImportSourceType type, byte[] bytes, String filename) {
        return new ProviderInput(new ByteArraySource(bytes), type, filename, "application/zip");
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

    @SuppressWarnings("unused")
    private static ParsedProviderRecord parsed(int index, Map<String, Object> fields) {
        return new ParsedProviderRecord(index, "amount.csv:row:" + index, fields);
    }
}
