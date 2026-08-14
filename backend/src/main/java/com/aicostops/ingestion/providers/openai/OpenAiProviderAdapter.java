package com.aicostops.ingestion.providers.openai;

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
import com.aicostops.ingestion.providers.common.ProviderTimeParser;
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
 * OpenAI platform adapter. Three source variants are dispatched by
 * {@link ImportSourceType} and schema inspection:
 *
 * <ul>
 *   <li>{@code openai.observed-empty-export.v1} — the observed four-column empty
 *       bucket CSV export under {@code FILE_EXPORT}. It deliberately does not claim
 *       a populated CSV metric schema; unknown columns WARN without upgrading the
 *       contract.</li>
 *   <li>{@code openai.organization-usage-completions-json.v1} — official Usage API
 *       JSON under {@code USAGE_API_JSON}.</li>
 *   <li>{@code openai.organization-costs-json.v1} — official Costs API JSON under
 *       {@code COSTS_API_JSON}.</li>
 * </ul>
 */
@Component
public final class OpenAiProviderAdapter implements ProviderAdapter {

    static final String PROVIDER_CODE = "OPENAI";
    static final String PARSER_VERSION = "openai-provider-import-v1";

    static final String OBSERVED_EMPTY_EXPORT = "openai.observed-empty-export.v1";
    static final String USAGE_JSON = "openai.organization-usage-completions-json.v1";
    static final String COSTS_JSON = "openai.organization-costs-json.v1";

    static final String EXPORT_ROLE = "export";
    private static final List<String> REQUIRED_EXPORT_HEADERS =
            List.of("start_time", "end_time", "start_time_iso", "end_time_iso");

    private static final Pattern USAGE_PREFIX = Pattern.compile("^completions_usage_.*\\.csv$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST_PREFIX = Pattern.compile("^cost_.*\\.csv$", Pattern.CASE_INSENSITIVE);

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
        return switch (input.sourceType()) {
            case FILE_EXPORT -> inspectObservedCsv(input);
            case USAGE_API_JSON -> incompatible(List.of(issue(ImportIssueSeverity.ERROR,
                    "WRONG_SOURCE_TYPE", input.originalFilename(), null,
                    "Observed OpenAI CSV exports require FILE_EXPORT; usage JSON is not yet supported here")));
            case COSTS_API_JSON -> incompatible(List.of(issue(ImportIssueSeverity.ERROR,
                    "WRONG_SOURCE_TYPE", input.originalFilename(), null,
                    "Observed OpenAI CSV exports require FILE_EXPORT; costs JSON is not yet supported here")));
        };
    }

    private InspectionResult inspectObservedCsv(ProviderInput input) {
        List<String> headers;
        try (var content = input.source().openStream()) {
            headers = CsvSupport.readHeader(content);
        } catch (IOException | CsvSupport.DuplicateCsvHeaderException failure) {
            return incompatible(List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_CSV",
                    input.originalFilename(), null, "Export is not a readable CSV")));
        }
        var issues = new ArrayList<ImportIssueDraft>();
        var actualNormalized = HeaderNormalizer.normalizeAll(headers);
        var requiredNormalized = HeaderNormalizer.normalizeAll(REQUIRED_EXPORT_HEADERS);
        for (var i = 0; i < requiredNormalized.size(); i++) {
            if (!actualNormalized.contains(requiredNormalized.get(i))) {
                issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_COLUMN",
                        "export.csv", REQUIRED_EXPORT_HEADERS.get(i),
                        "Required column '" + REQUIRED_EXPORT_HEADERS.get(i) + "' is missing"));
            }
        }
        for (var raw : headers) {
            var normalized = HeaderNormalizer.normalize(raw);
            if (!requiredNormalized.contains(normalized)) {
                issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_COLUMN",
                        "export.csv", raw, "Unknown extra column in observed empty export"));
            }
        }
        var fingerprint = SchemaFingerprint.sha256(new SchemaDescriptor(
                PROVIDER_CODE, ImportSourceType.FILE_EXPORT, OBSERVED_EMPTY_EXPORT,
                Map.of(EXPORT_ROLE, actualNormalized)));
        var compatible = issues.stream().noneMatch(i -> i.severity() == ImportIssueSeverity.ERROR);
        return new InspectionResult(PROVIDER_CODE, OBSERVED_EMPTY_EXPORT, fingerprint, compatible, issues);
    }

    @Override
    public void parse(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink) {
        var kind = classifyExportKind(input.originalFilename());
        var index = new int[1];
        try (var content = input.source().openStream()) {
            CsvSupport.forEachRecord(content, (rowNumber, values) -> {
                var record = new ParsedProviderRecord(index[0]++,
                        "export.csv:row:" + rowNumber, new LinkedHashMap<>(values));
                sink.accept(normalize(record, inspection, kind));
            });
        } catch (IOException failure) {
            throw new IllegalStateException("OpenAI export parse failed (category)", failure);
        }
    }

    @Override
    public NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection) {
        // Direct contract callers have no filename context; the ingestion pipeline
        // always classifies the export kind inside parse() and uses the overload.
        return normalize(record, inspection, ExportKind.UNKNOWN);
    }

    private NormalizedProviderRecord normalize(
            ParsedProviderRecord record, InspectionResult inspection, ExportKind kind) {
        var fields = record.fields();
        var issues = new ArrayList<ImportIssueDraft>();
        var status = RawRecordNormalizeStatus.NORMALIZED;
        var builder = new NormalizedPayloadBuilder(OBSERVED_EMPTY_EXPORT, kind.recordKind)
                .providerField("exportKind", kind.label);
        if (kind == ExportKind.UNKNOWN) {
            issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_EXPORT_KIND",
                    record.locator(), null,
                    "Observed empty export with unrecognized filename prefix"));
            status = RawRecordNormalizeStatus.WARN;
        }
        var start = ProviderTimeParser.epochSecond(fields.get("start_time"))
                .orElseGet(() -> ProviderTimeParser.offsetInstant(string(fields.get("start_time_iso"))).orElse(null));
        var end = ProviderTimeParser.epochSecond(fields.get("end_time"))
                .orElseGet(() -> ProviderTimeParser.offsetInstant(string(fields.get("end_time_iso"))).orElse(null));
        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                new LinkedHashMap<>(fields), builder.build(), start, end, status, issues);
    }

    /** Coarse export-kind hint derived only from the export filename prefix. */
    private static ExportKind classifyExportKind(String originalFilename) {
        var base = originalFilename == null ? "" : originalFilename;
        var slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (USAGE_PREFIX.matcher(base).matches()) {
            return ExportKind.USAGE;
        }
        if (COST_PREFIX.matcher(base).matches()) {
            return ExportKind.COST;
        }
        return ExportKind.UNKNOWN;
    }

    private enum ExportKind {
        USAGE("USAGE", "EMPTY_USAGE_BUCKET"),
        COST("COST", "EMPTY_COST_BUCKET"),
        UNKNOWN("UNKNOWN", "EMPTY_USAGE_BUCKET");

        private final String label;
        private final String recordKind;

        ExportKind(String label, String recordKind) {
            this.label = label;
            this.recordKind = recordKind;
        }
    }

    private static ImportIssueDraft issue(
            ImportIssueSeverity severity, String code, String locator, String fieldName, String message) {
        return new ImportIssueDraft(severity, code, locator, fieldName, message, null);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static InspectionResult incompatible(List<ImportIssueDraft> issues) {
        return new InspectionResult(PROVIDER_CODE, OBSERVED_EMPTY_EXPORT, "", false, issues);
    }
}
