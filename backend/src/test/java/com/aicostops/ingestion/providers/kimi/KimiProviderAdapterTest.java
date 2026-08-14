package com.aicostops.ingestion.providers.kimi;

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

class KimiProviderAdapterTest {

    private static final List<String> SUMMARY_HEADER = List.of(
            "时间范围", "用户ID", "组织ID", "客户主体", "充值账户消耗（元）", "赠送账户消耗（元）");

    private final KimiProviderAdapter adapter = new KimiProviderAdapter(new XlsxStreamingReader());

    // ------------------------------------------------------------------
    // inspection
    // ------------------------------------------------------------------

    @Test
    void observedBillingSummaryShapeIsCompatible() throws Exception {
        var bytes = fixture(sheet("账单汇总",
                SUMMARY_HEADER,
                row("2026-08-01 00:00:00 - 2026-08-31 23:59:59", "user-1", "org-1", "某客户", "0", "0")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isTrue();
        assertThat(result.detectedProviderCode()).isEqualTo("KIMI");
        assertThat(result.schemaVariant()).isEqualTo("kimi.billing-summary-workbook.v1");
        assertThat(result.schemaFingerprint()).hasSize(64);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void unknownExtraColumnWarns() throws Exception {
        var bytes = fixture(sheet("账单汇总",
                List.of("时间范围", "用户ID", "组织ID", "客户主体", "充值账户消耗（元）",
                        "赠送账户消耗（元）", "未来字段"),
                row("2026-08-01 00:00:00 - 2026-08-31 23:59:59", "user-1", "org-1", "某客户", "0", "0", "x")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("UNKNOWN_COLUMN");
        assertThat(result.issues()).extracting(i -> i.severity())
                .containsOnly(ImportIssueSeverity.WARN);
    }

    @Test
    void missingMonetaryColumnIsIncompatible() throws Exception {
        var bytes = fixture(sheet("账单汇总",
                List.of("时间范围", "用户ID", "组织ID", "客户主体"),
                row("2026-08-01 00:00:00 - 2026-08-31 23:59:59", "user-1", "org-1", "某客户")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_COLUMN");
    }

    @Test
    void missingSummarySheetIsIncompatible() throws Exception {
        var bytes = fixture(sheet("Other sheet", List.of("a"), row("b")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_SHEET");
    }

    @Test
    void wrongSourceTypeIsIncompatible() throws Exception {
        var bytes = fixture(sheet("账单汇总",
                SUMMARY_HEADER,
                row("2026-08-01 00:00:00 - 2026-08-31 23:59:59", "user-1", "org-1", "某客户", "0", "0")));

        var result = adapter.inspect(
                new ProviderInput(new ByteArraySource(bytes), ImportSourceType.USAGE_API_JSON,
                        "kimi.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("WRONG_SOURCE_TYPE");
    }

    // ------------------------------------------------------------------
    // normalize
    // ------------------------------------------------------------------

    @Test
    void nonZeroRowNormalizesWithoutDerivedTotalOrFocusCredit() throws Exception {
        var bytes = fixture(sheet("账单汇总",
                SUMMARY_HEADER,
                row("2026-08-01 00:00:00 - 2026-08-31 23:59:59", "user-42", "org-7", "某客户主体",
                        "88.50", "11.50")));

        var records = parse(bytes);

        assertThat(records).hasSize(1);
        var record = records.get(0);
        assertThat(record.locator()).isEqualTo("账单汇总:row:2");
        assertThat(record.normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
        assertThat(record.usageStart()).isNull();
        assertThat(record.usageEnd()).isNull();
        assertThat(record.normalizedPayload()).containsEntry("sourceSchema", "kimi.billing-summary-workbook.v1")
                .containsEntry("recordKind", "BILLING_SUMMARY");
        assertThat(record.normalizedPayload()).doesNotContainKeys("usage");
        @SuppressWarnings("unchecked")
        Map<String, Object> dimensions = (Map<String, Object>) record.normalizedPayload().get("dimensions");
        assertThat(dimensions).containsEntry("providerUser", "user-42")
                .containsEntry("providerOrganization", "org-7");
        assertThat(dimensions).doesNotContainKeys("model", "providerProject", "credentialHint");
        @SuppressWarnings("unchecked")
        Map<String, Object> providerFields = (Map<String, Object>) record.normalizedPayload().get("providerFields");
        assertThat(providerFields).containsEntry("billingEntity", "某客户主体")
                .containsEntry("periodText", "2026-08-01 00:00:00 - 2026-08-31 23:59:59");
        @SuppressWarnings("unchecked")
        Map<String, Object> money = (Map<String, Object>) record.normalizedPayload().get("money");
        assertThat(money).containsEntry("currency", "CNY");
        assertThat(money).doesNotContainKey("reportedAmount");
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) money.get("components");
        assertThat(components).containsEntry("paidBalanceConsumption", new BigDecimal("88.50"))
                .containsEntry("promotionalBalanceConsumption", new BigDecimal("11.50"));
        assertThat(record.normalizedPayload().toString())
                .doesNotContain("FOCUS")
                .doesNotContain("team")
                .doesNotContain("employee")
                .doesNotContain("project");
    }

    @Test
    void zeroRowIsRetainedFaithfully() throws Exception {
        var bytes = fixture(sheet("账单汇总",
                SUMMARY_HEADER,
                row("2026-08-01 00:00:00 - 2026-08-31 23:59:59", "user-1", "org-1", "某客户", "0", "0.00")));

        var records = parse(bytes);

        assertThat(records).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>)
                ((Map<String, Object>) records.get(0).normalizedPayload().get("money")).get("components");
        assertThat(components).containsEntry("paidBalanceConsumption", BigDecimal.ZERO)
                .containsEntry("promotionalBalanceConsumption", new BigDecimal("0.00"));
    }

    @Test
    void malformedRequiredMoneyIsRowError() throws Exception {
        var bytes = fixture(sheet("账单汇总",
                SUMMARY_HEADER,
                row("2026-08-01 00:00:00 - 2026-08-31 23:59:59", "user-1", "org-1", "某客户", "abc", "0")));

        var records = parse(bytes);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.ERROR);
        assertThat(records.get(0).issues()).extracting(i -> i.issueCode())
                .containsExactly("INVALID_REQUIRED_MONEY");
    }

    @Test
    void rawPayloadKeepsOriginalProviderColumns() throws Exception {
        var bytes = fixture(sheet("账单汇总",
                SUMMARY_HEADER,
                row("2026-08-01 00:00:00 - 2026-08-31 23:59:59", "user-1", "org-1", "某客户", "1.5", "2.5")));

        var records = parse(bytes);

        assertThat(records.get(0).rawPayload()).containsEntry("用户ID", "user-1")
                .containsEntry("充值账户消耗（元）", "1.5")
                .containsEntry("赠送账户消耗（元）", "2.5");
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
                "kimi-billing.xlsx",
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
