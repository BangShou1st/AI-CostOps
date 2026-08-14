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
 *       JSON under {@code USAGE_API_JSON}. Cached tokens are a breakdown of input
 *       tokens and are never added to form a fake total.</li>
 *   <li>{@code openai.organization-costs-json.v1} — official Costs API JSON under
 *       {@code COSTS_API_JSON}. Only the current official fields
 *       ({@code amount.value}, {@code amount.currency}, {@code line_item},
 *       {@code project_id}) are mapped; stale {@code api_key_id} / {@code quantity}
 *       assumptions are absent.</li>
 * </ul>
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

    private static final List<String> USAGE_RECOGNIZED_FIELDS = List.of(
            "input_tokens", "output_tokens", "input_cached_tokens", "input_audio_tokens",
            "output_audio_tokens", "num_model_requests", "project_id", "user_id",
            "api_key_id", "model", "batch", "service_tier");
    private static final List<String> USAGE_REQUIRED_FIELDS = List.of(
            "input_tokens", "output_tokens", "num_model_requests");

    private static final List<String> COSTS_RECOGNIZED_FIELDS = List.of(
            "amount", "line_item", "project_id");
    private static final List<String> COSTS_REQUIRED_FIELDS = List.of(
            "amount", "line_item", "project_id");

    private final ObjectMapper objectMapper;

    public OpenAiProviderAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
            return incompatible(OBSERVED_EMPTY_EXPORT,
                    List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_CSV",
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

    // ------------------------------------------------------------------
    // Parts B/C: official Usage and Costs JSON
    // ------------------------------------------------------------------

    private InspectionResult inspectJsonPage(ProviderInput input, String variant, boolean usage) {
        var required = usage ? USAGE_REQUIRED_FIELDS : COSTS_REQUIRED_FIELDS;
        var recognized = usage ? USAGE_RECOGNIZED_FIELDS : COSTS_RECOGNIZED_FIELDS;
        var sourceType = usage ? ImportSourceType.USAGE_API_JSON : ImportSourceType.COSTS_API_JSON;
        PageShape shape;
        try (var content = input.source().openStream()) {
            shape = scanPage(content);
        } catch (IOException | JacksonException failure) {
            return incompatible(variant, List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_JSON",
                    input.originalFilename(), null, "Payload is not a readable official API page")));
        }
        if (shape == null) {
            return incompatible(variant, List.of(issue(ImportIssueSeverity.ERROR, "MALFORMED_JSON",
                    input.originalFilename(), null,
                    "Payload does not match the official page/bucket/result shape")));
        }
        var issues = new ArrayList<ImportIssueDraft>();
        for (var result : shape.results) {
            var keys = result.keys();
            for (var field : required) {
                if (!keys.contains(field)) {
                    issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_FIELD",
                            result.locator, field, "Required result field '" + field + "' is missing"));
                }
            }
            if (!usage) {
                var amount = result.node.get("amount");
                if (amount == null || !amount.isObject()
                        || !amount.has("value") || !amount.has("currency")) {
                    issues.add(issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_FIELD",
                            result.locator, "amount.value",
                            "Required result field 'amount.value/amount.currency' is missing"));
                }
            }
            for (var key : keys) {
                if (!recognized.contains(key)) {
                    issues.add(issue(ImportIssueSeverity.WARN, "UNKNOWN_FIELD",
                            result.locator, key, "Unknown extra result field"));
                }
            }
            for (var field : recognized) {
                if (!required.contains(field) && !keys.contains(field)) {
                    issues.add(issue(ImportIssueSeverity.WARN, "MISSING_OPTIONAL_FIELD",
                            result.locator, field, "Recognized optional result field is missing"));
                }
            }
        }
        var fingerprint = SchemaFingerprint.sha256(new SchemaDescriptor(
                PROVIDER_CODE, sourceType, variant, Map.of("result", List.copyOf(shape.allKeys))));
        var compatible = issues.stream().noneMatch(i -> i.severity() == ImportIssueSeverity.ERROR);
        return new InspectionResult(PROVIDER_CODE, variant, fingerprint, compatible, issues);
    }

    private void parseJsonResults(
            ProviderInput input, InspectionResult inspection, ProviderRecordSink sink, boolean usage) {
        var index = new AtomicInteger();
        try (var content = input.source().openStream()) {
            var parser = objectMapper.createParser(content);
            try {
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
                        var bucket = (JsonNode) parser.readValueAsTree();
                        if (!bucket.isObject()) {
                            throw new IllegalStateException("Official API bucket must be an object");
                        }
                        var start = epochOf(bucket.get("start_time"));
                        var end = epochOf(bucket.get("end_time"));
                        var results = bucket.get("results");
                        if (results == null || !results.isArray()) {
                            throw new IllegalStateException("Official API bucket must contain a results array");
                        }
                        var resultIndex = 0;
                        for (var result : results) {
                            var fields = toMap(result);
                            var record = new ParsedProviderRecord(index.getAndIncrement(),
                                    "data[" + bucketIndex + "].results[" + resultIndex + "]", fields);
                            sink.accept(normalizeJson(record, inspection, usage, start, end));
                            resultIndex++;
                        }
                        bucketIndex++;
                    }
                }
            } finally {
                parser.close();
            }
        } catch (IOException | JacksonException failure) {
            throw new IllegalStateException("OpenAI JSON parse failed (category)", failure);
        }
    }

    private NormalizedProviderRecord normalizeJson(
            ParsedProviderRecord record, InspectionResult inspection, boolean usage,
            Instant bucketStart, Instant bucketEnd) {
        var fields = record.fields();
        var issues = new ArrayList<ImportIssueDraft>();
        var status = RawRecordNormalizeStatus.NORMALIZED;
        if (usage) {
            var inputTokens = integral(fields.get("input_tokens"));
            var outputTokens = integral(fields.get("output_tokens"));
            var cached = integral(fields.get("input_cached_tokens"));
            var audioIn = integral(fields.get("input_audio_tokens"));
            var audioOut = integral(fields.get("output_audio_tokens"));
            var requests = integral(fields.get("num_model_requests"));
            if (inputTokens.invalid() || outputTokens.invalid() || requests.invalid()
                    || cached.invalid() || audioIn.invalid() || audioOut.invalid()) {
                issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_NUMBER",
                        record.locator(), "input_tokens",
                        "Token and request metrics must be integral numbers"));
                status = RawRecordNormalizeStatus.ERROR;
            }
            var builder = new NormalizedPayloadBuilder(USAGE_JSON, "USAGE")
                    .dimension("model", string(fields.get("model")))
                    .dimension("providerUser", string(fields.get("user_id")))
                    .dimension("providerProject", string(fields.get("project_id")))
                    .providerField("apiKeyId", string(fields.get("api_key_id")))
                    .providerField("batch", fields.get("batch"))
                    .providerField("serviceTier", string(fields.get("service_tier")));
            if (status == RawRecordNormalizeStatus.NORMALIZED) {
                builder.usage("inputTokens", inputTokens.value())
                        .usage("outputTokens", outputTokens.value())
                        .usage("inputCachedTokens", cached.value())
                        .usage("inputAudioTokens", audioIn.value())
                        .usage("outputAudioTokens", audioOut.value())
                        .usage("numModelRequests", requests.value());
            }
            return new NormalizedProviderRecord(record.index(), record.locator(), null,
                    new LinkedHashMap<>(fields), builder.build(), bucketStart, bucketEnd, status, issues);
        }
        var amountNode = fields.get("amount");
        var amount = amountNode instanceof Map<?, ?> map
                ? castStringMap(map) : Map.<String, Object>of();
        var value = ProviderNumberParser.decimal(string(amount.get("value")));
        var currency = string(amount.get("currency"));
        if (value.invalid() || currency == null || currency.isBlank()) {
            issues.add(issue(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_MONEY",
                    record.locator(), "amount.value",
                    "amount.value must be a valid decimal and amount.currency must not be blank"));
            status = RawRecordNormalizeStatus.ERROR;
        }
        var builder = new NormalizedPayloadBuilder(COSTS_JSON, "COST")
                .dimension("providerProject", string(fields.get("project_id")))
                .providerField("lineItem", string(fields.get("line_item")));
        if (status == RawRecordNormalizeStatus.NORMALIZED) {
            builder.money("currency", currency).money("reportedAmount", value.value());
        }
        return new NormalizedProviderRecord(record.index(), record.locator(), null,
                new LinkedHashMap<>(fields), builder.build(), bucketStart, bucketEnd, status, issues);
    }

    private PageShape scanPage(InputStream content) throws IOException {
        var parser = objectMapper.createParser(content);
        try {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            var allKeys = new TreeSet<String>();
            var results = new ArrayList<ResultRef>();
            var foundData = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!"data".equals(parser.currentName())) {
                    parser.skipChildren();
                    continue;
                }
                foundData = true;
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    return null;
                }
                var bucketIndex = 0;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        return null;
                    }
                    var bucket = (JsonNode) parser.readValueAsTree();
                    if (!bucket.isObject()) {
                        return null;
                    }
                    var resultsNode = bucket.get("results");
                    if (resultsNode == null || !resultsNode.isArray()) {
                        return null;
                    }
                    var resultIndex = 0;
                    for (var result : resultsNode) {
                        if (!result.isObject()) {
                            return null;
                        }
                        allKeys.addAll(result.propertyNames());
                        results.add(new ResultRef("data[" + bucketIndex + "].results[" + resultIndex + "]",
                                result));
                        resultIndex++;
                    }
                    bucketIndex++;
                }
            }
            if (!foundData) {
                return null;
            }
            return new PageShape(results, List.copyOf(allKeys));
        } finally {
            parser.close();
        }
    }

    private record PageShape(List<ResultRef> results, List<String> allKeys) {
    }

    private record ResultRef(String locator, JsonNode node) {

        Set<String> keys() {
            return new TreeSet<>(node.propertyNames());
        }
    }

    // ------------------------------------------------------------------
    // scalar conversion helpers
    // ------------------------------------------------------------------

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

    private static Instant epochOf(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        return Instant.ofEpochSecond(node.longValue());
    }

    private static Map<String, Object> castStringMap(Map<?, ?> source) {
        var target = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            target.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return target;
    }

    // ------------------------------------------------------------------
    // contract plumbing
    // ------------------------------------------------------------------

    @Override
    public void parse(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink) {
        switch (inspection.schemaVariant()) {
            case OBSERVED_EMPTY_EXPORT -> parseObservedCsv(input, inspection, sink);
            case USAGE_JSON -> parseJsonResults(input, inspection, sink, true);
            case COSTS_JSON -> parseJsonResults(input, inspection, sink, false);
            default -> throw new IllegalStateException("Unsupported OpenAI schema variant");
        }
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
