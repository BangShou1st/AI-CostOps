package com.aicostops.cost.application;

import com.aicostops.cost.domain.AttributionHint;
import com.aicostops.cost.domain.CanonicalFactBatch;
import com.aicostops.cost.domain.ChargeCategory;
import com.aicostops.cost.domain.ChargeFact;
import com.aicostops.cost.domain.ConsumptionFact;
import com.aicostops.cost.domain.DocumentType;
import com.aicostops.cost.domain.ExternalDocument;
import com.aicostops.cost.domain.HintType;
import com.aicostops.cost.domain.ReviewStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure canonical fact mapping driven by an explicit {@code (sourceSchema, recordKind)}
 * whitelist (R3). Unknown pairs fail closed (R4); zero-fact schemas return an
 * explicit empty batch (R5). Provider evidence is never derived or summed (R1/R2),
 * the raw payload is never re-read (R14), and masked credentials never become
 * API-key hints (R10). All amounts stay BigDecimal; metadata values are stringified.
 */
@Component
public final class CanonicalCostNormalizer {

    private static final String OPENAI_USAGE = "openai.organization-usage-completions-json.v1";
    private static final String OPENAI_COSTS = "openai.organization-costs-json.v1";
    private static final String OPENAI_EMPTY_EXPORT = "openai.observed-empty-export.v1";
    private static final String MIMO_WORKBOOK = "mimo.usage-workbook.v1";
    private static final String GLM_WORKBOOK = "glm.monthly-billing-summary-workbook.v1";
    private static final String KIMI_WORKBOOK = "kimi.billing-summary-workbook.v1";
    private static final String DEEPSEEK_ZIP = "deepseek.usage-zip.v1";

    private static final String TOKENS = "tokens";
    private static final String REQUESTS = "requests";

    private static final List<UsageMeter> OPENAI_USAGE_REQUIRED_METERS = List.of(
            new UsageMeter("inputTokens", "input_tokens", TOKENS),
            new UsageMeter("outputTokens", "output_tokens", TOKENS),
            new UsageMeter("numModelRequests", "num_model_requests", REQUESTS));

    private static final List<UsageMeter> OPENAI_USAGE_OPTIONAL_METERS = List.of(
            new UsageMeter("inputCachedTokens", "input_cached_tokens", TOKENS),
            new UsageMeter("inputCacheWriteTokens", "input_cache_write_tokens", TOKENS),
            new UsageMeter("inputUncachedTokens", "input_uncached_tokens", TOKENS),
            new UsageMeter("inputTextTokens", "input_text_tokens", TOKENS),
            new UsageMeter("inputAudioTokens", "input_audio_tokens", TOKENS),
            new UsageMeter("inputImageTokens", "input_image_tokens", TOKENS),
            new UsageMeter("inputCachedTextTokens", "input_cached_text_tokens", TOKENS),
            new UsageMeter("inputCachedAudioTokens", "input_cached_audio_tokens", TOKENS),
            new UsageMeter("inputCachedImageTokens", "input_cached_image_tokens", TOKENS),
            new UsageMeter("outputTextTokens", "output_text_tokens", TOKENS),
            new UsageMeter("outputAudioTokens", "output_audio_tokens", TOKENS),
            new UsageMeter("outputImageTokens", "output_image_tokens", TOKENS));

    private static final List<UsageMeter> MIMO_MODEL_METERS = List.of(
            new UsageMeter("totalTokens", "total_tokens", TOKENS),
            new UsageMeter("inputHitTokens", "input_hit_tokens", TOKENS),
            new UsageMeter("inputMissTokens", "input_miss_tokens", TOKENS),
            new UsageMeter("outputTokens", "output_tokens", TOKENS),
            new UsageMeter("requestCount", "request_count", REQUESTS));

    private static final List<String> GLM_REPORTED_COMPONENTS =
            List.of("consumptionAmount", "payableAmount", "paidAmount", "outstandingAmount");
    private static final List<String> GLM_METADATA_COMPONENTS =
            List.of("catalogAmount", "creditPaymentAmount", "promotionalDeductionAmount");
    private static final List<String> GLM_METADATA_FIELDS =
            List.of("billingMonth", "settlementStatus");

    private static final List<String> KIMI_METADATA_COMPONENTS =
            List.of("paidBalanceConsumption", "promotionalBalanceConsumption");
    private static final List<String> KIMI_METADATA_FIELDS =
            List.of("billingEntity", "periodText", "providerOrganization");

    private final ObjectMapper objectMapper;

    public CanonicalCostNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Maps one raw record's normalized payload; unknown schemas fail closed. */
    public CanonicalFactBatch normalize(CanonicalizationInput input) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(input.normalizedPayload());
        } catch (JacksonException malformed) {
            throw new IllegalStateException(
                    "normalized payload is not readable JSON for record " + input.recordLocator(), malformed);
        }
        var schema = payload.path("sourceSchema").asText();
        var kind = payload.path("recordKind").asText();
        return switch (schema) {
            case OPENAI_USAGE -> openAiUsage(input, payload, kind);
            case OPENAI_COSTS -> openAiCosts(input, payload, kind);
            case OPENAI_EMPTY_EXPORT -> emptyExport(input, kind);
            case MIMO_WORKBOOK -> mimo(input, payload, kind);
            case GLM_WORKBOOK -> glm(input, payload, kind);
            case KIMI_WORKBOOK -> kimi(input, payload, kind);
            case DEEPSEEK_ZIP -> deepSeek(input, payload, kind);
            default -> throw new IllegalStateException("unsupported canonicalization pair ("
                    + schema + ", " + kind + ") for record " + input.recordLocator());
        };
    }

    // ------------------------------------------------------------------
    // OpenAI
    // ------------------------------------------------------------------

    private CanonicalFactBatch openAiUsage(CanonicalizationInput input, JsonNode payload, String kind) {
        if (!"USAGE".equals(kind)) {
            throw unknown(input, payload);
        }
        var dimensions = payload.path("dimensions");
        var usage = payload.path("usage");
        var consumptions = new ArrayList<ConsumptionFact>(15);
        var index = 0;
        for (var meter : OPENAI_USAGE_REQUIRED_METERS) {
            var quantity = requiredDecimal(usage, meter.key());
            consumptions.add(openAiUsageConsumption(input, dimensions, meter, quantity, index++));
        }
        for (var meter : OPENAI_USAGE_OPTIONAL_METERS) {
            if (hasNumber(usage, meter.key())) {
                consumptions.add(openAiUsageConsumption(input, dimensions, meter,
                        usage.get(meter.key()).decimalValue(), index++));
            }
        }
        return new CanonicalFactBatch(List.of(), consumptions, List.of(), List.of(),
                openAiUsageHints(input, dimensions));
    }

    private ConsumptionFact openAiUsageConsumption(
            CanonicalizationInput input, JsonNode dimensions, UsageMeter meter,
            BigDecimal quantity, int factIndex) {
        return new ConsumptionFact(input.orgId(), input.rawRecordId(), factIndex,
                input.providerCode(), null, text(dimensions.path("model")),
                meter.meterCode(), quantity, meter.unit(),
                input.usageStart(), input.usageEnd(), null,
                null, text(dimensions.path("providerProject")), text(dimensions.path("providerUser")),
                null, apiKeyLabel(dimensions.path("credentialId")));
    }

    private List<AttributionHint> openAiUsageHints(CanonicalizationInput input, JsonNode dimensions) {
        var hints = new ArrayList<AttributionHint>(3);
        var index = 0;
        var user = text(dimensions.path("providerUser"));
        if (user != null) {
            hints.add(hint(input, index++, HintType.PROVIDER_USER, user));
        }
        var project = text(dimensions.path("providerProject"));
        if (project != null) {
            hints.add(hint(input, index++, HintType.PROVIDER_PROJECT, project));
        }
        var credential = text(dimensions.path("credentialId"));
        if (credential != null && !isMasked(credential)) {
            hints.add(hint(input, index++, HintType.PROVIDER_API_KEY, credential));
        }
        return hints;
    }

    private CanonicalFactBatch openAiCosts(CanonicalizationInput input, JsonNode payload, String kind) {
        if (!"COST".equals(kind)) {
            throw unknown(input, payload);
        }
        var dimensions = payload.path("dimensions");
        var metadata = metadataOf(payload.path("providerFields"), List.of("lineItem", "quantity"));
        var charge = charge(input, 0, payload.path("money"), metadata);
        var hints = new ArrayList<AttributionHint>(2);
        var index = 0;
        var project = text(dimensions.path("providerProject"));
        if (project != null) {
            hints.add(hint(input, index++, HintType.PROVIDER_PROJECT, project));
        }
        var credential = text(dimensions.path("credentialId"));
        if (credential != null && !isMasked(credential)) {
            hints.add(hint(input, index++, HintType.PROVIDER_API_KEY, credential));
        }
        return new CanonicalFactBatch(List.of(), List.of(), List.of(), List.of(charge), hints);
    }

    private CanonicalFactBatch emptyExport(CanonicalizationInput input, String kind) {
        if (!"EMPTY_USAGE_BUCKET".equals(kind) && !"EMPTY_COST_BUCKET".equals(kind)) {
            throw new IllegalStateException("unsupported canonicalization pair ("
                    + OPENAI_EMPTY_EXPORT + ", " + kind + ") for record " + input.recordLocator());
        }
        return new CanonicalFactBatch(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------------
    // MiMo
    // ------------------------------------------------------------------

    private CanonicalFactBatch mimo(CanonicalizationInput input, JsonNode payload, String kind) {
        if ("USAGE".equals(kind)) {
            return mimoModel(input, payload);
        }
        if ("PLUGIN_USAGE".equals(kind)) {
            return mimoPlugin(input, payload);
        }
        throw unknown(input, payload);
    }

    private CanonicalFactBatch mimoModel(CanonicalizationInput input, JsonNode payload) {
        var usage = payload.path("usage");
        var dimensions = payload.path("dimensions");
        var consumptions = new ArrayList<ConsumptionFact>(5);
        for (var i = 0; i < MIMO_MODEL_METERS.size(); i++) {
            var meter = MIMO_MODEL_METERS.get(i);
            var quantity = requiredDecimal(usage, meter.key());
            consumptions.add(new ConsumptionFact(input.orgId(), input.rawRecordId(), i,
                    input.providerCode(), null, text(dimensions.path("model")),
                    meter.meterCode(), quantity, meter.unit(),
                    input.usageStart(), input.usageEnd(), null,
                    null, null, null, null, null));
        }
        var metadata = combine(
                metadataOf(payload.path("money").path("components"),
                        List.of("inputHitAmount", "inputMissAmount", "outputAmount")),
                metadataOf(payload.path("providerFields"), List.of("date")));
        metadata = combine(metadata, metadataOf(payload.path("usage"), List.of("totalAudioDurationRaw")));
        var charge = charge(input, 0, payload.path("money"), metadata);
        return new CanonicalFactBatch(List.of(), consumptions, List.of(), List.of(charge), List.of());
    }

    private CanonicalFactBatch mimoPlugin(CanonicalizationInput input, JsonNode payload) {
        var usage = payload.path("usage");
        var quantity = requiredDecimal(usage, "requestCount");
        var plugin = text(payload.path("providerFields").path("plugin"));
        var consumption = new ConsumptionFact(input.orgId(), input.rawRecordId(), 0,
                input.providerCode(), plugin, null,
                "request_count", quantity, REQUESTS,
                input.usageStart(), input.usageEnd(), null,
                null, null, null, null, null);
        var metadata = metadataOf(payload.path("providerFields"), List.of("date"));
        var charge = charge(input, 0, payload.path("money"), metadata);
        return new CanonicalFactBatch(List.of(), List.of(consumption), List.of(), List.of(charge), List.of());
    }

    // ------------------------------------------------------------------
    // GLM
    // ------------------------------------------------------------------

    private CanonicalFactBatch glm(CanonicalizationInput input, JsonNode payload, String kind) {
        if (!"BILLING_SUMMARY".equals(kind)) {
            throw unknown(input, payload);
        }
        var components = payload.path("money").path("components");
        var metadata = combine(
                metadataOf(components, GLM_METADATA_COMPONENTS),
                metadataOf(payload.path("providerFields"), GLM_METADATA_FIELDS));
        var document = new ExternalDocument(input.orgId(), input.rawRecordId(), 0,
                DocumentType.BILL_SUMMARY,
                null, null, null,
                decimalOrNull(components, "consumptionAmount"),
                decimalOrNull(components, "payableAmount"),
                decimalOrNull(components, "paidAmount"),
                decimalOrNull(components, "outstandingAmount"),
                metadata);
        return new CanonicalFactBatch(List.of(document), List.of(), List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------------
    // Kimi
    // ------------------------------------------------------------------

    private CanonicalFactBatch kimi(CanonicalizationInput input, JsonNode payload, String kind) {
        if (!"BILLING_SUMMARY".equals(kind)) {
            throw unknown(input, payload);
        }
        var money = payload.path("money");
        var metadata = combine(
                metadataOf(money.path("components"), KIMI_METADATA_COMPONENTS),
                metadataOf(payload.path("providerFields"), KIMI_METADATA_FIELDS));
        metadata = combine(metadata, metadataOf(payload.path("dimensions"), List.of("providerOrganization")));
        var document = new ExternalDocument(input.orgId(), input.rawRecordId(), 0,
                DocumentType.BILL_SUMMARY,
                null, null, text(money.path("currency")),
                null, null, null, null,
                metadata);
        var hints = new ArrayList<AttributionHint>(1);
        var user = text(payload.path("dimensions").path("providerUser"));
        if (user != null) {
            hints.add(hint(input, 0, HintType.PROVIDER_USER, user));
        }
        return new CanonicalFactBatch(List.of(document), List.of(), List.of(), List.of(), hints);
    }

    // ------------------------------------------------------------------
    // DeepSeek
    // ------------------------------------------------------------------

    private CanonicalFactBatch deepSeek(CanonicalizationInput input, JsonNode payload, String kind) {
        if ("USAGE".equals(kind)) {
            return deepSeekAmount(input, payload);
        }
        if ("COST".equals(kind)) {
            return deepSeekCost(input, payload);
        }
        throw unknown(input, payload);
    }

    /** amount.csv carries price/amount only as raw fields; they are never upgraded (double-count guard). */
    private CanonicalFactBatch deepSeekAmount(CanonicalizationInput input, JsonNode payload) {
        var dimensions = payload.path("dimensions");
        var hints = new ArrayList<AttributionHint>(2);
        var index = 0;
        var user = text(dimensions.path("providerUser"));
        if (user != null) {
            hints.add(hint(input, index++, HintType.PROVIDER_USER, user));
        }
        var credentialLabel = text(payload.path("providerFields").path("credentialLabel"));
        if (credentialLabel != null && !isMasked(credentialLabel)) {
            hints.add(hint(input, index++, HintType.PROVIDER_API_KEY, credentialLabel));
        }
        return new CanonicalFactBatch(List.of(), List.of(), List.of(), List.of(), hints);
    }

    /** cost.csv metadata is exactly {model, walletType}; price/amount are not in the normalized payload (R14). */
    private CanonicalFactBatch deepSeekCost(CanonicalizationInput input, JsonNode payload) {
        var metadata = combine(
                metadataOf(payload.path("dimensions"), List.of("model")),
                metadataOf(payload.path("providerFields"), List.of("walletType")));
        var charge = charge(input, 0, payload.path("money"), metadata);
        var hints = new ArrayList<AttributionHint>(1);
        var user = text(payload.path("dimensions").path("providerUser"));
        if (user != null) {
            hints.add(hint(input, 0, HintType.PROVIDER_USER, user));
        }
        return new CanonicalFactBatch(List.of(), List.of(), List.of(), List.of(charge), hints);
    }

    // ------------------------------------------------------------------
    // Shared construction
    // ------------------------------------------------------------------

    private ChargeFact charge(CanonicalizationInput input, int factIndex, JsonNode money,
            Map<String, Object> metadata) {
        var amount = money.path("reportedAmount");
        if (amount.isMissingNode() || amount.isNull() || !amount.isNumber()) {
            throw new IllegalStateException("normalized money.reportedAmount must be a number for record "
                    + input.recordLocator());
        }
        return new ChargeFact(input.orgId(), input.rawRecordId(), factIndex,
                input.providerCode(), ChargeCategory.USAGE,
                amount.decimalValue(), text(money.path("currency")),
                null, null, null, null,
                input.usageStart(), input.usageEnd(),
                ReviewStatus.CLEAN, null, metadata);
    }

    private static AttributionHint hint(CanonicalizationInput input, int factIndex,
            HintType type, String providerValue) {
        return new AttributionHint(input.orgId(), input.rawRecordId(), factIndex,
                type, null, null, providerValue, null, null);
    }

    private static BigDecimal requiredDecimal(JsonNode section, String key) {
        if (!hasNumber(section, key)) {
            throw new IllegalStateException("normalized usage field '" + key + "' must be a number");
        }
        return section.get(key).decimalValue();
    }

    private static BigDecimal decimalOrNull(JsonNode section, String key) {
        if (!hasNumber(section, key)) {
            return null;
        }
        return section.get(key).decimalValue();
    }

    private static boolean hasNumber(JsonNode section, String key) {
        var node = section.path(key);
        return !node.isMissingNode() && !node.isNull() && node.isNumber();
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : String.valueOf(node.asText());
    }

    private static String apiKeyLabel(JsonNode node) {
        var value = text(node);
        return value == null || isMasked(value) ? null : value;
    }

    private static boolean isMasked(String value) {
        return "********".equals(value) || value.toLowerCase().contains("redacted");
    }

    /** Builds an immutable metadata map from the named keys of one section; null when nothing is present. */
    private static Map<String, Object> metadataOf(JsonNode section, List<String> keys) {
        var metadata = new LinkedHashMap<String, Object>();
        for (var key : keys) {
            var node = section.path(key);
            if (!node.isMissingNode() && !node.isNull()) {
                metadata.put(key, scalar(node));
            }
        }
        return metadata.isEmpty() ? null : Map.copyOf(metadata);
    }

    /** Amounts and quantities are stringified in metadata; authority stays in the DECIMAL columns (R7). */
    private static Object scalar(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue().toPlainString();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    private static Map<String, Object> combine(Map<String, Object> first, Map<String, Object> second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        var merged = new LinkedHashMap<>(first);
        merged.putAll(second);
        return Map.copyOf(merged);
    }

    private static IllegalStateException unknown(CanonicalizationInput input, JsonNode payload) {
        return new IllegalStateException("unsupported canonicalization pair ("
                + payload.path("sourceSchema").asText() + ", " + payload.path("recordKind").asText()
                + ") for record " + input.recordLocator());
    }

    private record UsageMeter(String key, String meterCode, String unit) {
    }
}
