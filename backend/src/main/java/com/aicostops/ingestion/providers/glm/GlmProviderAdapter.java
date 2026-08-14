package com.aicostops.ingestion.providers.glm;

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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * GLM / 智谱 monthly billing summary adapter for
 * {@code glm.monthly-billing-summary-workbook.v1}.
 *
 * <p>Only the observed monthly summary fields are supported; the separately
 * documented detailed-expense workbook is out of scope. The observed evidence does
 * not freeze a sheet name, so inspection locates the sheet whose headers contain the
 * full required set; other sheets WARN as unknown. Every financial field is retained
 * independently and faithfully: no settlement identity such as
 * {@code payable = consumption - deduction} or {@code outstanding = payable - paid}
 * is ever inferred, even when rows look mathematically inconsistent.
 * {@code 账期(月)} is preserved as provider billing-period text, not an {@code Instant}.
 */
@Component
public final class GlmProviderAdapter implements ProviderAdapter {

    static final String PROVIDER_CODE = "GLM";
    static final String PARSER_VERSION = "glm-provider-import-v1";
    static final String SCHEMA_VARIANT = "glm.monthly-billing-summary-workbook.v1";

    private static final List<String> REQUIRED_HEADERS = List.of(
            "账期(月)", "目录总价", "总消费金额", "信用支付金额", "赠金抵扣金额",
            "应付金额", "已付款金额", "待付款金额", "结算状态");

    private final XlsxStreamingReader workbookReader;

    public GlmProviderAdapter(XlsxStreamingReader workbookReader) {
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
                    input.originalFilename(), null, "GLM billing workbooks require FILE_EXPORT")));
        }
        var issues = new ArrayList<ImportIssueDraft>();
        WorkbookSchema schema;
        try (var workbook = input.source().openStream()) {
            schema = workbookReader.inspect(workbook);
        } catch (IOException failure) {
            return incompatible(List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_WORKBOOK",
                    input.originalFilename(), null, "Workbook could not be read")));
        }

        var requiredNormalized = HeaderNormalizer.normalizeAll(REQUIRED_HEADERS);
        var summarySheet = findSummarySheet(schema);
        if (summarySheet.isEmpty()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_SHEET",
                    input.originalFilename(), null,
                    "No sheet matches the GLM monthly billing summary schema"));
        }

        var rolesForFingerprint = new LinkedHashMap<String, List<String>>();
        for (var entry : schema.headersBySheet().entrySet()) {
            var sheetName = entry.getKey();
            var normalized = HeaderNormalizer.normalizeAll(entry.getValue());
            rolesForFingerprint.put(sheetName, normalized);
            if (summarySheet.isPresent() && sheetName.equals(summarySheet.get())) {
                validateHeaders(sheetName, entry.getValue(), requiredNormalized, issues);
            } else {
                issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_SHEET",
                        sheetName, null, "Unknown extra sheet in GLM workbook"));
            }
        }

        var fingerprint = SchemaFingerprint.sha256(new SchemaDescriptor(
                PROVIDER_CODE, ImportSourceType.FILE_EXPORT, SCHEMA_VARIANT, rolesForFingerprint));
        var compatible = issues.stream().noneMatch(i -> i.severity() == ImportIssueSeverity.ERROR);
        return new InspectionResult(PROVIDER_CODE, SCHEMA_VARIANT, fingerprint, compatible, issues);
    }

    private Optional<String> findSummarySheet(WorkbookSchema schema) {
        var requiredNormalized = HeaderNormalizer.normalizeAll(REQUIRED_HEADERS);
        String best = null;
        var bestScore = 0;
        for (var entry : schema.headersBySheet().entrySet()) {
            var normalized = HeaderNormalizer.normalizeAll(entry.getValue());
            var score = 0;
            for (var header : requiredNormalized) {
                if (normalized.contains(header)) {
                    score++;
                }
            }
            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
            }
        }
        return bestScore == 0 ? Optional.empty() : Optional.of(best);
    }

    private void validateHeaders(
            String sheetName, List<String> rawHeaders, List<String> requiredNormalized,
            List<ImportIssueDraft> issues) {
        var actualNormalized = HeaderNormalizer.normalizeAll(rawHeaders);
        for (var i = 0; i < requiredNormalized.size(); i++) {
            if (!actualNormalized.contains(requiredNormalized.get(i))) {
                issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_COLUMN",
                        sheetName, REQUIRED_HEADERS.get(i),
                        "Required column '" + REQUIRED_HEADERS.get(i) + "' is missing from " + sheetName));
            }
        }
        for (var raw : rawHeaders) {
            var normalized = HeaderNormalizer.normalize(raw);
            if (!requiredNormalized.contains(normalized)) {
                issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_COLUMN",
                        sheetName, raw, "Unknown extra column in " + sheetName));
            }
        }
    }

    @Override
    public void parse(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink) {
        WorkbookSchema schema;
        try (var workbook = input.source().openStream()) {
            schema = workbookReader.inspect(workbook);
        } catch (IOException failure) {
            throw new IllegalStateException("GLM workbook parse failed (category)", failure);
        }
        var summarySheet = findSummarySheet(schema)
                .orElseThrow(() -> new IllegalStateException("GLM summary sheet must exist after inspection"));
        var index = new AtomicInteger();
        try (var workbook = input.source().openStream()) {
            workbookReader.forEachRow(workbook, Set.of(summarySheet), row -> {
                sink.accept(normalize(new ParsedProviderRecord(index.getAndIncrement(),
                        row.sheetName() + ":row:" + row.rowNumber(), new LinkedHashMap<>(row.values())),
                        inspection));
            });
        } catch (IOException failure) {
            throw new IllegalStateException("GLM workbook parse failed (category)", failure);
        }
    }

    @Override
    public NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection) {
        var fields = record.fields();
        var issues = new ArrayList<ImportIssueDraft>();
        var status = RawRecordNormalizeStatus.NORMALIZED;

        var parsed = new BigDecimal[7];
        for (var i = 0; i < MONEY_HEADERS.size(); i++) {
            var value = ProviderNumberParser.decimal(string(fields.get(MONEY_HEADERS.get(i))));
            if (value.missing() || value.invalid()) {
                issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_MONEY",
                        record.locator(), MONEY_HEADERS.get(i),
                        "Monetary field '" + MONEY_HEADERS.get(i) + "' must be a valid decimal"));
                status = RawRecordNormalizeStatus.ERROR;
            } else {
                parsed[i] = value.value();
            }
        }

        var builder = new NormalizedPayloadBuilder(SCHEMA_VARIANT, "BILLING_SUMMARY")
                .providerField("billingMonth", fields.get("账期(月)"))
                .providerField("settlementStatus", fields.get("结算状态"));
        if (status == RawRecordNormalizeStatus.NORMALIZED) {
            builder.moneyComponent("catalogAmount", parsed[0])
                    .moneyComponent("consumptionAmount", parsed[1])
                    .moneyComponent("creditPaymentAmount", parsed[2])
                    .moneyComponent("promotionalDeductionAmount", parsed[3])
                    .moneyComponent("payableAmount", parsed[4])
                    .moneyComponent("paidAmount", parsed[5])
                    .moneyComponent("outstandingAmount", parsed[6]);
        }

        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                new LinkedHashMap<>(fields), builder.build(), null, null, status, issues);
    }

    private static final List<String> MONEY_HEADERS = List.of(
            "目录总价", "总消费金额", "信用支付金额", "赠金抵扣金额",
            "应付金额", "已付款金额", "待付款金额");

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
