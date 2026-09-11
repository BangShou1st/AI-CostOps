package com.aicostops.providerhub.application;

import java.net.URI;

/**
 * Shared bounded Provider transport policy (M18 repair).
 * Discovery, connection probe and model probe share base URL / paths /
 * DIRECT_ONLY / DIRECT_PUBLIC_ONLY / credential / server-owned User-Agent /
 * timeouts / redirect + SSRF policy / body bound. No browser proxy, no
 * arbitrary headers, no auto-redirect with secret forwarding.
 */
public final class ProviderTransportSupport {

    public static final int MAX_BODY_BYTES = 65536;
    public static final int MAX_MODEL_IDS = 500;
    public static final int MAX_REDIRECTS = 3;
    public static final String OPENCODE_USER_AGENT =
            ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT;
    /**
     * Neutral User-Agent for custom Providers without an explicit userAgent. Custom traffic must
     * never masquerade as OpenCode; the OpenCode UA is provider-specific to OPENCODE_ZEN.
     */
    public static final String NEUTRAL_PROVIDER_USER_AGENT = "AI-CostOps-Provider/3.0";

    @Deprecated
    public static final String DEFAULT_PROBE_USER_AGENT = OPENCODE_USER_AGENT;

    private ProviderTransportSupport() {
    }

    /**
     * Server-owned User-Agent policy: explicit profile UA wins; otherwise OPENCODE_ZEN uses the
     * provider-specific OpenCode UA and CUSTOM falls back to the neutral provider UA.
     */
    public static String serverUserAgent(String profileUserAgent, String templateCode) {
        if (profileUserAgent != null && !profileUserAgent.isBlank()) {
            return profileUserAgent;
        }
        if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(templateCode)) {
            return OPENCODE_USER_AGENT;
        }
        return NEUTRAL_PROVIDER_USER_AGENT;
    }

    @Deprecated
    public static String serverUserAgent(String profileUserAgent) {
        return profileUserAgent == null || profileUserAgent.isBlank()
                ? NEUTRAL_PROVIDER_USER_AGENT : profileUserAgent;
    }

    public static String joinBase(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + (path.startsWith("/") ? path : "/" + path);
    }

    public static boolean sameOrigin(URI a, URI b) {
        if (a.getScheme() == null || b.getScheme() == null) {
            return false;
        }
        if (!a.getScheme().equalsIgnoreCase(b.getScheme())) {
            return false;
        }
        if (a.getHost() == null || b.getHost() == null) {
            return false;
        }
        if (!a.getHost().equalsIgnoreCase(b.getHost())) {
            return false;
        }
        return effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    public static URI resolveRedirect(String current, String location) {
        var resolved = URI.create(current).resolve(location);
        if (resolved.getScheme() == null || resolved.getHost() == null) {
            throw new IllegalArgumentException("Redirect target is malformed");
        }
        return resolved;
    }
}
