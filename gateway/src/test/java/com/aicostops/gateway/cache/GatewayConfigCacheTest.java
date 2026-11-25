package com.aicostops.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.provider.ProviderCredentialDecryptor;
import com.aicostops.gateway.routing.RoutingPolicyMapper;
import com.aicostops.gateway.testsupport.MutableClock;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

class GatewayConfigCacheTest {

    private static final String TEST_KEK = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    private MutableClock clock;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private GatewayReadMapper readMapper;
    private RoutingPolicyMapper routingMapper;
    private BudgetReservationMapper reservationMapper;
    private ObjectMapper objectMapper;
    private GatewayConfigCache cache;
    private GatewayConfigInvalidationSubscriber subscriber;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        readMapper = mock(GatewayReadMapper.class);
        routingMapper = mock(RoutingPolicyMapper.class);
        reservationMapper = mock(BudgetReservationMapper.class);
        objectMapper = new ObjectMapper();
        cache = new GatewayConfigCache(redis, objectMapper, readMapper, routingMapper, reservationMapper, clock);
        subscriber = new GatewayConfigInvalidationSubscriber(null, cache, objectMapper);
    }

    @Test
    void eventLoopGuardRejectsNonBlockingThread() {
        assertThatThrownBy(() -> reactor.core.publisher.Mono.fromCallable(() -> cache.getModelCatalog(1L))
                .subscribeOn(Schedulers.parallel())
                .block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-blocking Reactor event-loop thread");

        assertThatThrownBy(() -> reactor.core.publisher.Mono.fromCallable(() -> cache.getRouteConfig(1L, 2L))
                .subscribeOn(Schedulers.parallel())
                .block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-blocking Reactor event-loop thread");
    }

    @Test
    void jitterTtlStaysWithinPlusMinusTenPercent() {
        var base = Duration.ofSeconds(300);
        long minMs = 270_000L;
        long maxMs = 330_000L;
        var seen = new HashSet<Long>();
        for (int i = 0; i < 500; i++) {
            var jittered = GatewayConfigCache.jitterTtl(base);
            assertThat(jittered.toMillis()).isBetween(minMs, maxMs);
            seen.add(jittered.toMillis());
        }
        assertThat(seen.size()).isGreaterThan(5);
        assertThat(GatewayConfigCache.jitterTtl(Duration.ZERO)).isEqualTo(Duration.ZERO);
    }

    @Test
    void providerCredentialsStayLocalOnlyWithDefensiveCopyAndPubSubEviction() throws Exception {
        long orgId = 10L;
        long accountId = 20L;
        byte[] ciphertextAndNonce[] = encryptSecret("sk-super-secret-value", orgId, accountId);
        when(readMapper.findActiveProviderCredential(orgId, accountId))
                .thenReturn(new GatewayReadMapper.ProviderCredentialRow(
                        "API_KEY", ciphertextAndNonce[0], ciphertextAndNonce[1], (short) 1));

        var props = new GatewayProperties();
        props.setProviderKekV1(TEST_KEK);
        var decryptor = new ProviderCredentialDecryptor(readMapper, props, cache);

        var first = decryptor.decrypt(orgId, accountId);
        assertThat(new String(first.secret(), StandardCharsets.UTF_8)).isEqualTo("sk-super-secret-value");
        // Mutating caller's copy must never corrupt the cached array.
        first.secret()[0] = 'X';
        first.secret()[1] = 'X';

        var second = decryptor.decrypt(orgId, accountId);
        assertThat(new String(second.secret(), StandardCharsets.UTF_8)).isEqualTo("sk-super-secret-value");
        verify(readMapper, times(1)).findActiveProviderCredential(orgId, accountId);
        verifyNoInteractions(redis);

        // Pub/Sub PROVIDER_CRED message evicts local cache and triggers fresh DB read.
        subscriber.handleInvalidation("{\"type\":\"PROVIDER_CRED\",\"key\":\"" + orgId + ":" + accountId + "\"}");
        decryptor.decrypt(orgId, accountId);
        verify(readMapper, times(2)).findActiveProviderCredential(orgId, accountId);

        // TTL expiry (5m) also expires local credential entry cleanly.
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        decryptor.decrypt(orgId, accountId);
        verify(readMapper, times(3)).findActiveProviderCredential(orgId, accountId);
        verifyNoInteractions(redis);
    }

    @Test
    void redisFailureFailsOpenAndDoesNotCacheNullOnDbError() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis timeout"));
        org.mockito.Mockito.doThrow(new RuntimeException("Redis write timeout"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        var row = new GatewayReadMapper.ConnectionProfileRow(
                55L, 10L, 20L, "BEARER", "Authorization", "PUBLIC");
        when(readMapper.findConnectionProfileByIdUnscoped(55L)).thenReturn(row);

        assertThat(cache.getConnectionProfile(55L)).isEqualTo(row);
        assertThat(cache.getConnectionProfile(55L)).isEqualTo(row);
        verify(readMapper, times(1)).findConnectionProfileByIdUnscoped(55L);

        // Missing record returns null and caches sentinel for 10s L1.
        when(readMapper.findConnectionProfileByIdUnscoped(999L)).thenReturn(null);
        assertThat(cache.getConnectionProfile(999L)).isNull();
        assertThat(cache.getConnectionProfile(999L)).isNull();
        verify(readMapper, times(1)).findConnectionProfileByIdUnscoped(999L);

        clock.advance(Duration.ofSeconds(11));
        assertThat(cache.getConnectionProfile(999L)).isNull();
        verify(readMapper, times(2)).findConnectionProfileByIdUnscoped(999L);

        // MySQL exception must NEVER be cached as null sentinel.
        when(readMapper.findConnectionProfileByIdUnscoped(888L))
                .thenThrow(new IllegalStateException("MySQL unreachable"))
                .thenReturn(row);
        assertThatThrownBy(() -> cache.getConnectionProfile(888L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.getConnectionProfile(888L)).isEqualTo(row);
        verify(readMapper, times(2)).findConnectionProfileByIdUnscoped(888L);
    }

    @Test
    void pricingDraftNotCachedPermanentlyAndActiveIsTenantIsolated() {
        when(reservationMapper.findPricingVersionMetadata(77L))
                .thenReturn(new BudgetReservationMapper.PricingVersionMetadataRow(77L, 10L, "DRAFT"))
                .thenReturn(new BudgetReservationMapper.PricingVersionMetadataRow(77L, 10L, "ACTIVE"));
        when(reservationMapper.findPricingRatesByVersion(10L, 77L))
                .thenReturn(List.of(new BudgetReservationMapper.PricingRateRow(
                        "INPUT_TOKEN", 1000L, new BigDecimal("0.00200000"))));

        // DRAFT is loaded and returned but NOT cached permanently.
        assertThat(cache.getPricingRates(10L, 77L)).hasSize(1);
        assertThat(cache.getPricingRates(10L, 77L)).hasSize(1);
        verify(reservationMapper, times(2)).findPricingVersionMetadata(77L);

        // Now cached as ACTIVE: foreign org stays empty, owning org reuses cached L1 forever.
        assertThat(cache.getPricingRates(999L, 77L)).isEmpty();
        assertThat(cache.getPricingDimensions(10L, 77L)).containsExactly("INPUT_TOKEN");
        clock.advance(Duration.ofDays(30));
        assertThat(cache.getPricingRates(10L, 77L)).hasSize(1);
        verify(reservationMapper, times(2)).findPricingVersionMetadata(77L);
    }

    @Test
    void singleFlightDeduplicatesHundredConcurrentMisses() throws Exception {
        var dbCalls = new AtomicInteger();
        var enterLoader = new CountDownLatch(1);
        var releaseLoader = new CountDownLatch(1);
        when(routingMapper.findActivePoliciesForModel(10L, 20L)).thenAnswer(inv -> {
            dbCalls.incrementAndGet();
            enterLoader.countDown();
            releaseLoader.await(5, TimeUnit.SECONDS);
            return List.of(new RoutingPolicyMapper.PolicyRow(100L, 1, null, 20L));
        });
        when(routingMapper.findActiveCandidatesForModel(10L, 20L)).thenReturn(List.of());
        when(routingMapper.findActivePricingWindowsForModel(10L, 20L)).thenReturn(List.of());

        int threads = 100;
        var pool = Executors.newFixedThreadPool(threads);
        var startGate = new CountDownLatch(1);
        var futures = new Future[threads];
        try {
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    startGate.await();
                    return cache.getRouteConfig(10L, 20L);
                });
            }
            startGate.countDown();
            assertThat(enterLoader.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoader.countDown();
            for (var future : futures) {
                var result = (GatewayConfigCacheModels.CachedRouteConfig) future.get(10, TimeUnit.SECONDS);
                assertThat(result).isNotNull();
                assertThat(result.policies()).hasSize(1);
            }
        } finally {
            releaseLoader.countDown();
            pool.shutdownNow();
        }
        assertThat(dbCalls.get()).isEqualTo(1);
    }

    @Test
    void routeAndModelCatalogPreserveOrgPrivateOverrideAndInMemoryClockWindows() {
        var t1 = Instant.parse("2026-11-25T10:00:00Z");
        var t2 = Instant.parse("2026-11-25T11:00:00Z");
        var tBoundary = Instant.parse("2026-11-25T10:30:00Z");
        when(readMapper.findVisibleModelsForOrg(10L)).thenReturn(List.of(
                new GatewayReadMapper.OrgVisibleModelRow(2L, "gpt-4o", 10L, "ACTIVE", 512, 4096),
                new GatewayReadMapper.OrgVisibleModelRow(1L, "gpt-4o", null, "ACTIVE", 256, 2048)));
        assertThat(cache.getVisibleModelByKey(10L, "gpt-4o").id()).isEqualTo(2L);

        when(routingMapper.findActivePoliciesForModel(10L, 2L)).thenReturn(List.of(
                new RoutingPolicyMapper.PolicyRow(200L, 2, 50L, 2L),
                new RoutingPolicyMapper.PolicyRow(100L, 1, null, 2L)));
        when(routingMapper.findActiveCandidatesForModel(10L, 2L)).thenReturn(List.of(
                new RoutingPolicyMapper.ModelCandidateRow(
                        300L, 200L, 0, 30L, "OPENAI", 40L, 2L, "OPENAI", "gpt-4o",
                        50L, "https://api.example/v1", "/chat/completions", "OPENAI_CHAT", "PUBLIC",
                        "OPENAI", true, true, true, true, true),
                new RoutingPolicyMapper.ModelCandidateRow(
                        301L, 100L, 0, 30L, "OPENAI", 40L, 2L, "OPENAI", "gpt-4o",
                        50L, "https://api.example/v1", "/chat/completions", "OPENAI_CHAT", "PUBLIC",
                        "OPENAI", true, true, true, true, true)));
        when(routingMapper.findActivePricingWindowsForModel(10L, 2L)).thenReturn(List.of(
                new RoutingPolicyMapper.PricingWindowRow(
                        902L, 30L, 40L, "USD", tBoundary, null),
                new RoutingPolicyMapper.PricingWindowRow(
                        901L, 30L, 40L, "USD", t1.minusSeconds(60), tBoundary)));

        var route = cache.getRouteConfig(10L, 2L);
        var projectPolicyT1 = route.resolvePolicy(50L, t1);
        assertThat(projectPolicyT1.id()).isEqualTo(200L);
        assertThat(projectPolicyT1.candidates().getFirst().pricingVersionId()).isEqualTo(901L);

        // Same cached route evaluated at t2 switches pricing window in memory without extra SQL.
        var projectPolicyT2 = route.resolvePolicy(50L, t2);
        assertThat(projectPolicyT2.candidates().getFirst().pricingVersionId()).isEqualTo(902L);
        assertThat(route.resolvePolicy(999L, t1).id()).isEqualTo(100L);
        verify(routingMapper, times(1)).findActivePoliciesForModel(10L, 2L);
    }

    @Test
    void concurrentInvalidationDuringSlowLoadPreventsStaleCachePoisoning() throws Exception {
        var oldRow = new GatewayReadMapper.ConnectionProfileRow(
                71L, 10L, 20L, "BEARER", "Authorization", "OLD");
        var freshRow = new GatewayReadMapper.ConnectionProfileRow(
                71L, 10L, 20L, "BEARER", "Authorization", "FRESH");
        var enteredDb = new CountDownLatch(1);
        var releaseDb = new CountDownLatch(1);
        var call = new AtomicInteger();

        when(readMapper.findConnectionProfileByIdUnscoped(71L)).thenAnswer(inv -> {
            if (call.incrementAndGet() == 1) {
                enteredDb.countDown();
                releaseDb.await(5, TimeUnit.SECONDS);
                return oldRow;
            }
            return freshRow;
        });

        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> cache.getConnectionProfile(71L));
            assertThat(enteredDb.await(5, TimeUnit.SECONDS)).isTrue();
            cache.invalidateConnectionProfile("71");
            releaseDb.countDown();
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(oldRow);
        } finally {
            releaseDb.countDown();
            pool.shutdownNow();
        }

        // Old value must NOT have poisoned L1; next read loads the committed fresh row.
        assertThat(cache.getConnectionProfile(71L)).isEqualTo(freshRow);
        verify(readMapper, times(2)).findConnectionProfileByIdUnscoped(71L);
    }

    @Test
    void subscriberHandlesAllInvalidationMessageTypesAndMalformedPayloads() {
        when(readMapper.findConnectionProfileByIdUnscoped(anyLong()))
                .thenReturn(new GatewayReadMapper.ConnectionProfileRow(
                        1L, 10L, 20L, "BEARER", "Authorization", "PUBLIC"));
        when(readMapper.findVisibleModelsForOrg(10L)).thenReturn(List.of(
                new GatewayReadMapper.OrgVisibleModelRow(2L, "m", 10L, "ACTIVE", 100, 1000)));
        when(routingMapper.findActivePoliciesForModel(anyLong(), anyLong()))
                .thenReturn(List.of(new RoutingPolicyMapper.PolicyRow(1L, 1, null, 2L)));

        cache.getConnectionProfile(1L);
        cache.getModelCatalog(10L);
        cache.getRouteConfig(10L, 2L);

        subscriber.handleInvalidation("{\"type\":\"CONN\",\"key\":\"1\"}");
        subscriber.handleInvalidation("{\"type\":\"MODEL\",\"key\":\"10\"}");
        subscriber.handleInvalidation("{\"type\":\"ROUTE\",\"key\":\"10:2\"}");
        subscriber.handleInvalidation("{\"type\":\"ROUTE_ORG\",\"key\":\"10\"}");
        subscriber.handleInvalidation("{\"type\":\"ROUTE_ALL\",\"key\":\"*\"}");
        subscriber.handleInvalidation("{\"type\":\"MODEL_ALL\",\"key\":\"*\"}");
        subscriber.handleInvalidation("{\"type\":\"CRED\",\"key\":\"deadbeef\"}");
        subscriber.handleInvalidation("{\"type\":\"PRINCIPAL\",\"key\":\"5\"}");
        subscriber.handleInvalidation("{\"type\":\"PRICING\",\"key\":\"7\"}");
        subscriber.handleInvalidation("{\"type\":\"PROVIDER_CRED\",\"key\":\"10:20\"}");
        subscriber.handleInvalidation("{\"type\":\"ALL\",\"key\":\"*\"}");
        subscriber.handleInvalidation("{\"type\":\"UNKNOWN\",\"key\":\"x\"}");
        subscriber.handleInvalidation("{not valid json");
        subscriber.handleInvalidation("");
        subscriber.handleInvalidation(null);

        cache.getConnectionProfile(1L);
        cache.getModelCatalog(10L);
        cache.getRouteConfig(10L, 2L);
        verify(readMapper, times(2)).findConnectionProfileByIdUnscoped(1L);
        verify(readMapper, times(2)).findVisibleModelsForOrg(10L);
        verify(routingMapper, times(2)).findActivePoliciesForModel(10L, 2L);
    }

    private static byte[][] encryptSecret(String secret, long orgId, long providerAccountId) throws Exception {
        var nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(TEST_KEK), "AES"),
                new GCMParameterSpec(128, nonce));
        var aad = ("aicostops:v2:provider-credential:v1\0" + orgId + "\0" + providerAccountId
                + "\0API_KEY\0" + "1").getBytes(StandardCharsets.UTF_8);
        cipher.updateAAD(aad);
        return new byte[][]{cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)), nonce};
    }
}
