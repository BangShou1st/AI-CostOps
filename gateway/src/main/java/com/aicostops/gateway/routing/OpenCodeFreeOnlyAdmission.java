package com.aicostops.gateway.routing;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Gateway-owned OpenCode Zen free admission policy (2026-10-16).
 *
 * <p>This is a deliberately <em>independent</em> second barrier. The Control Plane already refuses
 * to probe or promote a paid OpenCode model, but a model that was promoted before this round, that
 * already has an ACTIVE Pricing Version, or that sits on a routing policy activated earlier would
 * otherwise remain dispatchable. The Gateway therefore re-derives admission from its own
 * server-owned list at candidate-evaluation time, before any budget admission, dispatch fence or
 * Provider HTTP I/O.
 *
 * <p>Admission is an allow-list, never a suffix rule: a {@code -free} name is not evidence, and a
 * {@code PAID}/`UNKNOWN`/`UNSUPPORTED` classification never becomes free here. The list is closed
 * under "officially served by V1 Zen at the reviewed revision <em>and</em> listed Free on the
 * official V1 Zen pricing table".
 *
 * <p>Only the built-in {@code OPENCODE_ZEN} adapter is in scope; every other Provider keeps its
 * existing eligibility behaviour. The list is intentionally duplicated rather than shared with the
 * Backend so that neither deployable can silently widen the other's admission.
 */
@Component
public final class OpenCodeFreeOnlyAdmission {

    /** Reviewed revision of the free admission policy; must advance with the evidence. */
    public static final String POLICY_VERSION = "2026-10-16-v1";

    /** Built-in OpenCode Zen adapter code; the only adapter this policy constrains. */
    public static final String OPENCODE_ZEN_ADAPTER = "OPENCODE_ZEN";

    /**
     * OpenCode Zen Chat Completions models that carry a currently valid free attestation:
     * an official {@code /zen/v1/chat/completions} endpoint row, an official V1 Zen pricing row of
     * {@code Free}, and continued service in the live V1 Zen catalog as of 2026-10-16.
     */
    public static final Set<String> ADMITTED_FREE_CHAT_MODELS = Set.of(
            "big-pickle",
            "longcat-2.5-preview-free",
            "mimo-v2.6-flash-free",
            "nemotron-3.5-lightning-free",
            "space-bunny-free");

    private final Set<String> admitted;

    /** Production wiring: the reviewed free allow-list, with no runtime override. */
    @org.springframework.beans.factory.annotation.Autowired
    public OpenCodeFreeOnlyAdmission() {
        this(ADMITTED_FREE_CHAT_MODELS);
    }

    /** Test seam only: inject a synthetic allow-list; never reachable from runtime config. */
    OpenCodeFreeOnlyAdmission(Set<String> admitted) {
        this.admitted = Set.copyOf(admitted);
    }

    public static OpenCodeFreeOnlyAdmission production() {
        return new OpenCodeFreeOnlyAdmission();
    }

    /**
     * Whether one frozen route candidate may be dispatched. Non-OpenCode adapters are never
     * affected. An OpenCode Zen model is admitted only when its exact server-owned name is on the
     * reviewed free list; every other name — paid, unknown, unsupported, delisted, Console-only or
     * suffix-invented — fails closed.
     */
    public boolean admits(String adapterCode, String providerModelName) {
        if (!isOpenCodeZen(adapterCode)) {
            return true;
        }
        return providerModelName != null
                && admitted.contains(providerModelName.strip().toLowerCase(Locale.ROOT));
    }

    public boolean isOpenCodeZen(String adapterCode) {
        return adapterCode != null
                && OPENCODE_ZEN_ADAPTER.equals(adapterCode.strip().toUpperCase(Locale.ROOT));
    }
}
