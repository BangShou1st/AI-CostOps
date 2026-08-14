package com.aicostops.ingestion.providers.deepseek;

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
import com.aicostops.ingestion.providers.common.CsvSupport;
import com.aicostops.ingestion.providers.common.HeaderNormalizer;
import com.aicostops.ingestion.providers.common.NormalizedPayloadBuilder;
import com.aicostops.ingestion.providers.common.ProviderNumberParser;
import com.aicostops.ingestion.providers.common.ProviderTimeParser;
import com.aicostops.ingestion.providers.common.SafeZipReader;
import com.aicostops.ingestion.providers.common.SchemaDescriptor;
import com.aicostops.ingestion.providers.common.SchemaFingerprint;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * DeepSeek platform export adapter for the observed {@code deepseek.usage-zip.v1}
 * contract: one {@code amount-*.csv} and one {@code cost-*.csv} inside a ZIP.
 *
 * <p>Date-bearing entry names are normalized to the logical roles {@code amount} /
 * {@code cost}; they never enter the schema fingerprint. The raw {@code api_key}
 * value is dropped before any persisted-ready payload is built; only a fixed masked
 * credential hint is emitted. No {@code amount * price = cost} equation is inferred,
 * {@code wallet_type} is not enumerated, and {@code type} units are never guessed.
 */
@Component
public final class DeepSeekProviderAdapter implements ProviderAdapter {

    static final String PROVIDER_CODE = "DEEPSEEK";
    static final String PARSER_VERSION = "deepseek-provider-import-v1";
    static final String SCHEMA_VARIANT = "deepseek.usage-zip.v1";
    static final String CREDENTIAL_HINT = "********";

    private static final Pattern AMOUNT_ENTRY = Pattern.compile("^amount-.*\\.csv$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST_ENTRY = Pattern.compile("^cost-.*\\.csv$", Pattern.CASE_INSENSITIVE);
    private static final String AMOUNT_ROLE = "amount";
    private static final String COST_ROLE = "cost";

    private static final List<String> REQUIRED_AMOUNT_HEADERS = List.of(
            "user_id", "start_time_iso", "end_time_iso", "model", "api_key_name",
            "api_key", "type", "price", "amount");
    private static final List<String> REQUIRED_COST_HEADERS = List.of(
            "user_id", "start_time_iso", "end_time_iso", "model", "wallet_type", "cost", "currency");

    private final SafeZipReader zipReader;

    public DeepSeekProviderAdapter(SafeZipReader zipReader) {
        this.zipReader = zipReader;
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
                    input.originalFilename(), null,
                    "DeepSeek ZIP exports require FILE_EXPORT")));
        }
        var issues = new ArrayList<ImportIssueDraft>();
        var observedHeaders = new LinkedHashMap<String, List<String>>();
        var foundRoles = new LinkedHashMap<String, String>();
        try (var archive = input.source().openStream()) {
            zipReader.forEachEntry(archive, (entryName, content) -> {
                var role = classify(entryName);
                if (role == null) {
                    issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_ARCHIVE_ENTRY",
                            entryName, null, "Unknown extra archive entry"));
                    return;
                }
                if (foundRoles.containsKey(role)) {
                    issues.add(issue(ImportIssueSeverity.ERROR, "DUPLICATE_ARCHIVE_ROLE",
                            entryName, null, "Logical role '" + role + "' appears more than once"));
                    return;
                }
                foundRoles.put(role, entryName);
                try {
                    observedHeaders.put(role, CsvSupport.readHeader(content));
                } catch (IOException failure) {
                    issues.add(issue(ImportIssueSeverity.ERROR, "MALFORMED_CSV",
                            entryName, null, "Logical role '" + role + "' is not a readable CSV"));
                }
            });
        } catch (SafeZipReader.UnsafeArchiveException unsafe) {
            return incompatible(List.of(issue(ImportIssueSeverity.ERROR, "UNSAFE_ARCHIVE",
                    input.originalFilename(), null, "Unsafe archive: " + unsafe.reason())));
        } catch (IOException failure) {
            return incompatible(List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_ARCHIVE",
                    input.originalFilename(), null, "Archive could not be read")));
        }

        for (var required : List.of(AMOUNT_ROLE, COST_ROLE)) {
            if (!foundRoles.containsKey(required)) {
                issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_ARCHIVE_ROLE",
                        input.originalFilename(), null,
                        "Required logical archive role '" + required + "' is missing"));
            }
        }

        var rolesForFingerprint = new LinkedHashMap<String, List<String>>();
        for (var entry : observedHeaders.entrySet()) {
            var role = entry.getKey();
            var actual = HeaderNormalizer.normalizeAll(entry.getValue());
            rolesForFingerprint.put(role, actual);
            validateHeaders(role, actual, entry.getValue(), issues);
        }

        var fingerprint = SchemaFingerprint.sha256(new SchemaDescriptor(
                PROVIDER_CODE, ImportSourceType.FILE_EXPORT, SCHEMA_VARIANT, rolesForFingerprint));
        var compatible = issues.stream().noneMatch(i -> i.severity() == ImportIssueSeverity.ERROR);
        return new InspectionResult(PROVIDER_CODE, SCHEMA_VARIANT, fingerprint, compatible, issues);
    }

    private void validateHeaders(
            String role, List<String> actualNormalized, List<String> rawHeaders, List<ImportIssueDraft> issues) {
        var required = HeaderNormalizer.normalizeAll(role.equals(AMOUNT_ROLE)
                ? REQUIRED_AMOUNT_HEADERS : REQUIRED_COST_HEADERS);
        for (var i = 0; i < required.size(); i++) {
            if (!actualNormalized.contains(required.get(i))) {
                issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_COLUMN",
                        role + ".csv", role.equals(AMOUNT_ROLE)
                                ? REQUIRED_AMOUNT_HEADERS.get(i) : REQUIRED_COST_HEADERS.get(i),
                        "Required column '" + (role.equals(AMOUNT_ROLE)
                                ? REQUIRED_AMOUNT_HEADERS.get(i) : REQUIRED_COST_HEADERS.get(i))
                                + "' is missing from " + role + " CSV"));
            }
        }
        for (var raw : rawHeaders) {
            var normalized = HeaderNormalizer.normalize(raw);
            if (!required.contains(normalized)) {
                issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_COLUMN",
                        role + ".csv", raw, "Unknown extra column in " + role + " CSV"));
            }
        }
    }

    @Override
    public void parse(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink) {
        var index = new int[1];
        try (var archive = input.source().openStream()) {
            zipReader.forEachEntry(archive, (entryName, content) -> {
                var role = classify(entryName);
                if (role == null) {
                    return;
                }
                CsvSupport.forEachRecord(content, (rowNumber, values) -> {
                    var record = new ParsedProviderRecord(index[0]++,
                            role + ".csv:row:" + rowNumber, new LinkedHashMap<>(values));
                    sink.accept(normalize(record, inspection));
                });
            });
        } catch (IOException failure) {
            throw new IllegalStateException("DeepSeek ZIP parse failed (category)", failure);
        }
    }

    @Override
    public NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection) {
        if (record.locator().startsWith(AMOUNT_ROLE + ".csv:")) {
            return normalizeAmount(record);
        }
        return normalizeCost(record);
    }

    private NormalizedProviderRecord normalizeAmount(ParsedProviderRecord record) {
        var fields = record.fields();
        var raw = new LinkedHashMap<String, Object>();
        var hasApiKey = false;
        for (var entry : fields.entrySet()) {
            if (entry.getKey().equals("api_key")) {
                hasApiKey = true;
                continue; // never persisted raw; only a fixed masked hint is emitted
            }
            raw.put(entry.getKey(), entry.getValue());
        }
        var issues = new ArrayList<ImportIssueDraft>();
        var status = RawRecordNormalizeStatus.NORMALIZED;
        if (ProviderNumberParser.decimal(string(fields.get("amount"))).invalid()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_MONEY",
                    record.locator(), "amount",
                    "amount must be a valid decimal when present"));
            status = RawRecordNormalizeStatus.ERROR;
        }
        var builder = new NormalizedPayloadBuilder(SCHEMA_VARIANT, "USAGE")
                .dimension("model", fields.get("model"))
                .dimension("providerUser", fields.get("user_id"))
                .providerField("apiKeyName", fields.get("api_key_name"))
                .providerField("type", fields.get("type"))
                .providerField("price", fields.get("price"))
                .providerField("amount", fields.get("amount"));
        if (hasApiKey) {
            builder.dimension("credentialHint", CREDENTIAL_HINT);
        }
        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                raw, builder.build(),
                ProviderTimeParser.offsetInstant(string(fields.get("start_time_iso"))).orElse(null),
                ProviderTimeParser.offsetInstant(string(fields.get("end_time_iso"))).orElse(null),
                status, issues);
    }

    private NormalizedProviderRecord normalizeCost(ParsedProviderRecord record) {
        var fields = record.fields();
        var issues = new ArrayList<ImportIssueDraft>();
        var status = RawRecordNormalizeStatus.NORMALIZED;
        var cost = ProviderNumberParser.decimal(string(fields.get("cost")));
        var currency = string(fields.get("currency"));
        if (cost.missing() || cost.invalid() || currency == null || currency.isBlank()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_MONEY",
                    record.locator(), "cost",
                    "cost must be a valid decimal and currency must not be blank"));
            status = RawRecordNormalizeStatus.ERROR;
        }
        var builder = new NormalizedPayloadBuilder(SCHEMA_VARIANT, "COST")
                .dimension("model", fields.get("model"))
                .dimension("providerUser", fields.get("user_id"))
                .providerField("walletType", fields.get("wallet_type"));
        if (status == RawRecordNormalizeStatus.NORMALIZED) {
            builder.money("currency", currency)
                    .money("reportedAmount", cost.value());
        }
        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                new LinkedHashMap<>(fields), builder.build(),
                ProviderTimeParser.offsetInstant(string(fields.get("start_time_iso"))).orElse(null),
                ProviderTimeParser.offsetInstant(string(fields.get("end_time_iso"))).orElse(null),
                status, issues);
    }

    private static String classify(String entryName) {
        var base = entryName.substring(entryName.lastIndexOf('/') + 1);
        if (AMOUNT_ENTRY.matcher(base).matches()) {
            return AMOUNT_ROLE;
        }
        if (COST_ENTRY.matcher(base).matches()) {
            return COST_ROLE;
        }
        return null;
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
