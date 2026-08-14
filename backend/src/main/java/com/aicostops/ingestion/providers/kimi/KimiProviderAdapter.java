package com.aicostops.ingestion.providers.kimi;

import com.aicostops.ingestion.application.InspectionResult;
import com.aicostops.ingestion.application.ImportIssueDraft;
import com.aicostops.ingestion.application.NormalizedProviderRecord;
import com.aicostops.ingestion.application.ParsedProviderRecord;
import com.aicostops.ingestion.application.ProviderAdapter;
import com.aicostops.ingestion.application.ProviderInput;
import com.aicostops.ingestion.application.ProviderRecordSink;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.ingestion.providers.common.HeaderNormalizer;
import com.aicostops.ingestion.providers.common.NormalizedPayloadBuilder;
import com.aicostops.ingestion.providers.common.ProviderNumberParser;
import com.aicostops.ingestion.providers.common.SchemaDescriptor;
import com.aicostops.ingestion.providers.common.SchemaFingerprint;
import com.aicostops.ingestion.providers.common.WorkbookSchema;
import com.aicostops.ingestion.providers.common.XlsxStreamingReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Kimi / Moonshot billing summary workbook adapter for
 * {@code kimi.billing-summary-workbook.v1}.
 *
 * <p>Only the observed {@code 账单汇总} sheet is supported; public Kimi docs do not
 * authorize adding unobserved columns. The two monetary columns are independent
 * provider-reported components: no reported total is derived, and the promotional
 * balance is never mapped to any FOCUS credit concept. Provider user/organization
 * values stay provider-native hints; no internal employee/team/project assignment
 * is made. {@code 时间范围} remains text because no timezone-aware interval parser
 * is evidenced.
 */
@Component
public final class KimiProviderAdapter implements ProviderAdapter {

    static final String PROVIDER_CODE = "KIMI";
    static final String PARSER_VERSION = "kimi-provider-import-v1";
    static final String SCHEMA_VARIANT = "kimi.billing-summary-workbook.v1";
    static final String CURRENCY = "CNY";

    static final String SUMMARY_SHEET = "账单汇总";

    private static final List<String> REQUIRED_HEADERS = List.of(
            "时间范围", "用户ID", "组织ID", "客户主体", "充值账户消耗（元）", "赠送账户消耗（元）");

    private final XlsxStreamingReader workbookReader;

    public KimiProviderAdapter(XlsxStreamingReader workbookReader) {
        this.workbookReader = workbookReader;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }

    @Override
    public InspectionResult inspect(ProviderInput input) {
        if (input.sourceType() != ImportSourceType.FILE_EXPORT) {
            return incompatible(List.of(issue(ImportIssueSeverity.ERROR, "WRONG_SOURCE_TYPE",
                    input.originalFilename(), null, "Kimi billing workbooks require FILE_EXPORT")));
        }
        var issues = new ArrayList<ImportIssueDraft>();
        WorkbookSchema schema;
        try (var workbook = input.source().openStream()) {
            schema = workbookReader.inspect(workbook);
        } catch (IOException failure) {
            return incompatible(List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_WORKBOOK",
                    input.originalFilename(), null, "Workbook could not be read")));
        }

        if (schema.headers(SUMMARY_SHEET).isEmpty()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_SHEET",
                    input.originalFilename(), SUMMARY_SHEET,
                    "Required sheet '" + SUMMARY_SHEET + "' is missing"));
        }

        var rolesForFingerprint = new LinkedHashMap<String, List<String>>();
        for (var entry : schema.headersBySheet().entrySet()) {
            var sheetName = entry.getKey();
            rolesForFingerprint.put(sheetName, HeaderNormalizer.normalizeAll(entry.getValue()));
            if (sheetName.equals(SUMMARY_SHEET)) {
                validateHeaders(sheetName, entry.getValue(), issues);
            } else {
                issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_SHEET",
                        sheetName, null, "Unknown extra sheet in Kimi workbook"));
            }
        }

        var fingerprint = SchemaFingerprint.sha256(new SchemaDescriptor(
                PROVIDER_CODE, ImportSourceType.FILE_EXPORT, SCHEMA_VARIANT, rolesForFingerprint));
        var compatible = issues.stream().noneMatch(i -> i.severity() == ImportIssueSeverity.ERROR);
        return new InspectionResult(PROVIDER_CODE, SCHEMA_VARIANT, fingerprint, compatible, issues);
    }

    private void validateHeaders(
            String sheetName, List<String> rawHeaders, List<ImportIssueDraft> issues) {
        var actual = HeaderNormalizer.normalizeAll(rawHeaders);
        var required = HeaderNormalizer.normalizeAll(REQUIRED_HEADERS);
        for (var i = 0; i < required.size(); i++) {
            if (!actual.contains(required.get(i))) {
                issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_COLUMN",
                        sheetName, REQUIRED_HEADERS.get(i),
                        "Required column '" + REQUIRED_HEADERS.get(i) + "' is missing from " + sheetName));
            }
        }
        for (var raw : rawHeaders) {
            var normalized = HeaderNormalizer.normalize(raw);
            if (!required.contains(normalized)) {
                issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_COLUMN",
                        sheetName, raw, "Unknown extra column in " + sheetName));
            }
        }
    }

    @Override
    public void parse(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink) {
        var index = new AtomicInteger();
        try (var workbook = input.source().openStream()) {
            workbookReader.forEachRow(workbook, Set.of(SUMMARY_SHEET), row -> {
                sink.accept(normalize(new ParsedProviderRecord(index.getAndIncrement(),
                        row.sheetName() + ":row:" + row.rowNumber(), new LinkedHashMap<>(row.values())),
                        inspection));
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Kimi workbook parse failed (category)", failure);
        }
    }

    @Override
    public NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection) {
        var fields = record.fields();
        var issues = new ArrayList<ImportIssueDraft>();
        var status = RawRecordNormalizeStatus.NORMALIZED;

        var paid = ProviderNumberParser.decimal(string(fields.get("充值账户消耗（元）")));
        var promotional = ProviderNumberParser.decimal(string(fields.get("赠送账户消耗（元）")));
        if (paid.missing() || paid.invalid() || promotional.missing() || promotional.invalid()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_MONEY",
                    record.locator(), "充值账户消耗（元）",
                    "Both monetary columns must be valid decimals"));
            status = RawRecordNormalizeStatus.ERROR;
        }

        var builder = new NormalizedPayloadBuilder(SCHEMA_VARIANT, "BILLING_SUMMARY")
                .dimension("providerUser", fields.get("用户ID"))
                .dimension("providerOrganization", fields.get("组织ID"))
                .providerField("billingEntity", fields.get("客户主体"))
                .providerField("periodText", fields.get("时间范围"));
        if (status == RawRecordNormalizeStatus.NORMALIZED) {
            builder.money("currency", CURRENCY)
                    .moneyComponent("paidBalanceConsumption", paid.value())
                    .moneyComponent("promotionalBalanceConsumption", promotional.value());
        }

        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                new LinkedHashMap<>(fields), builder.build(), null, null, status, issues);
    }

    private static ImportIssueDraft issue(
            ImportIssueSeverity severity, String code, String locator, String fieldName, String message) {
        return new ImportIssueDraft(severity, code, locator, fieldName, message, null);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static InspectionResult incompatible(List<ImportIssueDraft> issues) {
        return new InspectionResult(PROVIDER_CODE, SCHEMA_VARIANT, "", false, issues);
    }
}
