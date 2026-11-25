package com.aicostops.gateway.cache;

import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.routing.ResolvedRoutingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Serializable L1/L2 cache records for the Gateway configuration objects. */
public final class GatewayConfigCacheModels {

    private GatewayConfigCacheModels() {
    }

    public record CachedCredential(
            long id,
            long orgId,
            String credentialPrefix,
            byte[] secretDigest,
            short secretDigestVersion,
            String principalType,
            String credentialOrigin,
            Long organizationMemberId,
            Long serviceIdentityId,
            long projectId,
            String financialScopeType,
            long financialScopeId,
            String budgetEnforcementMode,
            String status,
            Instant expiresAt) {

        public static CachedCredential fromRow(GatewayReadMapper.CredentialRow row) {
            return new CachedCredential(
                    row.id(), row.orgId(), row.credentialPrefix(), row.secretDigest(),
                    row.secretDigestVersion(), row.principalType(), row.credentialOrigin(),
                    row.organizationMemberId(), row.serviceIdentityId(), row.projectId(),
                    row.financialScopeType(), row.financialScopeId(),
                    row.budgetEnforcementMode(), row.status(), row.expiresAt());
        }

        public GatewayReadMapper.CredentialRow toRow() {
            return new GatewayReadMapper.CredentialRow(
                    id, orgId, credentialPrefix, secretDigest, secretDigestVersion,
                    principalType, credentialOrigin, organizationMemberId, serviceIdentityId,
                    projectId, financialScopeType, financialScopeId,
                    budgetEnforcementMode, status, expiresAt);
        }
    }

    public record CachedPrincipalState(
            String principalStatus,
            String projectStatus,
            String financialScopeStatus,
            List<Long> activeModelIds) {

        public CachedPrincipalState {
            activeModelIds = activeModelIds == null ? List.of() : List.copyOf(activeModelIds);
        }

        public boolean allowsModel(long modelId) {
            return activeModelIds.contains(modelId);
        }

        public Set<Long> activeModelIdSet() {
            return activeModelIds.stream().collect(Collectors.toUnmodifiableSet());
        }
    }

    public record CachedModelEntry(
            long id,
            String modelKey,
            Long ownerOrgId,
            String status,
            Integer defaultMaxOutputTokens,
            int maxOutputTokens) {

        public GatewayReadMapper.ModelRow toRow() {
            return new GatewayReadMapper.ModelRow(
                    id, modelKey, status, defaultMaxOutputTokens, maxOutputTokens);
        }
    }

    /**
     * Visible models for an organization, ordered with org-private entries first
     * ({@code ownerOrgId DESC, id ASC}) so {@link #findVisibleByKey(String)} preserves
     * org-private overriding global.
     */
    public record CachedModelCatalog(List<CachedModelEntry> models) {

        public CachedModelCatalog {
            models = models == null ? List.of() : List.copyOf(models);
        }

        public CachedModelEntry findVisibleByKey(String modelKey) {
            if (modelKey == null) {
                return null;
            }
            for (var model : models) {
                if (modelKey.equals(model.modelKey())) {
                    return model;
                }
            }
            return null;
        }

        public CachedModelEntry findById(long modelId) {
            for (var model : models) {
                if (model.id() == modelId) {
                    return model;
                }
            }
            return null;
        }
    }

    public record CachedRouteConfig(
            List<CachedRoutingPolicy> policies,
            List<CachedPricingWindow> pricingWindows) {

        public CachedRouteConfig {
            policies = policies == null ? List.of() : List.copyOf(policies);
            pricingWindows = pricingWindows == null ? List.of() : List.copyOf(pricingWindows);
        }

        /**
         * Resolves the active policy for {@code projectId} (exact project policy wins,
         * else org default) and binds each candidate's current pricing window against
         * {@code now} in memory.
         */
        public ResolvedRoutingPolicy resolvePolicy(long projectId, Instant now) {
            CachedRoutingPolicy exact = null;
            CachedRoutingPolicy orgDefault = null;
            for (var policy : policies) {
                if (policy.projectId() != null && Objects.equals(policy.projectId(), projectId)) {
                    exact = policy;
                    break;
                }
                if (policy.projectId() == null && orgDefault == null) {
                    orgDefault = policy;
                }
            }
            var selected = exact != null ? exact : orgDefault;
            if (selected == null) {
                return null;
            }
            var resolvedCandidates = selected.candidates().stream()
                    .map(candidate -> toCandidate(candidate,
                            findActivePricing(candidate.providerAccountId(),
                                    candidate.providerModelId(), now)))
                    .toList();
            return new ResolvedRoutingPolicy(
                    selected.id(), selected.version(), selected.projectId(),
                    selected.modelId(), resolvedCandidates);
        }

        public CachedPricingWindow findActivePricing(
                long providerAccountId, long providerModelId, Instant now) {
            for (var window : pricingWindows) {
                if (window.providerAccountId() == providerAccountId
                        && window.providerModelId() == providerModelId
                        && !window.effectiveFrom().isAfter(now)
                        && (window.effectiveTo() == null || window.effectiveTo().isAfter(now))) {
                    return window;
                }
            }
            return null;
        }

        private static ResolvedRoutingPolicy.Candidate toCandidate(
                CachedCandidate candidate, CachedPricingWindow pricing) {
            return new ResolvedRoutingPolicy.Candidate(
                    candidate.id(),
                    candidate.priority(),
                    candidate.providerAccountId(),
                    candidate.providerCode(),
                    candidate.providerModelId(),
                    candidate.providerModelName(),
                    pricing == null ? null : pricing.pricingVersionId(),
                    pricing == null ? null : pricing.currency(),
                    candidate.baseUrl(),
                    candidate.adapterCode(),
                    candidate.credentialReady(),
                    candidate.routingEligible(),
                    candidate.chatCapable(),
                    candidate.streamCapable(),
                    candidate.providerModelLogicalModelId(),
                    candidate.providerModelProviderCode(),
                    candidate.providerConnectionProfileId(),
                    candidate.completionPath(),
                    candidate.protocolCode(),
                    candidate.networkPolicy(),
                    candidate.providerModelPairValid());
        }
    }

    public record CachedRoutingPolicy(
            long id,
            int version,
            Long projectId,
            long modelId,
            List<CachedCandidate> candidates) {

        public CachedRoutingPolicy {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record CachedCandidate(
            long id,
            int priority,
            long providerAccountId,
            String providerCode,
            long providerModelId,
            String providerModelName,
            long providerConnectionProfileId,
            String baseUrl,
            String completionPath,
            String protocolCode,
            String networkPolicy,
            String adapterCode,
            boolean credentialReady,
            boolean providerModelPairValid,
            boolean routingEligible,
            boolean chatCapable,
            boolean streamCapable,
            long providerModelLogicalModelId,
            String providerModelProviderCode) {
    }

    public record CachedPricingWindow(
            long pricingVersionId,
            long providerAccountId,
            long providerModelId,
            String currency,
            Instant effectiveFrom,
            Instant effectiveTo) {
    }

    public record CachedPricingVersion(
            long pricingVersionId,
            long orgId,
            String status,
            List<CachedPricingRate> rates) {

        public CachedPricingVersion {
            rates = rates == null ? List.of() : List.copyOf(rates);
        }

        public boolean isImmutableCacheable() {
            return ("ACTIVE".equals(status) || "RETIRED".equals(status)) && !rates.isEmpty();
        }
    }

    public record CachedPricingRate(String dimensionCode, long unitQuantity, BigDecimal unitPrice) {
    }

    public record CachedConnectionProfile(
            long id,
            long orgId,
            long providerAccountId,
            String authType,
            String authHeaderName,
            String networkPolicy) {

        public static CachedConnectionProfile fromRow(GatewayReadMapper.ConnectionProfileRow row) {
            return new CachedConnectionProfile(
                    row.id(), row.orgId(), row.providerAccountId(),
                    row.authType(), row.authHeaderName(), row.networkPolicy());
        }

        public GatewayReadMapper.ConnectionProfileRow toRow() {
            return new GatewayReadMapper.ConnectionProfileRow(
                    id, orgId, providerAccountId, authType, authHeaderName, networkPolicy);
        }
    }
}
