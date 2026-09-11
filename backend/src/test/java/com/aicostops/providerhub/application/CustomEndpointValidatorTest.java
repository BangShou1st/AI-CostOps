package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicostops.shared.web.DomainException;
import java.net.InetAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomEndpointValidatorTest {

    private static CustomEndpointValidator validatorFor(Map<String, String[]> dns) {
        return new CustomEndpointValidator(host -> {
            var ips = dns.get(host.toLowerCase(java.util.Locale.ROOT));
            if (ips == null) {
                // Literal IPs resolve to themselves without DNS; named test
                // hosts fall back to a documentation public address so the
                // suite stays deterministic offline.
                try {
                    return new InetAddress[] { InetAddress.getByName(host) };
                } catch (java.net.UnknownHostException ex) {
                    return new InetAddress[] { InetAddress.getByName("93.184.216.34") };
                }
            }
            var resolved = new InetAddress[ips.length];
            for (var i = 0; i < ips.length; i++) {
                resolved[i] = InetAddress.getByName(ips[i]);
            }
            return resolved;
        });
    }

    private final CustomEndpointValidator validator = validatorFor(Map.of());

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost:8080/v1", "https://localhost/v1", "http://127.0.0.1:7897/",
        "http://127.0.0.2/", "http://10.0.0.5/v1", "http://172.16.4.9/v1",
        "http://192.168.1.10/v1", "http://169.254.169.254/latest/meta-data/",
        "http://[::1]/v1", "http://[fe80::1]/v1", "http://[fc00::1]/v1",
        "http://[ff02::1]/v1", "http://0.0.0.0/v1",
        "http://100.64.0.1/v1", "http://100.127.255.1/v1",
        "http://192.0.2.1/v1", "http://198.51.100.7/v1", "http://203.0.113.9/v1",
        "http://198.18.0.1/v1", "http://198.19.255.1/v1",
        "http://192.0.0.1/v1", "http://224.0.0.1/v1", "http://255.255.255.255/v1",
        "http://[2001:db8::1]/v1",
        "https://user:pass@example.com/v1", "ftp://example.com/v1", "file:///etc/passwd"
    })
    void rejectsBlockedTargets(String url) {
        var ex = assertThrows(DomainException.class, () -> validator.validateEndpoint(url));
        assertTrue(ex.code() == com.aicostops.shared.web.ProblemCode.ENDPOINT_BLOCKED,
                () -> "Unexpected problem code: " + ex.code());
    }

    @Test
    void allowsGloballyRoutablePublic() throws Exception {
        assertTrue(CustomEndpointValidator.isPublicUnicast(InetAddress.getByName("8.8.8.8")));
        assertTrue(CustomEndpointValidator.isPublicUnicast(InetAddress.getByName("1.1.1.1")));
        assertTrue(!CustomEndpointValidator.isPublicUnicast(InetAddress.getByName("10.0.0.1")));
        assertTrue(!CustomEndpointValidator.isPublicUnicast(InetAddress.getByName("100.64.0.1")));
        assertTrue(!CustomEndpointValidator.isPublicUnicast(InetAddress.getByName("127.0.0.1")));
        assertTrue(!CustomEndpointValidator.isPublicUnicast(InetAddress.getByName("169.254.169.254")));
        assertTrue(!CustomEndpointValidator.isPublicUnicast(InetAddress.getByName("192.0.2.1")));
        assertTrue(!CustomEndpointValidator.isPublicUnicast(InetAddress.getByName("198.18.0.1")));
        assertTrue(!CustomEndpointValidator.isPublicUnicast(InetAddress.getByName("203.0.113.1")));
    }

    @Test
    void allowsPublicEndpoint() {
        assertDoesNotThrow(() -> validator.validateEndpoint("https://api.example-provider.com/v1"));
    }

    @Test
    void rejectsDnsResolvingToPrivate() {
        var poisoned = validatorFor(Map.of("evil.example.com", new String[] { "10.9.9.9" }));
        assertThrows(DomainException.class,
                () -> poisoned.validateEndpoint("https://evil.example.com/v1"));
    }

    @Test
    void rejectsRedirectToPrivate() {
        var poisoned = validatorFor(Map.of("evil.example.com", new String[] { "192.168.9.9" }));
        assertThrows(DomainException.class,
                () -> poisoned.validateRedirectTarget("https://evil.example.com/callback"));
    }

    @Test
    void rejectsForbiddenAuthHeaders() {
        assertThrows(DomainException.class,
                () -> validator.validateAuthHeaderName("API_KEY_HEADER", "Authorization"));
        assertThrows(DomainException.class,
                () -> validator.validateAuthHeaderName("API_KEY_HEADER", "Proxy-Authorization"));
        assertThrows(DomainException.class,
                () -> validator.validateAuthHeaderName("API_KEY_HEADER", "Content-Length"));
        assertDoesNotThrow(
                () -> validator.validateAuthHeaderName("API_KEY_HEADER", "X-Api-Key"));
        assertDoesNotThrow(() -> validator.validateAuthHeaderName("BEARER", null));
    }
}
