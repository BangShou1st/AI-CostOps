package com.aicostops.gateway.request;

import com.aicostops.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Frozen AIC-092 request identity: domain-separated keyed digests computed
 * over the exact accepted method/path/raw UTF-8 JSON bytes. Raw Idempotency
 * keys and prompt bodies are never persisted or logged.
 */
@Component
public class RequestIdentityService {

    public static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[!-~]{1,128}$");
    private static final byte[] REQUEST_DOMAIN_PREFIX =
            "request\0POST\0/v1/chat/completions\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IDEM_DOMAIN_PREFIX = "idem\0".getBytes(StandardCharsets.UTF_8);

    private final byte[] requestHmacKey;

    public RequestIdentityService(GatewayProperties properties) {
        this.requestHmacKey = Base64.getDecoder().decode(properties.getRequestHmacKeyV1().trim());
    }

    public void validateIdempotencyKey(String rawIdempotencyKey) {
        if (rawIdempotencyKey == null
                || !IDEMPOTENCY_KEY_PATTERN.matcher(rawIdempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be 1..128 visible ASCII characters");
        }
    }

    public byte[] idempotencyKeyDigest(String rawIdempotencyKey) {
        return hmac256(IDEM_DOMAIN_PREFIX, rawIdempotencyKey.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] requestFingerprint(byte[] rawUtf8BodyBytes) {
        return hmac256(REQUEST_DOMAIN_PREFIX, rawUtf8BodyBytes);
    }

    public String newPublicRequestId() {
        return "gwr_" + UUID.randomUUID();
    }

    public String newRouteDecisionId() {
        return "grd_" + UUID.randomUUID();
    }

    private byte[] hmac256(byte[] domainPrefix, byte[] value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(requestHmacKey, "HmacSHA256"));
            mac.update(domainPrefix);
            return mac.doFinal(value);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", ex);
        }
    }
}