package com.aicostops.ingestion.providers.glm;

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

class GlmProviderAdapterTest {

    private static final List<String> SUMMARY_HEADER = List.of(
            "账期(月)", "目录总价", "总消费金额", "信用支付金额", "赠金抵扣金额",
            "应付金额", "已付款金额", "待付款金额", "结算状态");

    private final GlmProviderAdapter adapter = new GlmProviderAdapter(new XlsxStreamingReader());

    // ------------------------------------------------------------------
    // inspection
    // ------------------------------------------------------------------

    @Test
    void observedMonthlySummaryShapeIsCompatible() throws Exception {
        var bytes = fixture(sheet("账单明细",
                SUMMARY_HEADER,
                row("2026-08", "100.00", "90.00", "30.00", "10.00",
                        "50.00", "50.00", "0.00", "已结清")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isTrue();
        assertThat(result.detectedProviderCode()).isEqualTo("GLM");
        assertThat(result.schemaVariant()).isEqualTo("glm.monthly-billing-summary-workbook.v1");
        assertThat(result.schemaFingerprint()).hasSize(64);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void unknownExtraColumnWarns() throws Exception {
        var header = new ArrayList<>(SUMMARY_HEADER);
        header.add("future_column");
        var data = new ArrayList<>(Arrays.asList(
                "2026-08", "100.00", "90.00", "30.00", "10.00", "50.00", "50.00", "0.00", "已结清"));
        data.add("x");
        var bytes = fixture(sheet("账单明细", header, data));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isTrue();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("UNKNOWN_COLUMN");
        assertThat(result.issues()).extracting(i -> i.severity())
                .containsOnly(ImportIssueSeverity.WARN);
    }

    @Test
    void missingRequiredFieldIsIncompatible() throws Exception {
        var bytes = fixture(sheet("账单明细",
                List.of("账期(月)", "目录总价", "总消费金额", "信用支付金额", "赠金抵扣金额",
                        "应付金额", "已付款金额", "待付款金额"),
                row("2026-08", "100.00", "90.00", "30.00", "10.00", "50.00", "50.00", "0.00")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_COLUMN");
    }

    @Test
    void noSheetMatchingGlmSchemaIsIncompatible() throws Exception {
        var bytes = fixture(sheet("Other sheet",
                List.of("a", "b"), row("1", "2")));

        var result = adapter.inspect(input(bytes));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("MISSING_REQUIRED_SHEET");
    }

    @Test
    void wrongSourceTypeIsIncompatible() throws Exception {
        var bytes = fixture(sheet("账单明细",
                SUMMARY_HEADER,
                row("2026-08", "100.00", "90.00", "30.00", "10.00", "50.00", "50.00", "0.00", "已结清")));

        var result = adapter.inspect(
                new ProviderInput(new ByteArraySource(bytes), ImportSourceType.USAGE_API_JSON,
                        "glm.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        assertThat(result.compatible()).isFalse();
        assertThat(result.issues()).extracting(i -> i.issueCode()).contains("WRONG_SOURCE_TYPE");
    }

    // ------------------------------------------------------------------
    // normalize
    // ------------------------------------------------------------------

    @Test
    void everySettlementFieldIsPreservedIndependentlyWithoutFormula() throws Exception {
        // Deliberately "mathematically inconsistent" row: payable does not equal
        // consumption minus deduction, outstanding does not equal payable minus paid.
        var bytes = fixture(sheet("账单明细",
                SUMMARY_HEADER,
                row("2026-08", "0.00", "0.00", "20.00", "0.00",
                        "15.00", "25.00", "-10.00", "部分结清")));

        var records = parse(bytes);

        assertThat(records).hasSize(1);
        var record = records.get(0);
        assertThat(record.normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.NORMALIZED);
        assertThat(record.usageStart()).isNull();
        assertThat(record.usageEnd()).isNull();
        assertThat(record.normalizedPayload()).containsEntry("sourceSchema", "glm.monthly-billing-summary-workbook.v1")
                .containsEntry("recordKind", "BILLING_SUMMARY");
        assertThat(record.normalizedPayload()).doesNotContainKeys("usage");
        @SuppressWarnings("unchecked")
        Map<String, Object> money = (Map<String, Object>) record.normalizedPayload().get("money");
        assertThat(money).doesNotContainKey("reportedAmount").doesNotContainKey("currency");
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) money.get("components");
        assertThat(components).containsEntry("catalogAmount", new BigDecimal("0.00"))
                .containsEntry("consumptionAmount", new BigDecimal("0.00"))
                .containsEntry("creditPaymentAmount", new BigDecimal("20.00"))
                .containsEntry("promotionalDeductionAmount", new BigDecimal("0.00"))
                .containsEntry("payableAmount", new BigDecimal("15.00"))
                .containsEntry("paidAmount", new BigDecimal("25.00"))
                .containsEntry("outstandingAmount", new BigDecimal("-10.00"));
        assertThat(record.normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("billingMonth", "2026-08")
                .containsEntry("settlementStatus", "部分结清");
    }

    @Test
    void billingMonthStaysProviderNativeText() throws Exception {
        var bytes = fixture(sheet("账单明细",
                SUMMARY_HEADER,
                row("2026-08", "100.00", "90.00", "30.00", "10.00", "50.00", "50.00", "0.00", "已结清")));

        var records = parse(bytes);

        assertThat(records.get(0).normalizedPayload().get("providerFields"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("billingMonth", "2026-08");
        assertThat(records.get(0).usageStart()).isNull();
        assertThat(records.get(0).usageEnd()).isNull();
    }

    @Test
    void malformedRequiredMoneyIsRowError() throws Exception {
        var bytes = fixture(sheet("账单明细",
                SUMMARY_HEADER,
                row("2026-08", "abc", "90.00", "30.00", "10.00", "50.00", "50.00", "0.00", "已结清")));

        var records = parse(bytes);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).normalizeStatus()).isEqualTo(RawRecordNormalizeStatus.ERROR);
        assertThat(records.get(0).issues()).extracting(i -> i.issueCode())
                .containsExactly("INVALID_REQUIRED_MONEY");
    }

    @Test
    void rawPayloadKeepsOriginalProviderColumns() throws Exception {
        var bytes = fixture(sheet("账单明细",
                SUMMARY_HEADER,
                row("2026-08", "100.00", "90.00", "30.00", "10.00", "50.00", "50.00", "0.00", "已结清")));

        var records = parse(bytes);

        assertThat(records.get(0).rawPayload()).containsEntry("账期(月)", "2026-08")
                .containsEntry("应付金额", "50.00")
                .containsEntry("结算状态", "已结清");
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
                "glm-monthly.xlsx",
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
