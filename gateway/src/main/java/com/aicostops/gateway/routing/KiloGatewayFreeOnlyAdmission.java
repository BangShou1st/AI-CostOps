package com.aicostops.gateway.routing;

import com.sharedlibs.kilogateway.FreeModelPolicy;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Gateway-owned Kilo Gateway free admission barrier (2026-10-18).
 *
 * <p>This is a deliberately <em>independent</em> second barrier for the built-in Kilo Gateway
 * template. The Control Plane already refuses to probe or promote a non-free Kilo model, but a model
 * that was promoted before this round, that already has an ACTIVE Pricing Version, or that sits on a
 * routing policy activated earlier would otherwise remain dispatchable. The Gateway therefore
 * re-derives admission at candidate-evaluation time, before any budget admission, dispatch fence or
 * Provider HTTP I/O.
 *
 * <p>The barrier is independent in <em>execution</em>, not in <em>definition</em>: the reviewed Kilo
 * free rule lives once in the shared library {@link FreeModelPolicy#isFreeModelId(String)}, so this
 * barrier and the Backend probe/promotion gate cannot drift into two conflicting free rules.
 *
 * <p>Only the built-in {@code KILO_GATEWAY} adapter is in scope; every other Provider keeps its
 * existing eligibility behaviour.
 */
@Component
public final class KiloGatewayFreeOnlyAdmission {

    /** Built-in Kilo Gateway adapter code; the only adapter this policy constrains. */
    public static final String KILO_GATEWAY_ADAPTER = "KILO_GATEWAY";

    /**
     * Whether one frozen route candidate may be dispatched. Non-Kilo adapters are never affected. A
     * Kilo model is admitted only when the shared free-model policy attests it; every other id fails
     * closed.
     */
    public boolean admits(String adapterCode, String providerModelName) {
        if (!isKiloGateway(adapterCode)) {
            return true;
        }
        return providerModelName != null
                && FreeModelPolicy.isFreeModelId(providerModelName.strip());
    }

    /** True only for the built-in Kilo Gateway adapter, the single surface this policy constrains. */
    public boolean isKiloGateway(String adapterCode) {
        return adapterCode != null
                && KILO_GATEWAY_ADAPTER.equals(adapterCode.strip().toUpperCase(Locale.ROOT));
    }
}
