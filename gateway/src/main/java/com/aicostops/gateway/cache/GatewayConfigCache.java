package com.aicostops.gateway.cache;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedCandidate;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedConnectionProfile;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedCredential;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedModelCatalog;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedModelEntry;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedPricingRate;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedPricingVersion;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedPricingWindow;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedPrincipalState;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedRouteConfig;
import com.aicostops.gateway.cache.GatewayConfigCacheModels.CachedRoutingPolicy;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.provider.ProviderCredentialDecryptor.DecryptedCredential;
import com.aicostops.gateway.routing.RoutingPolicyMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Two-level Gateway configuration cache (Caffeine L1 + Redis L2) with per-key
 * single-flight loading, null-sentinel anti-penetration caching, ±10% Redis TTL
 * jitter, and fail-open behavior on Redis outages.
 *
 * <p>Financial invariants:
 * <ul>
 *   <li>{@code billing_period}, {@code budget}, and {@code budget_reservation} are NEVER cached.</li>
 *   <li>Decrypted Provider credentials are cached ONLY in local Caffeine memory (5 min TTL) and
 *       NEVER written to Redis or logged.</li>
 * </ul>
 */
@Component
public class GatewayConfigCache {

    public static final Duration CRED_LOCAL_TTL = Duration.ofSeconds(30);
    public static final Duration CRED_REDIS_TTL = Duration.ofMinutes(5);
    public static final Duration PRINCIPAL_LOCAL_TTL = Duration.ofSeconds(10);
    public static final Duration PRINCIPAL_REDIS_TTL = Duration.ofMinutes(1);
    public static final Duration MODEL_LOCAL_TTL = Duration.ofSeconds(60);
    public static final Duration MODEL_REDIS_TTL = Duration.ofMinutes(10);
    public static final Duration ROUTE_LOCAL_TTL = Duration.ofSeconds(30);
    public static final Duration ROUTE_REDIS_TTL = Duration.ofMinutes(5);
    public static final Duration CONN_LOCAL_TTL = Duration.ofSeconds(60);
    public static final Duration CONN_REDIS_TTL = Duration.ofMinutes(10);
    public static final Duration PROVIDER_CRED_LOCAL_TTL = Duration.ofMinutes(5);
    public static final Duration NULL_LOCAL_TTL = Duration.ofSeconds(10);
    public static final Duration NULL_REDIS_TTL = Duration.ofSeconds(60);

    private static final String NULL_SENTINEL_PAYLOAD = "\"__NULL__\"";
    private static final Logger log = LoggerFactory.getLogger(GatewayConfigCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final GatewayReadMapper readMapper;
    private final RoutingPolicyMapper routingPolicyMapper;
    private final BudgetReservationMapper reservationMapper;
    private final ConcurrentHashMap<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong globalEpoch = new AtomicLong();
    private final AtomicBoolean redisTouched = new AtomicBoolean(false);

    private final CacheSlot<CachedCredential> credSlot;
    private final CacheSlot<CachedPrincipalState> principalSlot;
    private final CacheSlot<CachedModelCatalog> modelSlot;
    private final CacheSlot<CachedRouteConfig> routeSlot;
    private final CacheSlot<CachedConnectionProfile> connSlot;

    // Pricing versions: permanent when ACTIVE/RETIRED with non-empty rates; short null-sentinel
    // when missing; DRAFT or empty rates are never permanently cached.
    private final Cache<String, CachedPricingVersion> pricingPermanentL1;
    private final Cache<String, Boolean> pricingNegativeL1;
    private final AtomicLong pricingEpoch = new AtomicLong();

    // Provider credentials: LOCAL MEMORY ONLY (never written to Redis).
    private final Cache<String, DecryptedCredential> providerCredLocalL1;
    private final AtomicLong providerCredEpoch = new AtomicLong();

    @Autowired
    public GatewayConfigCache(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectMapper objectMapper,
            GatewayReadMapper readMapper,
            RoutingPolicyMapper routingPolicyMapper,
            BudgetReservationMapper reservationMapper,
            Clock clock) {
        this(redisProvider == null ? null : redisProvider.getIfAvailable(),
                objectMapper, readMapper, routingPolicyMapper, reservationMapper, clock);
    }

    public GatewayConfigCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            GatewayReadMapper readMapper,
            RoutingPolicyMapper routingPolicyMapper,
            BudgetReservationMapper reservationMapper,
            Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.readMapper = readMapper;
        this.routingPolicyMapper = routingPolicyMapper;
        this.reservationMapper = reservationMapper;
        var ticker = tickerFromClock(clock == null ? Clock.systemUTC() : clock);

        this.credSlot = new CacheSlot<>(
                GatewayConfigCacheKeys.PREFIX_CRED, CachedCredential.class,
                CRED_LOCAL_TTL, CRED_REDIS_TTL, 50_000, ticker);
        this.principalSlot = new CacheSlot<>(
                GatewayConfigCacheKeys.PREFIX_PRINCIPAL, CachedPrincipalState.class,
                PRINCIPAL_LOCAL_TTL, PRINCIPAL_REDIS_TTL, 50_000, ticker);
        this.modelSlot = new CacheSlot<>(
                GatewayConfigCacheKeys.PREFIX_MODEL, CachedModelCatalog.class,
                MODEL_LOCAL_TTL, MODEL_REDIS_TTL, 10_000, ticker);
        this.routeSlot = new CacheSlot<>(
                GatewayConfigCacheKeys.PREFIX_ROUTE, CachedRouteConfig.class,
                ROUTE_LOCAL_TTL, ROUTE_REDIS_TTL, 50_000, ticker);
        this.connSlot = new CacheSlot<>(
                GatewayConfigCacheKeys.PREFIX_CONN, CachedConnectionProfile.class,
                CONN_LOCAL_TTL, CONN_REDIS_TTL, 20_000, ticker);
        this.pricingPermanentL1 = Caffeine.newBuilder()
                .maximumSize(10_000)
                .build();
        this.pricingNegativeL1 = Caffeine.newBuilder()
                .maximumSize(10_000)
                .ticker(ticker)
                .expireAfterWrite(NULL_LOCAL_TTL)
                .build();
        this.providerCredLocalL1 = Caffeine.newBuilder()
                .maximumSize(10_000)
                .ticker(ticker)
                .expireAfterWrite(PROVIDER_CRED_LOCAL_TTL)
                .build();
    }

    public GatewayReadMapper.CredentialRow getCredentialByDigest(
            String digestHex, String prefix, byte[] expectedDigest) {
        assertNotEventLoop();
        var cached = getOrLoad(credSlot, digestHex, () -> {
            var row = readMapper.findCredentialByPrefix(prefix);
            if (row == null || row.secretDigest() == null
                    || expectedDigest.length != row.secretDigest().length
                    || !MessageDigest.isEqual(expectedDigest, row.secretDigest())) {
                return null;
            }
            return CachedCredential.fromRow(row);
        });
        return cached == null ? null : cached.toRow();
    }

    public CachedPrincipalState getPrincipalState(GatewayReadMapper.CredentialRow row) {
        assertNotEventLoop();
        return getOrLoad(principalSlot, Long.toString(row.id()), () -> loadPrincipalState(
                row.id(), row.orgId(), row.principalType(),
                row.organizationMemberId(), row.serviceIdentityId(),
                row.projectId(), row.financialScopeType(), row.financialScopeId()));
    }

    public CachedPrincipalState getPrincipalState(GatewayPrincipal principal) {
        assertNotEventLoop();
        return getOrLoad(principalSlot, Long.toString(principal.credentialId()), () -> loadPrincipalState(
                principal.credentialId(), principal.organizationId(), principal.principalType(),
                principal.organizationMemberId(), principal.serviceIdentityId(),
                principal.projectId(), principal.financialScopeType(), principal.financialScopeId()));
    }

    public GatewayReadMapper.ModelRow getVisibleModelByKey(long orgId, String modelKey) {
        assertNotEventLoop();
        var catalog = getModelCatalog(orgId);
        if (catalog == null) {
            return null;
        }
        var entry = catalog.findVisibleByKey(modelKey);
        return entry == null ? null : entry.toRow();
    }

    public GatewayReadMapper.ModelRow getModelById(long orgId, long modelId) {
        assertNotEventLoop();
        var catalog = getModelCatalog(orgId);
        if (catalog == null) {
            return null;
        }
        var entry = catalog.findById(modelId);
        return entry == null ? null : entry.toRow();
    }

    public CachedModelCatalog getModelCatalog(long orgId) {
        assertNotEventLoop();
        return getOrLoad(modelSlot, Long.toString(orgId), () -> {
            var rows = readMapper.findVisibleModelsForOrg(orgId);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            var entries = rows.stream()
                    .map(r -> new CachedModelEntry(
                            r.id(), r.modelKey(), r.ownerOrgId(), r.status(),
                            r.defaultMaxOutputTokens(), r.maxOutputTokens()))
                    .toList();
            return new CachedModelCatalog(entries);
        });
    }

    public CachedRouteConfig getRouteConfig(long orgId, long modelId) {
        assertNotEventLoop();
        var key = orgId + ":" + modelId;
        return getOrLoad(routeSlot, key, () -> loadRouteConfig(orgId, modelId));
    }

    public GatewayReadMapper.ConnectionProfileRow getConnectionProfile(long profileId) {
        assertNotEventLoop();
        var cached = getOrLoad(connSlot, Long.toString(profileId), () -> {
            var row = readMapper.findConnectionProfileByIdUnscoped(profileId);
            return row == null ? null : CachedConnectionProfile.fromRow(row);
        });
        return cached == null ? null : cached.toRow();
    }

    public List<BudgetReservationMapper.PricingRateRow> getPricingRates(
            long callerOrgId, long pricingVersionId) {
        var version = getPricingVersion(pricingVersionId);
        if (version == null || version.orgId() != callerOrgId) {
            return List.of();
        }
        return version.rates().stream()
                .map(r -> new BudgetReservationMapper.PricingRateRow(
                        r.dimensionCode(), r.unitQuantity(), r.unitPrice()))
                .toList();
    }

    public List<String> getPricingDimensions(long callerOrgId, long pricingVersionId) {
        var version = getPricingVersion(pricingVersionId);
        if (version == null || version.orgId() != callerOrgId) {
            return List.of();
        }
        return version.rates().stream()
                .map(CachedPricingRate::dimensionCode)
                .toList();
    }

    public CachedPricingVersion getPricingVersion(long pricingVersionId) {
        assertNotEventLoop();
        var key = Long.toString(pricingVersionId);
        var l1 = pricingPermanentL1.getIfPresent(key);
        if (l1 != null) {
            return l1;
        }
        if (Boolean.TRUE.equals(pricingNegativeL1.getIfPresent(key))) {
            return null;
        }

        var flightKey = GatewayConfigCacheKeys.pricingKey(pricingVersionId);
        @SuppressWarnings("unchecked")
        var created = new CompletableFuture<CacheEntry<CachedPricingVersion>>();
        var existing = inFlight.putIfAbsent(flightKey, created);
        if (existing != null) {
            return awaitInFlight(existing);
        }
        try {
            var recheck = pricingPermanentL1.getIfPresent(key);
            if (recheck != null) {
                created.complete(CacheEntry.of(recheck));
                return recheck;
            }
            if (Boolean.TRUE.equals(pricingNegativeL1.getIfPresent(key))) {
                created.complete(CacheEntry.nullSentinel());
                return null;
            }

            long startPricingEpoch = pricingEpoch.get();
            long startGlobalEpoch = globalEpoch.get();

            var l2 = readFromRedis(flightKey, CachedPricingVersion.class);
            if (l2 != null) {
                if (pricingEpoch.get() == startPricingEpoch && globalEpoch.get() == startGlobalEpoch) {
                    if (l2.isNull()) {
                        pricingNegativeL1.put(key, Boolean.TRUE);
                    } else if (l2.value().isImmutableCacheable()) {
                        pricingPermanentL1.put(key, l2.value());
                    }
                }
                created.complete(l2);
                return l2.value();
            }

            var meta = reservationMapper.findPricingVersionMetadata(pricingVersionId);
            if (meta == null) {
                if (pricingEpoch.get() == startPricingEpoch && globalEpoch.get() == startGlobalEpoch) {
                    writeToRedis(flightKey, CacheEntry.nullSentinel(), NULL_REDIS_TTL, false);
                    pricingNegativeL1.put(key, Boolean.TRUE);
                }
                created.complete(CacheEntry.nullSentinel());
                return null;
            }

            var rateRows = reservationMapper.findPricingRatesByVersion(meta.orgId(), pricingVersionId);
            var rates = rateRows == null ? List.<CachedPricingRate>of()
                    : rateRows.stream()
                            .map(r -> new CachedPricingRate(r.dimensionCode(), r.unitQuantity(), r.unitPrice()))
                            .toList();
            var loaded = new CachedPricingVersion(meta.id(), meta.orgId(), meta.status(), rates);
            // Only activated/retired versions with non-empty rates are permanently cached.
            // DRAFT or empty versions bypass permanent L1/L2 so later edits/activation are visible.
            if (loaded.isImmutableCacheable()
                    && pricingEpoch.get() == startPricingEpoch
                    && globalEpoch.get() == startGlobalEpoch) {
                var entry = CacheEntry.of(loaded);
                writeToRedis(flightKey, entry, Duration.ZERO, true);
                pricingPermanentL1.put(key, loaded);
            }
            created.complete(CacheEntry.of(loaded));
            return loaded;
        } catch (Throwable ex) {
            created.completeExceptionally(ex);
            throw propagate(ex);
        } finally {
            inFlight.remove(flightKey, created);
        }
    }

    /**
     * Local-only Caffeine cache (5 min TTL) for decrypted Provider credentials.
     * NEVER reads from or writes to Redis, and always returns a defensive copy
     * of {@code byte[] secret}.
     */
    public DecryptedCredential getOrLoadProviderCredentialLocalOnly(
            long orgId, long providerAccountId, Supplier<DecryptedCredential> loader) {
        assertNotEventLoop();
        var key = orgId + ":" + providerAccountId;
        var cached = providerCredLocalL1.getIfPresent(key);
        if (cached != null) {
            return defensiveCopy(cached);
        }

        var flightKey = "local:provider_cred:" + key;
        var created = new CompletableFuture<CacheEntry<DecryptedCredential>>();
        var existing = inFlight.putIfAbsent(flightKey, created);
        if (existing != null) {
            DecryptedCredential resolved = awaitInFlight(existing);
            return resolved == null ? null : defensiveCopy(resolved);
        }
        try {
            var recheck = providerCredLocalL1.getIfPresent(key);
            if (recheck != null) {
                created.complete(CacheEntry.of(recheck));
                return defensiveCopy(recheck);
            }
            long startCredEpoch = providerCredEpoch.get();
            long startGlobalEpoch = globalEpoch.get();
            var loaded = loader.get();
            if (loaded != null) {
                var stored = defensiveCopy(loaded);
                if (providerCredEpoch.get() == startCredEpoch && globalEpoch.get() == startGlobalEpoch) {
                    providerCredLocalL1.put(key, stored);
                }
                created.complete(CacheEntry.of(stored));
                return defensiveCopy(stored);
            }
            created.complete(CacheEntry.nullSentinel());
            return null;
        } catch (Throwable ex) {
            created.completeExceptionally(ex);
            throw propagate(ex);
        } finally {
            inFlight.remove(flightKey, created);
        }
    }

    // ------------------------------------------------------------------ //
    // Local Caffeine invalidation hooks (Pub/Sub + disconnect/reconnect)
    // ------------------------------------------------------------------ //

    public void invalidateCredential(String digestHex) {
        if (digestHex != null && !digestHex.isBlank()) {
            credSlot.invalidate(digestHex);
        }
    }

    public void invalidatePrincipal(String credentialId) {
        if (credentialId != null && !credentialId.isBlank()) {
            principalSlot.invalidate(credentialId);
        }
    }

    public void invalidateModelCatalog(String orgId) {
        if (orgId != null && !orgId.isBlank()) {
            modelSlot.invalidate(orgId);
        }
    }

    public void invalidateAllModelCatalogs() {
        modelSlot.invalidateAll();
    }

    public void invalidateRoute(String orgAndModelKey) {
        if (orgAndModelKey != null && !orgAndModelKey.isBlank()) {
            routeSlot.invalidate(orgAndModelKey);
        }
    }

    public void invalidateOrgRoutes(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return;
        }
        var prefix = orgId + ":";
        routeSlot.invalidateMatching(k -> k.startsWith(prefix));
    }

    public void invalidateAllRoutes() {
        routeSlot.invalidateAll();
    }

    public void invalidatePricing(String pricingVersionId) {
        if (pricingVersionId == null || pricingVersionId.isBlank()) {
            return;
        }
        pricingEpoch.incrementAndGet();
        pricingPermanentL1.invalidate(pricingVersionId);
        pricingNegativeL1.invalidate(pricingVersionId);
    }

    public void invalidateConnectionProfile(String connId) {
        if (connId != null && !connId.isBlank()) {
            connSlot.invalidate(connId);
        }
    }

    public void invalidateProviderCredential(String orgAndAccountKey) {
        if (orgAndAccountKey != null && !orgAndAccountKey.isBlank()) {
            providerCredEpoch.incrementAndGet();
            providerCredLocalL1.invalidate(orgAndAccountKey);
        }
    }

    /** Clears ALL local Caffeine caches (e.g. on Pub/Sub disconnect/reconnect). */
    public void invalidateAll() {
        globalEpoch.incrementAndGet();
        credSlot.invalidateAll();
        principalSlot.invalidateAll();
        modelSlot.invalidateAll();
        routeSlot.invalidateAll();
        connSlot.invalidateAll();
        pricingEpoch.incrementAndGet();
        pricingPermanentL1.invalidateAll();
        pricingNegativeL1.invalidateAll();
        providerCredEpoch.incrementAndGet();
        providerCredLocalL1.invalidateAll();
    }

    /** Test-only reset clearing all local caches and any {@code cfg:*} keys in Redis. */
    public void resetForTests() {
        invalidateAll();
        if (redis != null && redisTouched.compareAndSet(true, false)) {
            try {
                var keys = new ArrayList<String>();
                var cursor = redis.scan(ScanOptions.scanOptions().match("cfg:*").count(500).build());
                if (cursor != null) {
                    try (cursor) {
                        while (cursor.hasNext()) {
                            keys.add(cursor.next());
                        }
                    }
                }
                if (!keys.isEmpty()) {
                    redis.delete(keys);
                }
            } catch (RuntimeException ex) {
                log.debug("Ignoring Redis reset failure during test cleanup", ex);
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Core read path: L1 -> single-flight -> L2 -> MySQL
    // ------------------------------------------------------------------ //

    private <T> T getOrLoad(CacheSlot<T> slot, String rawKey, Supplier<T> dbLoader) {
        var l1 = slot.getL1(rawKey);
        if (l1 != null) {
            return l1.value();
        }

        var flightKey = slot.redisKey(rawKey);
        var created = new CompletableFuture<CacheEntry<T>>();
        var existing = inFlight.putIfAbsent(flightKey, created);
        if (existing != null) {
            return awaitInFlight(existing);
        }
        try {
            var recheck = slot.getL1(rawKey);
            if (recheck != null) {
                created.complete(recheck);
                return recheck.value();
            }

            long startSlotEpoch = slot.epoch();
            long startGlobalEpoch = globalEpoch.get();

            var l2 = readFromRedis(flightKey, slot.valueType());
            if (l2 != null) {
                if (slot.epoch() == startSlotEpoch && globalEpoch.get() == startGlobalEpoch) {
                    slot.putL1(rawKey, l2);
                }
                created.complete(l2);
                return l2.value();
            }

            T loaded = dbLoader.get();
            CacheEntry<T> entry = loaded == null ? CacheEntry.nullSentinel() : CacheEntry.of(loaded);
            if (slot.epoch() == startSlotEpoch && globalEpoch.get() == startGlobalEpoch) {
                var effectiveRedisTtl = loaded == null ? NULL_REDIS_TTL : slot.redisTtl();
                writeToRedis(flightKey, entry, effectiveRedisTtl, false);
                slot.putL1(rawKey, entry);
            }
            created.complete(entry);
            return loaded;
        } catch (Throwable ex) {
            created.completeExceptionally(ex);
            throw propagate(ex);
        } finally {
            inFlight.remove(flightKey, created);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitInFlight(CompletableFuture<?> future) {
        try {
            var entry = (CacheEntry<T>) future.get();
            return entry == null ? null : entry.value();
        } catch (ExecutionException ex) {
            throw propagate(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for Gateway config cache load", ex);
        }
    }

    private <T> CacheEntry<T> readFromRedis(String redisKey, Class<T> valueType) {
        if (redis == null) {
            return null;
        }
        try {
            redisTouched.set(true);
            var raw = redis.opsForValue().get(redisKey);
            if (raw == null) {
                return null;
            }
            if (NULL_SENTINEL_PAYLOAD.equals(raw)) {
                return CacheEntry.nullSentinel();
            }
            var value = objectMapper.readValue(raw, valueType);
            return value == null ? null : CacheEntry.of(value);
        } catch (RuntimeException ex) {
            log.debug("Redis L2 read failed for key {}, falling back to MySQL", redisKey, ex);
            return null;
        }
    }

    private <T> void writeToRedis(
            String redisKey, CacheEntry<T> entry, Duration baseTtl, boolean permanent) {
        if (redis == null) {
            return;
        }
        try {
            redisTouched.set(true);
            var payload = entry.isNull()
                    ? NULL_SENTINEL_PAYLOAD
                    : objectMapper.writeValueAsString(entry.value());
            if (permanent && !entry.isNull()) {
                redis.opsForValue().set(redisKey, payload);
            } else {
                redis.opsForValue().set(redisKey, payload, jitterTtl(baseTtl));
            }
        } catch (RuntimeException ex) {
            log.debug("Redis L2 write failed for key {}, continuing fail-open", redisKey, ex);
        }
    }

    static Duration jitterTtl(Duration baseTtl) {
        if (baseTtl == null || baseTtl.isZero() || baseTtl.isNegative()) {
            return baseTtl;
        }
        double factor = ThreadLocalRandom.current().nextDouble(0.9d, 1.1d);
        long jitteredMs = Math.max(1L, Math.round(baseTtl.toMillis() * factor));
        return Duration.ofMillis(jitteredMs);
    }

    // ------------------------------------------------------------------ //
    // MySQL loaders
    // ------------------------------------------------------------------ //

    private CachedPrincipalState loadPrincipalState(
            long credentialId,
            long orgId,
            String principalType,
            Long organizationMemberId,
            Long serviceIdentityId,
            long projectId,
            String financialScopeType,
            long financialScopeId) {
        final String principalStatus;
        if ("SERVICE".equals(principalType)) {
            principalStatus = serviceIdentityId == null ? null
                    : readMapper.findServiceIdentityStatus(serviceIdentityId);
        } else if ("HUMAN_MEMBER".equals(principalType)) {
            principalStatus = organizationMemberId == null ? null
                    : readMapper.findOrganizationMemberStatus(organizationMemberId);
        } else {
            principalStatus = null;
        }
        var projectStatus = readMapper.findProjectStatus(projectId, orgId);
        var financialScopeStatus = switch (financialScopeType) {
            case "PROJECT" -> financialScopeId == projectId
                    ? projectStatus : readMapper.findProjectStatus(financialScopeId, orgId);
            case "TEAM" -> readMapper.findTeamStatus(financialScopeId, orgId);
            case "COST_CENTER" -> readMapper.findCostCenterStatus(financialScopeId, orgId);
            default -> null;
        };
        var activeModels = readMapper.findActiveModelIds(credentialId);
        return new CachedPrincipalState(principalStatus, projectStatus, financialScopeStatus, activeModels);
    }

    private CachedRouteConfig loadRouteConfig(long orgId, long modelId) {
        var policyRows = routingPolicyMapper.findActivePoliciesForModel(orgId, modelId);
        if (policyRows == null || policyRows.isEmpty()) {
            return null;
        }
        var candidateRows = routingPolicyMapper.findActiveCandidatesForModel(orgId, modelId);
        var byPolicy = new LinkedHashMap<Long, List<CachedCandidate>>();
        if (candidateRows != null) {
            for (var row : candidateRows) {
                byPolicy.computeIfAbsent(row.routingPolicyId(), ignored -> new ArrayList<>())
                        .add(new CachedCandidate(
                                row.id(),
                                row.priority(),
                                valueOrMinusOne(row.providerAccountId()),
                                row.providerCode(),
                                valueOrMinusOne(row.providerModelId()),
                                row.providerModelName(),
                                valueOrMinusOne(row.providerConnectionProfileId()),
                                row.baseUrl(),
                                row.completionPath(),
                                row.protocolCode(),
                                row.networkPolicy(),
                                row.adapterCode(),
                                row.credentialReady(),
                                row.providerModelPairValid(),
                                row.routingEligible(),
                                row.chatCapable(),
                                row.streamCapable(),
                                valueOrMinusOne(row.providerModelLogicalModelId()),
                                row.providerModelProviderCode()));
            }
        }
        var policies = policyRows.stream()
                .map(p -> new CachedRoutingPolicy(
                        p.id(), p.version(), p.projectId(), p.modelId(),
                        byPolicy.getOrDefault(p.id(), List.of())))
                .toList();
        var windowRows = routingPolicyMapper.findActivePricingWindowsForModel(orgId, modelId);
        var windows = windowRows == null ? List.<CachedPricingWindow>of()
                : windowRows.stream()
                        .map(w -> new CachedPricingWindow(
                                w.pricingVersionId(), w.providerAccountId(), w.providerModelId(),
                                w.currency(), w.effectiveFrom(), w.effectiveTo()))
                        .toList();
        return new CachedRouteConfig(policies, windows);
    }

    private static long valueOrMinusOne(Long value) {
        return value == null ? -1L : value;
    }

    private static DecryptedCredential defensiveCopy(DecryptedCredential credential) {
        var secret = credential.secret();
        return new DecryptedCredential(
                credential.credentialType(),
                secret == null ? null : Arrays.copyOf(secret, secret.length));
    }

    private static void assertNotEventLoop() {
        if (Schedulers.isInNonBlockingThread()) {
            throw new IllegalStateException(
                    "GatewayConfigCache must not run on a non-blocking Reactor event-loop thread: "
                            + Thread.currentThread().getName());
        }
    }

    private static RuntimeException propagate(Throwable ex) {
        if (ex instanceof RuntimeException runtime) {
            return runtime;
        }
        if (ex instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(ex);
    }

    private static Ticker tickerFromClock(Clock clock) {
        Instant origin = clock.instant();
        return () -> Math.max(0L, Duration.between(origin, clock.instant()).toNanos());
    }

    private static final class CacheSlot<T> {
        private final String redisPrefix;
        private final Class<T> valueType;
        private final Duration redisTtl;
        private final Cache<String, CacheEntry<T>> l1;
        private final AtomicLong epoch = new AtomicLong();

        private CacheSlot(
                String redisPrefix,
                Class<T> valueType,
                Duration localTtl,
                Duration redisTtl,
                long maxSize,
                Ticker ticker) {
            this.redisPrefix = redisPrefix;
            this.valueType = valueType;
            this.redisTtl = redisTtl;
            long localNanos = localTtl.toNanos();
            long nullNanos = NULL_LOCAL_TTL.toNanos();
            this.l1 = Caffeine.newBuilder()
                    .maximumSize(maxSize)
                    .ticker(ticker)
                    .expireAfter(new Expiry<String, CacheEntry<T>>() {
                        @Override
                        public long expireAfterCreate(
                                String key, CacheEntry<T> value, long currentTime) {
                            return value.isNull() ? nullNanos : localNanos;
                        }

                        @Override
                        public long expireAfterUpdate(
                                String key, CacheEntry<T> value, long currentTime, long currentDuration) {
                            return value.isNull() ? nullNanos : localNanos;
                        }

                        @Override
                        public long expireAfterRead(
                                String key, CacheEntry<T> value, long currentTime, long currentDuration) {
                            return currentDuration;
                        }
                    })
                    .build();
        }

        String redisKey(String rawKey) {
            return redisPrefix + rawKey;
        }

        Class<T> valueType() {
            return valueType;
        }

        Duration redisTtl() {
            return redisTtl;
        }

        long epoch() {
            return epoch.get();
        }

        CacheEntry<T> getL1(String key) {
            return l1.getIfPresent(key);
        }

        void putL1(String key, CacheEntry<T> entry) {
            l1.put(key, entry);
        }

        void invalidate(String key) {
            epoch.incrementAndGet();
            l1.invalidate(key);
        }

        void invalidateMatching(java.util.function.Predicate<String> predicate) {
            epoch.incrementAndGet();
            for (var key : l1.asMap().keySet()) {
                if (predicate.test(key)) {
                    l1.invalidate(key);
                }
            }
        }

        void invalidateAll() {
            epoch.incrementAndGet();
            l1.invalidateAll();
        }
    }

    private record CacheEntry<T>(boolean isNull, T value) {
        static <T> CacheEntry<T> of(T value) {
            return new CacheEntry<>(false, value);
        }

        static <T> CacheEntry<T> nullSentinel() {
            return new CacheEntry<>(true, null);
        }
    }
}
