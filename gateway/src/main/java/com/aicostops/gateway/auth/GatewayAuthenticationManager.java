package com.aicostops.gateway.auth;

import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reactive authentication for the billable Data Plane. The raw key is parsed,
 * its prefix resolves the credential row, and the secret part is compared in
 * constant time through the keyed HMAC digest. Authorization failures
 * (inactive principal/project/financial scope) surface as 403
 * {@code GATEWAY_FORBIDDEN}; invalid or unknown keys return an empty result
 * so the filter chain produces 401 {@code GATEWAY_AUTH_INVALID}.
 */
@Component
public class GatewayAuthenticationManager implements ReactiveAuthenticationManager {

    private final GatewayReadMapper readMapper;
    private final GatewayProperties properties;
    private final BlockingIoScheduler blockingIo;
    private final Clock clock;

    public GatewayAuthenticationManager(
            GatewayReadMapper readMapper,
            GatewayProperties properties,
            BlockingIoScheduler blockingIo,
            Clock clock) {
        this.readMapper = readMapper;
        this.properties = properties;
        this.blockingIo = blockingIo;
        this.clock = clock;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        var credentials = authentication.getCredentials();
        if (!(credentials instanceof String rawKey)) {
            return Mono.empty();
        }
        return blockingIo.call(() -> authenticateBlocking(rawKey))
                .map(principal -> new UsernamePasswordAuthenticationToken(principal, null));
    }

    private GatewayPrincipal authenticateBlocking(String rawKey) {
        var now = java.time.Instant.now(clock);
        GatewayApiKeyParser.ParsedKey parsed;
        try {
            parsed = GatewayApiKeyParser.parse(rawKey);
        } catch (IllegalArgumentException ex) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_AUTH_INVALID, ex.getMessage());
        }

        var row = readMapper.findCredentialByPrefix(parsed.prefix());
        if (row == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_AUTH_INVALID, "Invalid Gateway key");
        }
        var expected = digestSecret(parsed.secretPart());
        if (expected.length != row.secretDigest().length
                || !MessageDigest.isEqual(expected, row.secretDigest())) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_AUTH_INVALID, "Invalid Gateway key");
        }
        if (!"ACTIVE".equals(row.status())) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_AUTH_INVALID, "Gateway key is not active");
        }
        if (row.expiresAt() != null && row.expiresAt().isBefore(now)) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_AUTH_INVALID, "Gateway key has expired");
        }

        validatePrincipalActive(row);
        validateProjectActive(row);
        validateFinancialScopeActive(row);

        return new GatewayPrincipal(
                row.id(),
                row.orgId(),
                row.projectId(),
                row.principalType(),
                row.organizationMemberId(),
                row.serviceIdentityId(),
                row.financialScopeType(),
                row.financialScopeId(),
                row.budgetEnforcementMode());
    }

    private void validatePrincipalActive(GatewayReadMapper.CredentialRow row) {
        String status;
        if ("SERVICE".equals(row.principalType())) {
            status = readMapper.findServiceIdentityStatus(row.serviceIdentityId());
        } else if ("HUMAN_MEMBER".equals(row.principalType())) {
            status = readMapper.findOrganizationMemberStatus(row.organizationMemberId());
        } else {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_AUTH_INVALID, "Unknown principal type");
        }
        if (!"ACTIVE".equals(status)) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "Principal is not active");
        }
    }

    private void validateProjectActive(GatewayReadMapper.CredentialRow row) {
        var status = readMapper.findProjectStatus(row.projectId(), row.orgId());
        if (!"ACTIVE".equals(status)) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "Project is not active");
        }
    }

    private void validateFinancialScopeActive(GatewayReadMapper.CredentialRow row) {
        String status = switch (row.financialScopeType()) {
            case "PROJECT" -> readMapper.findProjectStatus(row.financialScopeId(), row.orgId());
            case "TEAM" -> readMapper.findTeamStatus(row.financialScopeId(), row.orgId());
            case "COST_CENTER" -> readMapper.findCostCenterStatus(row.financialScopeId(), row.orgId());
            default -> null;
        };
        if (!"ACTIVE".equals(status)) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "Financial scope is not active");
        }
    }

    private byte[] digestSecret(String secretPart) {
        var key = Base64.getDecoder().decode(properties.getCredentialHmacKeyV1().trim());
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(secretPart.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", ex);
        }
    }
}