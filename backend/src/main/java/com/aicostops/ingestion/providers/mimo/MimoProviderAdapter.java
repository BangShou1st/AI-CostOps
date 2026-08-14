package com.aicostops.ingestion.providers.mimo;

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
import com.aicostops.ingestion.providers.common.WorkbookRow;
import com.aicostops.ingestion.providers.common.WorkbookSchema;
import com.aicostops.ingestion.providers.common.XlsxStreamingReader;
import com.aicostops.ingestion.providers.common.SchemaDescriptor;
import com.aicostops.ingestion.providers.common.SchemaFingerprint;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Xiaomi MiMo usage workbook adapter for {@code mimo.usage-workbook.v1}.
 *
 * <p>{@code Model usage detail} is required; {@code Plugin usage detail} is a
 * recognized optional sheet whose observed empty state is valid and produces exactly
 * one {@code EMPTY_OPTIONAL_SHEET} WARN without fabricating records. {@code Total
 * Tokens} and {@code Consumed Amount} are provider-reported aggregates; the hit /
 * miss / output values are breakdown components and are never summed into fake
 * totals or emitted as additional charge rows. {@code Date} has no timezone and is
 * preserved as provider-native text, never converted to an {@code Instant}.
 */
@Component
public final class MimoProviderAdapter implements ProviderAdapter {

    public static final String PROVIDER_CODE = "MIMO";
    public static final String PARSER_VERSION = "mimo-provider-import-v1";
    public static final String SCHEMA_VARIANT = "mimo.usage-workbook.v1";
    static final String CREDENTIAL_HINT = "********";

    static final String MODEL_SHEET = "Model usage detail";
    static final String PLUGIN_SHEET = "Plugin usage detail";
    static final String API_KEY_COLUMN = "API Key";

    private static final List<String> REQUIRED_MODEL_HEADERS = List.of(
            "Date", "Model", "API Key", "Currency", "Consumed Amount",
            "Input Hit Amount", "Input Miss Amount", "Output Amount", "Total Tokens",
            "Input Hit Tokens", "Input Miss Tokens", "Output Tokens",
            "Total audio duration", "Request Count");
    private static final List<String> REQUIRED_PLUGIN_HEADERS = List.of(
            "Date", "Plugin", "API Key", "Currency", "Consumed Amount", "Request Count");

    private final XlsxStreamingReader workbookReader;

    public MimoProviderAdapter(XlsxStreamingReader workbookReader) {
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
                    input.originalFilename(), null, "MiMo usage workbooks require FILE_EXPORT")));
        }
        var issues = new ArrayList<ImportIssueDraft>();
        WorkbookSchema schema;
        try (var workbook = input.source().openStream()) {
            schema = workbookReader.inspect(workbook);
        } catch (IOException failure) {
            return incompatible(List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_WORKBOOK",
                    input.originalFilename(), null, "Workbook could not be read")));
        }

        if (schema.headers(MODEL_SHEET).isEmpty()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_SHEET",
                    input.originalFilename(), MODEL_SHEET,
                    "Required sheet '" + MODEL_SHEET + "' is missing"));
        }

        var rolesForFingerprint = new LinkedHashMap<String, List<String>>();
        for (var entry : schema.headersBySheet().entrySet()) {
            var sheetName = entry.getKey();
            var rawHeaders = entry.getValue();
            rolesForFingerprint.put(sheetName, HeaderNormalizer.normalizeAll(rawHeaders));
            if (sheetName.equals(MODEL_SHEET)) {
                validateHeaders(MODEL_SHEET, rawHeaders, REQUIRED_MODEL_HEADERS, issues);
            } else if (sheetName.equals(PLUGIN_SHEET)) {
                validateHeaders(PLUGIN_SHEET, rawHeaders, REQUIRED_PLUGIN_HEADERS, issues);
            } else {
                issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_SHEET",
                        sheetName, null, "Unknown extra sheet in MiMo workbook"));
            }
        }

        if (schema.headers(PLUGIN_SHEET).isPresent() && schema.headers(MODEL_SHEET).isPresent()
                && hasNoDataRows(input, PLUGIN_SHEET)) {
            issues.add(issue(ImportIssueSeverity.WARN, "EMPTY_OPTIONAL_SHEET",
                    PLUGIN_SHEET, null, "Recognized optional sheet is empty"));
        }

        var fingerprint = SchemaFingerprint.sha256(new SchemaDescriptor(
                PROVIDER_CODE, ImportSourceType.FILE_EXPORT, SCHEMA_VARIANT, rolesForFingerprint));
        var compatible = issues.stream().noneMatch(i -> i.severity() == ImportIssueSeverity.ERROR);
        return new InspectionResult(PROVIDER_CODE, SCHEMA_VARIANT, fingerprint, compatible, issues);
    }

    private boolean hasNoDataRows(ProviderInput input, String sheetName) {
        var count = new AtomicInteger();
        try (var workbook = input.source().openStream()) {
            workbookReader.forEachRow(workbook, Set.of(sheetName), row -> count.incrementAndGet());
        } catch (IOException failure) {
            return false;
        }
        return count.get() == 0;
    }

    private void validateHeaders(
            String sheetName, List<String> rawHeaders, List<String> requiredHeaders,
            List<ImportIssueDraft> issues) {
        var actual = HeaderNormalizer.normalizeAll(rawHeaders);
        var required = HeaderNormalizer.normalizeAll(requiredHeaders);
        for (var i = 0; i < required.size(); i++) {
            if (!actual.contains(required.get(i))) {
                issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_COLUMN",
                        sheetName, requiredHeaders.get(i),
                        "Required column '" + requiredHeaders.get(i) + "' is missing from " + sheetName));
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
            workbookReader.forEachRow(workbook, Set.of(MODEL_SHEET, PLUGIN_SHEET), row -> {
                sink.accept(normalize(new ParsedProviderRecord(index.getAndIncrement(),
                        row.sheetName() + ":row:" + row.rowNumber(), new LinkedHashMap<>(row.values())),
                        inspection));
            });
        } catch (IOException failure) {
            throw new IllegalStateException("MiMo workbook parse failed (category)", failure);
        }
    }

    @Override
    public NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection) {
        if (record.locator().startsWith(MODEL_SHEET + ":")) {
            return normalizeModelRow(record);
        }
        return normalizePluginRow(record);
    }

    private NormalizedProviderRecord normalizeModelRow(ParsedProviderRecord record) {
        var fields = record.fields();
        var issues = new ArrayList<ImportIssueDraft>();
        var status = RawRecordNormalizeStatus.NORMALIZED;

        var consumed = ProviderNumberParser.decimal(string(fields.get("Consumed Amount")));
        var currency = string(fields.get("Currency"));
        var totalTokens = ProviderNumberParser.longValue(string(fields.get("Total Tokens")));
        var inputHitTokens = ProviderNumberParser.longValue(string(fields.get("Input Hit Tokens")));
        var inputMissTokens = ProviderNumberParser.longValue(string(fields.get("Input Miss Tokens")));
        var outputTokens = ProviderNumberParser.longValue(string(fields.get("Output Tokens")));
        var requestCount = ProviderNumberParser.longValue(string(fields.get("Request Count")));
        if (consumed.invalid() || currency == null || currency.isBlank()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_MONEY",
                    record.locator(), "Consumed Amount",
                    "Consumed Amount must be a valid decimal and Currency must not be blank"));
            status = RawRecordNormalizeStatus.ERROR;
        }
        if (invalidLong(totalTokens) || invalidLong(inputHitTokens) || invalidLong(inputMissTokens)
                || invalidLong(outputTokens) || invalidLong(requestCount)) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_NUMBER",
                    record.locator(), "Total Tokens",
                    "Token and request counts must be valid integers"));
            status = RawRecordNormalizeStatus.ERROR;
        }

        var builder = new NormalizedPayloadBuilder(SCHEMA_VARIANT, "USAGE")
                .dimension("model", fields.get("Model"))
                .dimension("credentialHint", CREDENTIAL_HINT)
                .providerField("date", fields.get("Date"))
                .usage("totalAudioDurationRaw", fields.get("Total audio duration"));
        if (status == RawRecordNormalizeStatus.NORMALIZED) {
            builder.usage("totalTokens", totalTokens.value())
                    .usage("inputHitTokens", inputHitTokens.value())
                    .usage("inputMissTokens", inputMissTokens.value())
                    .usage("outputTokens", outputTokens.value())
                    .usage("requestCount", requestCount.value())
                    .money("currency", currency)
                    .money("reportedAmount", consumed.value())
                    .moneyComponent("inputHitAmount", decimalOf(fields.get("Input Hit Amount")))
                    .moneyComponent("inputMissAmount", decimalOf(fields.get("Input Miss Amount")))
                    .moneyComponent("outputAmount", decimalOf(fields.get("Output Amount")));
        }

        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                rawWithoutApiKey(fields), builder.build(), null, null, status, issues);
    }

    private NormalizedProviderRecord normalizePluginRow(ParsedProviderRecord record) {
        var fields = record.fields();
        var issues = new ArrayList<ImportIssueDraft>();
        var status = RawRecordNormalizeStatus.NORMALIZED;

        var consumed = ProviderNumberParser.decimal(string(fields.get("Consumed Amount")));
        var currency = string(fields.get("Currency"));
        var requestCount = ProviderNumberParser.longValue(string(fields.get("Request Count")));
        if (consumed.invalid() || currency == null || currency.isBlank()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_MONEY",
                    record.locator(), "Consumed Amount",
                    "Consumed Amount must be a valid decimal and Currency must not be blank"));
            status = RawRecordNormalizeStatus.ERROR;
        }
        if (invalidLong(requestCount)) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_NUMBER",
                    record.locator(), "Request Count", "Request Count must be a valid integer"));
            status = RawRecordNormalizeStatus.ERROR;
        }

        var builder = new NormalizedPayloadBuilder(SCHEMA_VARIANT, "PLUGIN_USAGE")
                .dimension("credentialHint", CREDENTIAL_HINT)
                .providerField("plugin", fields.get("Plugin"))
                .providerField("date", fields.get("Date"));
        if (status == RawRecordNormalizeStatus.NORMALIZED) {
            builder.usage("requestCount", requestCount.value())
                    .money("currency", currency)
                    .money("reportedAmount", consumed.value());
        }

        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                rawWithoutApiKey(fields), builder.build(), null, null, status, issues);
    }

    /** Raw provider fields minus the API-key value, which is never persisted. */
    private static Map<String, Object> rawWithoutApiKey(Map<String, Object> fields) {
        var raw = new LinkedHashMap<String, Object>();
        for (var entry : fields.entrySet()) {
            if (entry.getKey().equals(API_KEY_COLUMN)) {
                continue;
            }
            raw.put(entry.getKey(), entry.getValue());
        }
        return raw;
    }

    private static BigDecimal decimalOf(Object value) {
        var parsed = ProviderNumberParser.decimal(string(value));
        return parsed.valid() ? parsed.value() : null;
    }

    private static boolean invalidLong(ProviderNumberParser.ParsedValue<Long> parsed) {
        return parsed.invalid();
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
