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
}
