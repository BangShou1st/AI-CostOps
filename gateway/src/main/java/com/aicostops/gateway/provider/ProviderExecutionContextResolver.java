package com.aicostops.gateway.provider;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.cache.GatewayConfigCache;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.request.GatewayRequestService.DispatchResult;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves the Provider call boundary from one already-frozen route.
 * Endpoint and protocol fields come from {@link DispatchResult}; authentication
 * metadata comes only from the exact connection profile named by that route.
 */
@Component
public class ProviderExecutionContextResolver {

    private static final Set<String> AUTH_TYPES = Set.of("BEARER", "API_KEY_HEADER", "NONE");

    private final GatewayReadMapper readMapper;
    private final ProviderCredentialDecryptor credentialDecryptor;
    private final GatewayConfigCache configCache;

    public ProviderExecutionContextResolver(
            GatewayReadMapper readMapper, ProviderCredentialDecryptor credentialDecryptor) {
        this(readMapper, credentialDecryptor, null);
    }

    @Autowired
    public ProviderExecutionContextResolver(
            GatewayReadMapper readMapper,
            ProviderCredentialDecryptor credentialDecryptor,
            GatewayConfigCache configCache) {
        this.readMapper = readMapper;
        this.credentialDecryptor = credentialDecryptor;
        this.configCache = configCache;
    }

    public ProviderCallContext resolve(GatewayPrincipal principal, DispatchResult result) {
        var profile = configCache != null
                ? configCache.getConnectionProfile(result.providerConnectionProfileId())
                : readMapper.findConnectionProfileById(
                        principal.organizationId(), result.providerConnectionProfileId());
        if (profile == null || profile.id() != result.providerConnectionProfileId()) {
            throw new ProviderContextResolutionException("Frozen connection profile is missing");
        }
        if (profile.orgId() != principal.organizationId()) {
            throw new ProviderContextResolutionException("Frozen connection profile organization mismatch");
        }
        if (profile.providerAccountId() != result.providerAccountId()) {
            throw new ProviderContextResolutionException("Frozen connection profile account mismatch");
        }
        if (!AUTH_TYPES.contains(profile.authType())) {
            throw new ProviderContextResolutionException("Frozen connection profile auth type is invalid");
        }

        final String credentialType;
        final byte[] secret;
        if ("NONE".equals(profile.authType())) {
            credentialType = "NONE";
            secret = null;
        } else {
            var credential = credentialDecryptor.decrypt(
                    principal.organizationId(), result.providerAccountId());
            credentialType = credential.credentialType();
            secret = credential.secret();
        }
        return new ProviderCallContext(
                result.adapterCode(),
                result.providerAccountId(),
                result.providerModelId(),
                result.providerModelName(),
                result.pricingVersionId(),
                result.currency(),
                result.baseUrl(),
                credentialType,
                secret,
                result.routeDecisionId(),
                result.providerConnectionProfileId(),
                result.completionPath(),
                result.protocolCode(),
                result.networkPolicy(),
                profile.authHeaderName());
    }
}
