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
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isMulticastAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()) {
            return false;
        }
        var raw = address.getAddress();
        if (raw == null) {
            return false;
        }
        if (raw.length == 4) {
            return isGloballyRoutableIpv4(raw);
        }
        if (address instanceof java.net.Inet6Address v6) {
            return isGloballyRoutableIpv6(v6, raw);
        }
        return isGloballyRoutableIpv6Raw(raw);
    }

    static boolean isGloballyRoutableIpv4(byte[] raw) {
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        int b2 = raw[2] & 0xFF;
        int b3 = raw[3] & 0xFF;
        if (b0 == 0) {
            return false;
        }
        if (b0 == 10) {
            return false;
        }
        if (b0 == 100 && (b1 & 0xC0) == 0x40) {
            return false;
        }
        if (b0 == 127) {
            return false;
        }
        if (b0 == 169 && b1 == 254) {
            return false;
        }
        if (b0 == 172 && (b1 & 0xF0) == 0x10) {
            return false;
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            return false;
        }
        if (b0 == 192 && b1 == 0 && b2 == 2) {
            return false;
        }
        if (b0 == 192 && b1 == 168) {
            return false;
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return false;
        }
        if (b0 == 198 && b1 == 51 && b2 == 100) {
            return false;
        }
        if (b0 == 203 && b1 == 0 && b2 == 113) {
            return false;
        }
        if ((b0 & 0xF0) == 0xE0) {
            return false;
        }
        if ((b0 & 0xF0) == 0xF0) {
            return false;
        }
        if (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) {
            return false;
        }
        return true;
    }

    static boolean isGloballyRoutableIpv6(java.net.Inet6Address address, byte[] raw) {
        if (raw.length != 16) {
            return false;
        }
        if (isIpv4Mapped(raw)) {
            var v4 = new byte[] {raw[12], raw[13], raw[14], raw[15]};
            return isGloballyRoutableIpv4(v4);
        }
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        if ((b0 & 0xFE) == 0xFC) {
            return false;
        }
        if ((b0 == 0xFE) && ((b1 & 0xC0) == 0x80)) {
            return false;
        }
        if ((b0 & 0xFF) == 0xFF) {
            return false;
        }
        if (isDocumentationV6(raw)) {
            return false;
        }
        if ((b0 & 0xE0) != 0x20) {
            return false;
        }
        return true;
    }

    static boolean isGloballyRoutableIpv6Raw(byte[] raw) {
        if (raw.length != 16) {
            return false;
        }
        return isGloballyRoutableIpv6(null, raw);
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        for (var i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
    }

    private static boolean isDocumentationV6(byte[] raw) {
        return (raw[0] & 0xFF) == 0x20 && (raw[1] & 0xFF) == 0x01
                && (raw[2] & 0xFF) == 0x0D && (raw[3] & 0xFF) == 0xB8;
    }

    private ProviderExecutionException guardFailure(String detail) {
        return new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                ProviderSafetyReason.ENDPOINT_BLOCKED_PRE_DISPATCH, ProviderHealthSignal.NONE,
                null, null, false,
                new IllegalArgumentException("Dispatch endpoint blocked: " + detail));
    }
}
