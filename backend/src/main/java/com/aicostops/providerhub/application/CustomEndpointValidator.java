package com.aicostops.providerhub.application;

import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * SSRF defense for custom Provider endpoints (M18 V3, DIRECT_PUBLIC_ONLY).
 *
 * <p>Validates the initial URL and every redirect target: syntax, scheme,
 * userinfo absence, DNS resolution, and per-address public-unicast policy.
 * There is no production bypass flag; tests inject a fake {@link DnsResolver}.
 */
@Component
public class CustomEndpointValidator {

    public static final Set<String> FORBIDDEN_AUTH_HEADERS = Set.of(
            "AUTHORIZATION", "PROXY-AUTHORIZATION", "PROXY-CONNECTION", "HOST",
            "CONTENT-LENGTH", "CONNECTION", "COOKIE", "SET-COOKIE", "TRANSFER-ENCODING");

    private final DnsResolver dnsResolver;
    private final java.util.function.Predicate<java.net.InetAddress> addressPolicy;

    public CustomEndpointValidator() {
        this(DnsResolver.system(), CustomEndpointValidator::isPublicUnicast);
    }

    CustomEndpointValidator(DnsResolver dnsResolver) {
        this(dnsResolver, CustomEndpointValidator::isPublicUnicast);
    }

    /**
     * Test seam: custom DNS plus custom address policy. Production always uses the strict
     * public-unicast policy; lenient policies exist only in test sourcesets for controlled
     * loopback servers and can never be enabled by browser/admin runtime configuration.
     */
    CustomEndpointValidator(DnsResolver dnsResolver,
            java.util.function.Predicate<java.net.InetAddress> addressPolicy) {
        this.dnsResolver = dnsResolver;
        this.addressPolicy = addressPolicy;
    }

    /**
     * Pure deterministic URL shape validation: syntax, scheme, userinfo absence, host shape
     * and port range. Performs no DNS and no network I/O, so it is safe inside DB transactions.
     */
    public void validateSyntax(String rawUrl) {
        parse(rawUrl, "endpoint");
    }

    /** Validates a configured endpoint URL (initial destination). */
    public void validateEndpoint(String rawUrl) {
        validateTarget(rawUrl, "endpoint");
    }

    /** Re-validates every redirect hop before following it. */
    public void validateRedirectTarget(String rawUrl) {
        validateTarget(rawUrl, "redirect target");
    }

    /** Resolves and returns the validated public addresses for connection pinning. */
    public List<InetAddress> resolvePublicAddresses(String rawUrl) {
        var uri = parse(rawUrl, "endpoint");
        return resolveAndCheck(uri, "endpoint");
    }

    public void validateAuthHeaderName(String authType, String headerName) {
        if (!"API_KEY_HEADER".equals(authType)) {
            return;
        }
        if (headerName == null || headerName.isBlank()) {
            throw blocked("API key header name is required");
        }
        var normalized = headerName.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9-]+")) {
            throw blocked("API key header name has an illegal shape");
        }
        if (FORBIDDEN_AUTH_HEADERS.contains(normalized.toUpperCase(Locale.ROOT))) {
            throw blocked("API key header name is reserved");
        }
    }

    private void validateTarget(String rawUrl, String role) {
        var uri = parse(rawUrl, role);
        resolveAndCheck(uri, role);
    }

    private URI parse(String rawUrl, String role) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 500) {
            throw blocked("Provider " + role + " URL is malformed");
        }
        final URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (Exception ex) {
            throw blocked("Provider " + role + " URL is malformed");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw blocked("Provider " + role + " URL scheme is not allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw blocked("Provider " + role + " URL must not embed credentials");
        }
        var host = uri.getHost();
        if (host == null || host.isBlank() || host.length() > 253) {
            throw blocked("Provider " + role + " hostname is malformed");
        }
        try {
            IDN.toASCII(host);
        } catch (Exception ex) {
            throw blocked("Provider " + role + " hostname is malformed");
        }
        var port = uri.getPort();
        if (port != -1 && (port <= 0 || port > 65535)) {
            throw blocked("Provider " + role + " port is malformed");
        }
        return uri;
    }

    private List<InetAddress> resolveAndCheck(URI uri, String role) {
        final InetAddress[] resolved;
        try {
            resolved = dnsResolver.resolve(uri.getHost());
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, ProblemCode.ENDPOINT_BLOCKED,
                    "Provider endpoint unreachable", "Provider " + role + " DNS resolution failed.");
        }
        if (resolved == null || resolved.length == 0) {
            throw blocked("Provider " + role + " DNS resolution failed");
        }
        var accepted = new ArrayList<InetAddress>(resolved.length);
        for (var address : resolved) {
            if (address == null || !addressPolicy.test(address)) {
                throw blocked("Provider " + role + " resolves to a non-public address");
            }
            accepted.add(address);
        }
        return List.copyOf(accepted);
    }

    static boolean isPublicUnicast(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isMulticastAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()) {
            return false;
        }
        if (address instanceof Inet6Address v6 && isUniqueLocal(v6)) {
            return false;
        }
        return true;
    }

    private static boolean isUniqueLocal(Inet6Address address) {
        var raw = address.getAddress();
        return (raw[0] & 0xFE) == 0xFC;
    }

    private DomainException blocked(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.ENDPOINT_BLOCKED,
                "Provider endpoint blocked", detail + ".");
    }

    /** Test seam: DNS resolution strategy (production uses the JVM resolver). */
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws Exception;

        static DnsResolver system() {
            return InetAddress::getAllByName;
        }
    }
}
