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
import com.aicostops.ingestion.providers.common.ProviderNumberParser;
import com.aicostops.ingestion.providers.common.ProviderFieldLookup;
import com.aicostops.ingestion.providers.common.ProviderParserProperties;
import com.aicostops.ingestion.providers.common.ProviderTimeParser;
import com.aicostops.ingestion.providers.common.SchemaDescriptor;
import com.aicostops.ingestion.providers.common.SchemaFingerprint;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 *       JSON under {@code USAGE_API_JSON}. {@code input_tokens} / {@code output_tokens}
 *       are provider totals; cached / cache-write / uncached / text / audio / image
 *       values are breakdown components and are never added to form fake totals.</li>
 *   <li>{@code openai.organization-costs-json.v1} — official Costs API JSON under
 *       {@code COSTS_API_JSON}. Minimum money semantics are {@code amount.value} +
 *       {@code amount.currency}; {@code line_item} / {@code project_id} /
 *       {@code api_key_id} / {@code quantity} are optional provider dimensions and
 *       {@code quantity} is preserved provider-native without a guessed unit.</li>
 * </ul>
 *
 * <p>JSON parsing is bounded: inspection keeps only schema metadata (field-name set,
 * type markers, validation flags, issues) and parse materializes exactly one result
 * object at a time before normalizing and releasing it.
 */
@Component
public final class OpenAiProviderAdapter implements ProviderAdapter {

    public static final String PROVIDER_CODE = "OPENAI";
    public static final String PARSER_VERSION = "openai-provider-import-v1";

    public static final String OBSERVED_EMPTY_EXPORT = "openai.observed-empty-export.v1";
    public static final String USAGE_JSON = "openai.organization-usage-completions-json.v1";
    public static final String COSTS_JSON = "openai.organization-costs-json.v1";

    static final String EXPORT_ROLE = "export";
    private static final List<String> REQUIRED_EXPORT_HEADERS =
            List.of("start_time", "end_time", "start_time_iso", "end_time_iso");

    private static final Pattern USAGE_PREFIX = Pattern.compile("^completions_usage_.*\\.csv$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST_PREFIX = Pattern.compile("^cost_.*\\.csv$", Pattern.CASE_INSENSITIVE);

    // Official object/type markers (verified 2026-08-14).
    static final String ROOT_OBJECT = "page";
    static final String BUCKET_OBJECT = "bucket";
    static final String USAGE_RESULT_OBJECT = "organization.usage.completions.result";
    static final String COSTS_RESULT_OBJECT = "organization.costs.result";

    private static final List<String> USAGE_RECOGNIZED_FIELDS = List.of(
            "object",
            "input_tokens", "output_tokens",
            "input_cached_tokens", "input_cache_write_tokens", "input_uncached_tokens",
            "input_text_tokens", "input_audio_tokens", "input_image_tokens",
            "input_cached_text_tokens", "input_cached_audio_tokens", "input_cached_image_tokens",
            "output_text_tokens", "output_audio_tokens", "output_image_tokens",
            "num_model_requests",
            "project_id", "user_id", "api_key_id", "model", "batch", "service_tier");
    private static final List<String> USAGE_REQUIRED_FIELDS = List.of(
            "input_tokens", "output_tokens", "num_model_requests");
    private static final List<String> USAGE_OPTIONAL_INTEGRALS = List.of(
            "input_cached_tokens", "input_cache_write_tokens", "input_uncached_tokens",
            "input_text_tokens", "input_audio_tokens", "input_image_tokens",
            "input_cached_text_tokens", "input_cached_audio_tokens", "input_cached_image_tokens",
            "output_text_tokens", "output_audio_tokens", "output_image_tokens");

    private static final List<String> COSTS_RECOGNIZED_FIELDS = List.of(
            "object", "amount", "api_key_id", "line_item", "project_id", "quantity");
    private static final List<String> COSTS_REQUIRED_FIELDS = List.of("amount");

    private final ObjectMapper objectMapper;
    private final ProviderParserProperties properties;

    public OpenAiProviderAdapter(ObjectMapper objectMapper, ProviderParserProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
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
        return switch (input.sourceType()) {
            case FILE_EXPORT -> inspectObservedCsv(input);
            case USAGE_API_JSON -> inspectJsonPage(input, USAGE_JSON, true);
            case COSTS_API_JSON -> inspectJsonPage(input, COSTS_JSON, false);
            case COST_EXPORT -> throw new IllegalStateException(
                    "COST_EXPORT is a persisted UAT batch type and never reaches inspection");
        };
    }

    // ------------------------------------------------------------------
    // Part A: observed empty CSV export
    // ------------------------------------------------------------------

    private InspectionResult inspectObservedCsv(ProviderInput input) {
        List<String> headers;
        try (var content = input.source().openStream()) {
            headers = CsvSupport.readHeader(content);
        } catch (IOException | CsvSupport.DuplicateCsvHeaderException failure) {
            var code = failure instanceof CsvSupport.DuplicateCsvHeaderException
                    ? "DUPLICATE_COLUMN" : "MALFORMED_CSV";
            return incompatible(OBSERVED_EMPTY_EXPORT,
                    List.of(issue(ImportIssueSeverity.ERROR, code,
                            input.originalFilename(), null, "Export CSV header is invalid")));
        }
        var issues = new ArrayList<ImportIssueDraft>();
        var actualNormalized = HeaderNormalizer.normalizeAll(headers);
        var requiredNormalized = HeaderNormalizer.normalizeAll(REQUIRED_EXPORT_HEADERS);
        for (var duplicate : HeaderNormalizer.duplicateNormalizedHeaders(headers)) {
            issues.add(issue(ImportIssueSeverity.ERROR, "DUPLICATE_COLUMN",
                    "export.csv", duplicate, "Observed export contains duplicate normalized columns"));
        }
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

    private void parseObservedCsv(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink) {
        var kind = classifyExportKind(input.originalFilename());
        var index = new int[1];
        try (var content = input.source().openStream()) {
            CsvSupport.forEachRecord(content, (rowNumber, values) -> {
                var record = new ParsedProviderRecord(index[0]++,
                        "export.csv:row:" + rowNumber, new LinkedHashMap<>(values));
                sink.accept(normalizeObservedCsv(record, kind));
            });
        } catch (IOException failure) {
            throw new IllegalStateException("OpenAI export parse failed (category)", failure);
        }
    }

    private NormalizedProviderRecord normalizeObservedCsv(ParsedProviderRecord record, ExportKind kind) {
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
        var start = ProviderTimeParser.epochSecond(ProviderFieldLookup.get(fields, "start_time"))
                .orElseGet(() -> ProviderTimeParser.offsetInstant(
                        string(ProviderFieldLookup.get(fields, "start_time_iso"))).orElse(null));
        var end = ProviderTimeParser.epochSecond(ProviderFieldLookup.get(fields, "end_time"))
                .orElseGet(() -> ProviderTimeParser.offsetInstant(
                        string(ProviderFieldLookup.get(fields, "end_time_iso"))).orElse(null));
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

    // ------------------------------------------------------------------
    // Parts B/C: official Usage and Costs JSON (bounded streaming)
    // ------------------------------------------------------------------

    private InspectionResult inspectJsonPage(ProviderInput input, String variant, boolean usage) {
        var required = usage ? USAGE_REQUIRED_FIELDS : COSTS_REQUIRED_FIELDS;
        var recognized = usage ? USAGE_RECOGNIZED_FIELDS : COSTS_RECOGNIZED_FIELDS;
        var expectedResultObject = usage ? USAGE_RESULT_OBJECT : COSTS_RESULT_OBJECT;
        var sourceType = usage ? ImportSourceType.USAGE_API_JSON : ImportSourceType.COSTS_API_JSON;
        try (var content = input.source().openStream()) {
            var accumulator = new PageAccumulator(usage, required, recognized, expectedResultObject,
                    properties.maxInspectionIssues(), properties.maxJsonSchemaFields());
            scanPage(content, accumulator);
            if (accumulator.bucketLimitExceeded) {
                return incompatible(variant, List.of(issue(ImportIssueSeverity.ERROR,
                        "TOO_MANY_JSON_BUCKETS", input.originalFilename(), null,
                        "Official API page exceeds the parser bucket limit")));
            }
            if (accumulator.schemaFieldsExceeded) {
                var issues = new ArrayList<ImportIssueDraft>(accumulator.issues);
                issues.add(issue(ImportIssueSeverity.ERROR, "TOO_MANY_JSON_SCHEMA_FIELDS",
                        input.originalFilename(), null,
                        "Official API page exceeds the parser schema-field limit"));
                return new InspectionResult(PROVIDER_CODE, variant, "", false, issues);
            }
            if (!accumulator.shapeValid) {
                return incompatible(variant, List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_JSON",
                        input.originalFilename(), null,
                        "Payload does not match the official page/bucket/result shape")));
            }
            var roles = new LinkedHashMap<String, List<String>>();
            roles.put("result", List.copyOf(accumulator.allKeys));
            roles.put("object", List.of(ROOT_OBJECT, BUCKET_OBJECT, expectedResultObject));
            var fingerprint = SchemaFingerprint.sha256(new SchemaDescriptor(
                    PROVIDER_CODE, sourceType, variant, roles));
            return new InspectionResult(PROVIDER_CODE, variant, fingerprint,
                    accumulator.compatible(), accumulator.issues);
        } catch (IOException | JacksonException failure) {
            return incompatible(variant, List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_JSON",
                    input.originalFilename(), null, "Payload is not a readable official API page")));
        }
    }

    /**
     * Streaming page walk that keeps only schema metadata. Results are inspected and
     * discarded immediately; no {@code JsonNode} of a result survives the walk.
     */
    private void scanPage(InputStream content, PageAccumulator accumulator) throws IOException {
        try (var parser = objectMapper.createParser(content)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                accumulator.shapeValid = false;
                return;
            }
            var rootObjectSeen = false;
            var foundData = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                var field = parser.currentName();
                if ("data".equals(field)) {
                    foundData = true;
                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        accumulator.shapeValid = false;
                        return;
                    }
                    var bucketIndex = 0;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() != JsonToken.START_OBJECT) {
                            accumulator.shapeValid = false;
                            return;
                        }
                        accumulator.bucketCount++;
                        if (accumulator.bucketCount > properties.maxJsonBuckets()) {
                            // Stop traversal immediately; the remaining payload is
                            // never read, so a hostile tail cannot force more work.
                            accumulator.bucketLimitExceeded = true;
                            return;
                        }
                        scanBucket(parser, bucketIndex, accumulator);
                        bucketIndex++;
                    }
                } else if ("object".equals(field)) {
                    if (parser.nextToken() != JsonToken.VALUE_STRING
                            || !ROOT_OBJECT.equals(parser.getText())) {
                        accumulator.shapeValid = false;
                        return;
                    }
                    rootObjectSeen = true;
                } else {
                    parser.skipChildren();
                }
            }
            if (!rootObjectSeen || !foundData) {
                accumulator.shapeValid = false;
            }
        }
    }

    private void scanBucket(JsonParser parser, int bucketIndex, PageAccumulator accumulator) throws IOException {
        var bucketObjectSeen = false;
        var startTimeSeen = false;
        var endTimeSeen = false;
        var resultsSeen = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            var field = parser.currentName();
            if ("object".equals(field)) {
                if (parser.nextToken() != JsonToken.VALUE_STRING
                        || !BUCKET_OBJECT.equals(parser.getText())) {
                    accumulator.shapeValid = false;
                    return;
                }
                bucketObjectSeen = true;
            } else if ("start_time".equals(field)) {
                if (parser.nextToken() != JsonToken.VALUE_NUMBER_INT) {
                    accumulator.shapeValid = false;
                    return;
                }
                startTimeSeen = true;
            } else if ("end_time".equals(field)) {
                if (parser.nextToken() != JsonToken.VALUE_NUMBER_INT) {
                    accumulator.shapeValid = false;
                    return;
                }
                endTimeSeen = true;
            } else if ("results".equals(field)) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    accumulator.shapeValid = false;
                    return;
                }
                resultsSeen = true;
                var resultIndex = 0;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        accumulator.shapeValid = false;
                        return;
                    }
                    scanResult(parser, "data[" + bucketIndex + "].results[" + resultIndex + "]",
                            accumulator);
                    resultIndex++;
                }
            } else {
                parser.skipChildren();
            }
        }
        // Every bucket must carry the marker, integral epoch bounds and a results
        // array; an empty results array still requires valid times.
        if (!bucketObjectSeen || !startTimeSeen || !endTimeSeen || !resultsSeen) {
            accumulator.shapeValid = false;
        }
    }

    private void scanResult(JsonParser parser, String locator, PageAccumulator accumulator) throws IOException {
        var result = (JsonNode) parser.readValueAsTree();
        if (!result.isObject()) {
            accumulator.shapeValid = false;
            return;
        }
        var keys = new TreeSet<String>();
        result.propertyNames().forEach(keys::add);
        for (var key : keys) {
            if (accumulator.allKeys.size() >= accumulator.maxSchemaFields) {
                accumulator.schemaFieldsExceeded = true;
                return;
            }
            accumulator.allKeys.add(key);
        }

        var resultObject = result.path("object").asText("");
        if (!accumulator.expectedResultObject.equals(resultObject)) {
            accumulator.add(issue(ImportIssueSeverity.ERROR, "MALFORMED_JSON",
                    locator, "object",
                    "Result object marker must be '" + accumulator.expectedResultObject + "'"));
        }
        for (var field : accumulator.required) {
            var node = result.get(field);
            if (node == null || node.isNull()) {
                accumulator.addDeduped(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_FIELD",
                        locator, field, "Required result field '" + field + "' is missing or null");
            }
        }
        if (!accumulator.usage) {
            var amount = result.get("amount");
            if (amount == null || !amount.isObject()
                    || amount.get("value") == null || amount.get("value").isNull()
                    || !amount.get("value").isNumber()
                    || amount.get("currency") == null || amount.get("currency").isNull()
                    || !amount.get("currency").isTextual()
                    || amount.get("currency").asText().isBlank()) {
                accumulator.addDeduped(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_FIELD",
                        locator, "amount.value",
                        "amount.value must be a number and amount.currency must be non-blank text");
            }
        }
        for (var key : keys) {
            if (!accumulator.recognized.contains(key)) {
                accumulator.reportUnknownField(key, locator);
            }
        }
        for (var field : accumulator.recognized) {
            if (!accumulator.required.contains(field) && !keys.contains(field)) {
                accumulator.reportOptionalMissing(field, locator);
            }
        }
        // The result node is discarded here; only schema metadata accumulates.
    }

    /**
     * Schema-only page state; never holds result values. Issue collection is
     * bounded: compatibility is tracked independently of the capped sample list, so
     * a truncated page still fails closed when any sampled issue was an ERROR.
     */
    private static final class PageAccumulator {
        static final String TRUNCATED_CODE = "INSPECTION_ISSUES_TRUNCATED";

        private final boolean usage;
        private final List<String> required;
        private final List<String> recognized;
        private final String expectedResultObject;
        private final int maxIssues;
        private final int maxSchemaFields;
        private final Set<String> allKeys = new TreeSet<>();
        private final List<ImportIssueDraft> issues = new ArrayList<>();
        private final Set<String> reportedSchemaIssues = new TreeSet<>();
        private final Set<String> reportedUnknownFields = new TreeSet<>();
        private final Set<String> reportedOptionalMissing = new TreeSet<>();
        private boolean shapeValid = true;
        private boolean sawError;
        private int bucketCount;
        private boolean truncated;
        private boolean bucketLimitExceeded;
        private boolean schemaFieldsExceeded;

        private PageAccumulator(
                boolean usage, List<String> required, List<String> recognized,
                String expectedResultObject, int maxIssues, int maxSchemaFields) {
            this.usage = usage;
            this.required = required;
            this.recognized = recognized;
            this.expectedResultObject = expectedResultObject;
            this.maxIssues = maxIssues;
            this.maxSchemaFields = maxSchemaFields;
        }

        void add(ImportIssueDraft draft) {
            if (draft.severity() == ImportIssueSeverity.ERROR) {
                sawError = true;
            }
            if (truncated) {
                return;
            }
            if (issues.size() >= maxIssues) {
                truncated = true;
                issues.add(issue(ImportIssueSeverity.WARN, TRUNCATED_CODE,
                        "page", null, "Inspection issue collection truncated at " + maxIssues));
                return;
            }
            issues.add(draft);
        }

        /** Schema-class issues are reported once per (code, fieldName). */
        void addDeduped(ImportIssueSeverity severity, String code, String locator, String fieldName, String message) {
            if (reportedSchemaIssues.add(code + ":" + fieldName)) {
                add(issue(severity, code, locator, fieldName, message));
            } else if (severity == ImportIssueSeverity.ERROR) {
                sawError = true;
            }
        }

        /** Unknown-field reporting is capped so unique keys cannot grow unbounded. */
        void reportUnknownField(String key, String locator) {
            if (reportedUnknownFields.size() >= maxIssues) {
                return;
            }
            if (reportedUnknownFields.add(key)) {
                add(issue(ImportIssueSeverity.WARN, "UNKNOWN_FIELD", locator, key,
                        "Unknown extra result field"));
            }
        }

        void reportOptionalMissing(String field, String locator) {
            if (reportedOptionalMissing.size() >= maxIssues) {
                return;
            }
            if (reportedOptionalMissing.add(field)) {
                add(issue(ImportIssueSeverity.WARN, "MISSING_OPTIONAL_FIELD", locator, field,
                        "Recognized optional result field is missing"));
            }
        }

        boolean compatible() {
            return shapeValid && !sawError;
        }
    }

    @Override
    public void parse(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink) {
        switch (inspection.schemaVariant()) {
            case OBSERVED_EMPTY_EXPORT -> parseObservedCsv(input, inspection, sink);
            case USAGE_JSON -> parseJsonResults(input, inspection, sink, true);
            case COSTS_JSON -> parseJsonResults(input, inspection, sink, false);
            default -> throw new IllegalStateException("Unsupported OpenAI schema variant");
        }
    }

    /**
     * Bounded two-pass parse: Pass A collects only per-bucket epoch windows (never
     * results), Pass B re-opens the source and streams one result at a time using the
     * window of its bucket. JSON property order cannot change semantics because
     * bucket times are resolved before any result is normalized.
     */
    private void parseJsonResults(
            ProviderInput input, InspectionResult inspection, ProviderRecordSink sink, boolean usage) {
        var windows = new ArrayList<BucketWindow>();
        try (var content = input.source().openStream()) {
            collectBucketWindows(content, windows);
        } catch (IOException | JacksonException failure) {
            throw new IllegalStateException("OpenAI JSON parse failed (category)", failure);
        }
        var index = new AtomicInteger();
        try (var content = input.source().openStream();
                var parser = objectMapper.createParser(content)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("Official API page must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!"data".equals(parser.currentName())) {
                    parser.skipChildren();
                    continue;
                }
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("Official API page data must be an array");
                }
                var bucketIndex = 0;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    var window = windows.get(bucketIndex);
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        if ("results".equals(parser.currentName())) {
                            if (parser.nextToken() != JsonToken.START_ARRAY) {
                                throw new IllegalStateException("Official API bucket must contain a results array");
                            }
                            var resultIndex = 0;
                            while (parser.nextToken() != JsonToken.END_ARRAY) {
                                var result = (JsonNode) parser.readValueAsTree();
                                var fields = toMap(result);
                                var record = new ParsedProviderRecord(index.getAndIncrement(),
                                        "data[" + bucketIndex + "].results[" + resultIndex + "]", fields);
                                sink.accept(normalizeJson(record, inspection, usage,
                                        window.startInstant(), window.endInstant()));
                                resultIndex++;
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    bucketIndex++;
                }
            }
        } catch (IOException | JacksonException failure) {
            throw new IllegalStateException("OpenAI JSON parse failed (category)", failure);
        }
    }

    /** Pass A: bucket metadata only; results are skipped without materialization. */
    private void collectBucketWindows(InputStream content, List<BucketWindow> windows) throws IOException {
        try (var parser = objectMapper.createParser(content)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("Official API page must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!"data".equals(parser.currentName())) {
                    parser.skipChildren();
                    continue;
                }
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("Official API page data must be an array");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    Long start = null;
                    Long end = null;
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        var field = parser.currentName();
                        if ("start_time".equals(field)) {
                            if (parser.nextToken() == JsonToken.VALUE_NUMBER_INT) {
                                start = parser.getLongValue();
                            }
                        } else if ("end_time".equals(field)) {
                            if (parser.nextToken() == JsonToken.VALUE_NUMBER_INT) {
                                end = parser.getLongValue();
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    if (start == null || end == null) {
                        throw new IllegalStateException("Official API bucket must have integral epoch bounds");
                    }
                    windows.add(new BucketWindow(start, end));
                    if (windows.size() > properties.maxJsonBuckets()) {
                        throw new IllegalStateException("Official API page exceeds the parser bucket limit");
                    }
                }
            }
        }
    }

    /** One bucket's validated epoch window; bounded by max-json-buckets. */
    private record BucketWindow(long start, long end) {
        Instant startInstant() {
            return Instant.ofEpochSecond(start);
        }

        Instant endInstant() {
            return Instant.ofEpochSecond(end);
        }
    }

    private NormalizedProviderRecord normalizeJson(
            ParsedProviderRecord record, InspectionResult inspection, boolean usage,
            Instant bucketStart, Instant bucketEnd) {
        var fields = record.fields();
        var issues = new ArrayList<ImportIssueDraft>();
        var status = RawRecordNormalizeStatus.NORMALIZED;
        if (usage) {
            return normalizeUsageResult(record, fields, issues, status, bucketStart, bucketEnd);
        }
        return normalizeCostsResult(record, fields, issues, status, bucketStart, bucketEnd);
    }

    private NormalizedProviderRecord normalizeUsageResult(
            ParsedProviderRecord record, Map<String, Object> fields, List<ImportIssueDraft> issues,
            RawRecordNormalizeStatus status, Instant bucketStart, Instant bucketEnd) {
        var inputTokens = requiredIntegral(fields, "input_tokens");
        var outputTokens = requiredIntegral(fields, "output_tokens");
        var requests = requiredIntegral(fields, "num_model_requests");
        if (inputTokens.invalid() || outputTokens.invalid() || requests.invalid()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_NUMBER",
                    record.locator(), "input_tokens",
                    "input_tokens, output_tokens and num_model_requests must be present integral numbers"));
            status = RawRecordNormalizeStatus.ERROR;
        }
        var optional = new LinkedHashMap<String, ProviderNumberParser.ParsedValue<Long>>();
        for (var field : USAGE_OPTIONAL_INTEGRALS) {
            var parsed = optionalIntegral(fields, field);
            optional.put(field, parsed);
            if (parsed.invalid()) {
                issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_OPTIONAL_NUMBER",
                        record.locator(), field,
                        "Optional token breakdown '" + field + "' must be an integral number when present"));
                status = RawRecordNormalizeStatus.ERROR;
            }
        }
        if (bucketStart == null || bucketEnd == null) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_BUCKET_TIME",
                    record.locator(), "start_time",
                    "Official API bucket must have integral start_time and end_time"));
            status = RawRecordNormalizeStatus.ERROR;
        }

        var builder = new NormalizedPayloadBuilder(USAGE_JSON, "USAGE")
                .dimension("model", string(fields.get("model")))
                .dimension("providerUser", string(fields.get("user_id")))
                .dimension("providerProject", string(fields.get("project_id")))
                .dimension("credentialId", string(fields.get("api_key_id")))
                .providerField("batch", fields.get("batch"))
                .providerField("serviceTier", string(fields.get("service_tier")));
        if (status == RawRecordNormalizeStatus.NORMALIZED) {
            builder.usage("inputTokens", inputTokens.value())
                    .usage("outputTokens", outputTokens.value())
                    .usage("numModelRequests", requests.value());
            for (var entry : optional.entrySet()) {
                if (entry.getValue().valid()) {
                    builder.usage(optionalKey(entry.getKey()), entry.getValue().value());
                }
            }
        }
        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                new LinkedHashMap<>(fields), builder.build(), bucketStart, bucketEnd, status, issues);
    }

    private NormalizedProviderRecord normalizeCostsResult(
            ParsedProviderRecord record, Map<String, Object> fields, List<ImportIssueDraft> issues,
            RawRecordNormalizeStatus status, Instant bucketStart, Instant bucketEnd) {
        var amountNode = fields.get("amount");
        var amount = amountNode instanceof Map<?, ?> map
                ? castStringMap(map) : Map.<String, Object>of();
        var value = ProviderNumberParser.decimal(string(amount.get("value")));
        var currency = string(amount.get("currency"));
        if (amount.isEmpty() || value.missing() || value.invalid()
                || currency == null || currency.isBlank()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_MONEY",
                    record.locator(), "amount.value",
                    "amount.value must be a present decimal and amount.currency must be non-blank"));
            status = RawRecordNormalizeStatus.ERROR;
        }
        if (bucketStart == null || bucketEnd == null) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_BUCKET_TIME",
                    record.locator(), "start_time",
                    "Official API bucket must have integral start_time and end_time"));
            status = RawRecordNormalizeStatus.ERROR;
        }

        var builder = new NormalizedPayloadBuilder(COSTS_JSON, "COST")
                .dimension("providerProject", string(fields.get("project_id")))
                .dimension("credentialId", string(fields.get("api_key_id")))
                .providerField("lineItem", string(fields.get("line_item")))
                .providerField("quantity", fields.get("quantity"));
        if (status == RawRecordNormalizeStatus.NORMALIZED) {
            builder.money("currency", currency).money("reportedAmount", value.value());
        }
        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                new LinkedHashMap<>(fields), builder.build(), bucketStart, bucketEnd, status, issues);
    }

    private static ProviderNumberParser.ParsedValue<Long> requiredIntegral(
            Map<String, Object> fields, String key) {
        if (!fields.containsKey(key) || fields.get(key) == null) {
            return new ProviderNumberParser.ParsedValue<>(null, true, false);
        }
        return integral(fields.get(key));
    }

    /** Missing key -> optional omission; present-but-null or malformed -> invalid. */
    private static ProviderNumberParser.ParsedValue<Long> optionalIntegral(
            Map<String, Object> fields, String key) {
        if (!fields.containsKey(key)) {
            return new ProviderNumberParser.ParsedValue<>(null, false, true);
        }
        if (fields.get(key) == null) {
            return new ProviderNumberParser.ParsedValue<>(null, true, false);
        }
        return integral(fields.get(key));
    }

    private static ProviderNumberParser.ParsedValue<Long> integral(Object value) {
        if (value instanceof Long longValue) {
            return new ProviderNumberParser.ParsedValue<>(longValue, true, true);
        }
        if (value instanceof Integer integer) {
            return new ProviderNumberParser.ParsedValue<>(integer.longValue(), true, true);
        }
        if (value instanceof Number number && (number instanceof Double || number instanceof Float)) {
            return new ProviderNumberParser.ParsedValue<>(null, true, false);
        }
        if (value == null) {
            return new ProviderNumberParser.ParsedValue<>(null, false, true);
        }
        return ProviderNumberParser.longValue(String.valueOf(value));
    }

    private static String optionalKey(String snakeCase) {
        var parts = snakeCase.split("_");
        var builder = new StringBuilder(parts[0]);
        for (var i = 1; i < parts.length; i++) {
            builder.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return builder.toString();
    }

    private static Map<String, Object> toMap(JsonNode node) {
        var map = new LinkedHashMap<String, Object>();
        for (var entry : node.properties()) {
            map.put(entry.getKey(), toPlain(entry.getValue()));
        }
        return map;
    }

    private static Object toPlain(JsonNode node) {
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isArray()) {
            var list = new ArrayList<Object>();
            for (var item : node) {
                list.add(toPlain(item));
            }
            return list;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static Map<String, Object> castStringMap(Map<?, ?> source) {
        var target = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            target.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return target;
    }

    @Override
    public NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection) {
        // Direct contract callers have no file/bucket context; the ingestion pipeline
        // always routes through parse(), which supplies the variant-specific context.
        return switch (inspection.schemaVariant()) {
            case OBSERVED_EMPTY_EXPORT -> normalizeObservedCsv(record, ExportKind.UNKNOWN);
            case USAGE_JSON -> normalizeJson(record, inspection, true, null, null);
            case COSTS_JSON -> normalizeJson(record, inspection, false, null, null);
            default -> throw new IllegalStateException("Unsupported OpenAI schema variant");
        };
    }

    private static ImportIssueDraft issue(
            ImportIssueSeverity severity, String code, String locator, String fieldName, String message) {
        return new ImportIssueDraft(severity, code, locator, fieldName, message, null);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static InspectionResult incompatible(String variant, List<ImportIssueDraft> issues) {
        return new InspectionResult(PROVIDER_CODE, variant, "", false, issues);
    }
}
