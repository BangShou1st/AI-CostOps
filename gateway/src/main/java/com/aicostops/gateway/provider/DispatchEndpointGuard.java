package com.aicostops.gateway.provider;

import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Per-dispatch shape guard for connection-profile endpoints (M18 V3).
 *
 * <p>Full DNS + redirect validation happens at profile activation/probe
 * time in the Control Plane. This hot-path guard re-checks URL shape on
 * every dispatch without DNS: scheme, userinfo absence and literal-IP
 * policy. Gateway adapters never follow redirects.
 */
@Component
public class DispatchEndpointGuard {

    public void check(String baseUrl, String completionPath, String networkPolicy) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw guardFailure("endpoint is missing");
        }
        final URI uri;
        try {
            uri = new URI(baseUrl.strip());
        } catch (Exception ex) {
            throw guardFailure("endpoint is malformed");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw guardFailure("endpoint scheme is not allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw guardFailure("endpoint must not embed credentials");
        }
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw guardFailure("endpoint hostname is malformed");
        }
        if ("DIRECT_PUBLIC_ONLY".equals(networkPolicy) && isLiteralBlockedIp(host)) {
            throw guardFailure("endpoint literal address is not public");
        }
        if (completionPath != null && !completionPath.isBlank()
                && !completionPath.strip().matches("/[A-Za-z0-9._~!$&'()*+,;=:@/-]*")) {
            throw guardFailure("completion path is malformed");
        }
    }

    static boolean isLiteralBlockedIp(String host) {
        var normalized = host.strip();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            var address = java.net.InetAddress.getByName(normalized);
            if (!address.getHostAddress().equalsIgnoreCase(normalized)
                    && !normalized.equalsIgnoreCase(address.getHostName())) {
                return false;
            }
            return !isPublicUnicast(address);
        } catch (Exception ex) {
            return false;
        }
    }

    static boolean isPublicUnicast(java.net.InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isMulticastAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()) {
            return false;
        }
        if (address instanceof java.net.Inet6Address v6) {
            var raw = v6.getAddress();
            if ((raw[0] & 0xFE) == 0xFC) {
                return false;
            }
        }
        return true;
    }

    private ProviderExecutionException guardFailure(String detail) {
        return new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                ProviderSafetyReason.ENDPOINT_BLOCKED_PRE_DISPATCH, ProviderHealthSignal.NONE,
                null, null, false,
                new IllegalArgumentException("Dispatch endpoint blocked: " + detail));
    }
}
