package com.aicostops.providerhub.application;

import com.sharedlibs.kilogateway.FreeModelPolicy;
import org.springframework.stereotype.Component;

/**
 * Server-owned Kilo Gateway model compatibility manifest (M18 V3).
 *
 * <p>Kilo Gateway is an OpenAI-compatible universal inference gateway: every id it serves is
 * dispatched through the Chat Completions adapter, so the connection-level protocol already equals
 * the per-model protocol. Unlike OpenCode Zen — which multiplexes Responses / Messages /
 * Gemini-native families behind one catalog and therefore needs a reviewed protocol table — Kilo
 * needs no per-model protocol review, only the free / paid classification.
 *
 * <p>Free classification is deliberately NOT re-invented here: it is delegated to the shared
 * library {@link FreeModelPolicy#isFreeModelId(String)}, the exact same single definition the
 * Gateway admission barrier executes, so the Backend probe/promotion gate and the Gateway dispatch
 * barrier cannot drift into two conflicting free rules. An id without free evidence fails closed:
 * it stays discovered and AVAILABLE but can never be probed, promoted or dispatched through the
 * built-in free-only template.
 */
@Component
public class KiloGatewayModelManifest {

    public static final String MANIFEST_VERSION = "2026-10-18-v1";
    public static final String VERIFIED_AT = "2026-10-18";
    public static final String SOURCE = "Kilo Gateway shared library FreeModelPolicy";
    public static final String PROTOCOL_CHAT = "OPENAI_CHAT_COMPLETIONS";
    public static final String PRICING_VERIFIED_FREE = "VERIFIED_FREE";
    public static final String PRICING_UNKNOWN = "UNKNOWN";

    private static final String KEY_PREFIX = "kilo-";
    private static final int FINGERPRINT_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 100;

    public String version() {
        return MANIFEST_VERSION;
    }

    public String verifiedAt() {
        return VERIFIED_AT;
    }

    public String source() {
        return SOURCE;
    }

    /** Every Kilo id is served through Chat Completions; the template protocol is authoritative. */
    public String protocolFor(String name) {
        return PROTOCOL_CHAT;
    }

    /** Verified free only when the shared policy attests it; everything else fails closed. */
    public String pricingFor(String name) {
        return isFreeEligible(name) ? PRICING_VERIFIED_FREE : PRICING_UNKNOWN;
    }

    /**
     * FREE_ONLY admission for the built-in Kilo template: the single predicate the probe, the
     * promotion and (independently) the Gateway must agree on. Delegated to the shared library so an
     * id fails closed unless the server-owned free policy attests it.
     */
    public boolean isFreeEligible(String name) {
        return name != null && FreeModelPolicy.isFreeModelId(name.strip());
    }

    public boolean isChatCompatible(String name) {
        return PROTOCOL_CHAT.equals(protocolFor(name));
    }

    /**
     * Stable, legal, collision-resistant internal logical model_key for a Kilo Provider model id.
     *
     * <p>Kilo ids legitimately contain {@code /} and {@code :} (for example
     * {@code poolside/laguna-s-2.1:free}), which the generic model-key rule rejects. The key is a
     * bounded, sanitised stem plus a SHA-256 fingerprint of the exact raw id, so the same id always
     * maps to the same key and two ids that differ only in the characters the sanitiser rewrites can
     * never collapse onto one another. The raw id is preserved unchanged as the logical display name
     * and as {@code provider_model.provider_model_name}.
     */
    public String logicalModelKey(String providerModelName) {
        var raw = providerModelName == null ? "" : providerModelName.strip();
        var stem = raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[^a-z0-9]+", "")
                .replaceAll("[^a-z0-9]+$", "");
        if (stem.isEmpty()) {
            stem = "model";
        }
        var fingerprint = sha256Hex(raw).substring(0, FINGERPRINT_LENGTH);
        var maxStemLength = MAX_KEY_LENGTH - KEY_PREFIX.length() - 1 - fingerprint.length();
        if (stem.length() > maxStemLength) {
            stem = stem.substring(0, maxStemLength);
        }
        return KEY_PREFIX + stem + "-" + fingerprint;
    }

    private static String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var builder = new StringBuilder(digest.length * 2);
            for (var b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
