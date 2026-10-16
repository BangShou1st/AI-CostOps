package com.aicostops.gateway.routing;

import com.sharedlibs.opencodezen.OpenCodeZenFreeAdmissionPolicy;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Gateway-owned OpenCode Zen free admission barrier (2026-10-16).
 *
 * <p>This is a deliberately <em>independent</em> second barrier. The Control Plane already refuses
 * to probe or promote a paid OpenCode model, but a model that was promoted before this round, that
 * already has an ACTIVE Pricing Version, or that sits on a routing policy activated earlier would
 * otherwise remain dispatchable. The Gateway therefore re-derives admission at candidate-evaluation
 * time, before any budget admission, dispatch fence or Provider HTTP I/O.
 *
 * <p>The barrier is independent in <em>execution</em>, not in <em>definition</em>: the reviewed free
 * set and predicate live once in the shared {@link OpenCodeZenFreeAdmissionPolicy}, so this barrier
 * and the Backend probe/promotion gate cannot drift into two conflicting free rules. Admission is an
 * allow-list, never a suffix rule: a {@code -free} name is not evidence, and a
 * {@code PAID}/`UNKNOWN`/`UNSUPPORTED` classification never becomes free here.
 *
 * <p>Only the built-in {@code OPENCODE_ZEN} adapter is in scope; every other Provider keeps its
 * existing eligibility behaviour.
 */
@Component
public final class OpenCodeFreeOnlyAdmission {

    /** Reviewed revision of the free admission policy; must advance with the evidence. */
    public static final String POLICY_VERSION = OpenCodeZenFreeAdmissionPolicy.POLICY_VERSION;

    /** Built-in OpenCode Zen adapter code; the only adapter this policy constrains. */
    public static final String OPENCODE_ZEN_ADAPTER = "OPENCODE_ZEN";

    /**
     * OpenCode Zen Chat Completions models that carry a currently valid free attestation. The set is
     * owned by the shared component; this alias keeps a single definition instead of a second copy.
     */
    public static final Set<String> ADMITTED_FREE_CHAT_MODELS =
            OpenCodeZenFreeAdmissionPolicy.ADMITTED_FREE_CHAT_MODELS;

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

    /** True only for the built-in OpenCode Zen adapter, the single surface this policy constrains. */
    public boolean isOpenCodeZen(String adapterCode) {
        return adapterCode != null
                && OPENCODE_ZEN_ADAPTER.equals(adapterCode.strip().toUpperCase(Locale.ROOT));
    }
}
