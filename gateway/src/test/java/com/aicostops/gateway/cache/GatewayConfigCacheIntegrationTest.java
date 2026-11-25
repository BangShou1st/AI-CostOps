package com.aicostops.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.provider.ProviderCredentialDecryptor;
import com.aicostops.gateway.provider.ProviderExecutionContextResolver;
import com.aicostops.gateway.provider.ProviderContextResolutionException;
import com.aicostops.gateway.request.GatewayRequestService.DispatchResult;
import com.aicostops.gateway.routing.RoutingPolicyMapper;
import com.aicostops.gateway.routing.RoutingPolicyResolver;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end verification of HC-04 WP3 Gateway two-level cache with real
 * MySQL 8.4 + Redis Testcontainers: L1/L2 hits, ±10% TTL jitter, null-sentinel
 * anti-penetration, 100-thread single-flight, intra-request deduplication,
 * local-only provider credential caching, strict financial exclusions, Pub/Sub
 * invalidation + reconnect wipe, and Redis outage fail-open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class GatewayConfigCacheIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static HttpServer mockUpstream;
    private static String mockUrl;

    @Autowired
    private WebTestClient web;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GatewayConfigCache configCache;

    @Autowired
    private ProviderCredentialDecryptor decryptor;

    @Autowired
    private ProviderExecutionContextResolver executionContextResolver;

    @Autowired
    private RoutingPolicyResolver policyResolver;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private GatewayReadMapper readMapper;

    @MockitoSpyBean
    private RoutingPolicyMapper routingMapper;

    @MockitoSpyBean
    private BudgetReservationMapper reservationMapper;

    @MockitoSpyBean
    private GatewayRequestMapper requestMapper;

    @BeforeAll
    static void startMockUpstream() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/", exchange -> {
            try (var in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            var body = ("{\"id\":\"chatcmpl_cache_test\",\"object\":\"chat.completion\","
                    + "\"created\":1788000300,\"model\":\"test-chat-model\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        mockUpstream.setExecutor(Executors.newCachedThreadPool());
        mockUpstream.start();
        mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopMockUpstream() {
        if (mockUpstream != null) {
            mockUpstream.stop(0);
        }
    }

    @BeforeEach
    void resetBefore() {
        Mockito.reset(redis, readMapper, routingMapper, reservationMapper, requestMapper);
    }

    @AfterEach
    void cleanAfter() {
        Mockito.reset(redis, readMapper, routingMapper, reservationMapper, requestMapper);
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void l1AndL2HitsJitterIntraRequestDedupLocalOnlyProviderCredAndStrictExclusions() {
        var rawKey = "aic_cac000000001_" + "A".repeat(43);
        var env = GatewayTestFixture.seed(
                jdbc, "cache-l1-l2-" + System.nanoTime(), HMAC_KEY, rawKey,
                GatewayTestFixture.TEST_KEK, "sk-live-secret-not-in-redis", mockUrl);
        long connProfileId = jdbc.queryForObject(
                "SELECT id FROM provider_connection_profile WHERE org_id=? LIMIT 1",
                Long.class, env.orgId());
        String digestHex = hmacHex(env.secretPart());
        clearInvocations(readMapper, routingMapper, reservationMapper, requestMapper);

        // Request 1 (cold cache): warms L1 + L2 and performs intra-request dedup.
        postChat(rawKey, "cache-req-1").expectStatus().isOk();

        // Model catalog queried ONCE across controller + authorizePrelude (legacy queries 0 times).
        verify(readMapper, times(1)).findVisibleModelsForOrg(env.orgId());
        verify(readMapper, times(0)).findVisibleModelId(env.orgId(), env.modelKey());
        verify(readMapper, times(0)).findModelById(env.modelId());
        // Pricing version queried ONCE across TX1 precompute and finalization metering.
        verify(reservationMapper, times(1)).findPricingVersionMetadata(env.pricingVersionId());
        verify(reservationMapper, times(1)).findPricingRatesByVersion(env.orgId(), env.pricingVersionId());
        verify(readMapper, times(1)).findCredentialByPrefix(env.prefix());
        verify(readMapper, times(1)).findConnectionProfileByIdUnscoped(connProfileId);
        verify(readMapper, times(1)).findActiveProviderCredential(env.orgId(), env.providerAccountId());
        verify(routingMapper, times(1)).findActivePoliciesForModel(env.orgId(), env.modelId());
        // Financial truth tables ALWAYS query MySQL directly (TX1 + TX2 lockBillingPeriodShared = 2 calls/req;
        // BudgetReservationService resolveBudget reads selectBudgetByIdentity pre-semaphore + inside TX1 = 2 calls/req).
        verify(readMapper, times(1)).findOpenBillingPeriodId(Mockito.eq(env.orgId()), Mockito.any());
        verify(requestMapper, times(2)).lockBillingPeriodShared(env.periodId(), env.orgId());
        verify(reservationMapper, times(2)).selectBudgetByIdentity(
                env.orgId(), env.periodId(), "PROJECT", env.projectId(), "USD");

        // Verify all 6 Redis keys exist with expected TTL jitter (±10%, allowing <= 10s elapsed since SET) or permanent (-1).
        assertThat(redis.getExpire(GatewayConfigCacheKeys.credKey(digestHex), TimeUnit.SECONDS))
                .isBetween(260L, 330L);
        assertThat(redis.getExpire(GatewayConfigCacheKeys.principalKey(env.credentialId()), TimeUnit.SECONDS))
                .isBetween(44L, 66L);
        assertThat(redis.getExpire(GatewayConfigCacheKeys.modelKey(env.orgId()), TimeUnit.SECONDS))
                .isBetween(530L, 660L);
        assertThat(redis.getExpire(GatewayConfigCacheKeys.routeKey(env.orgId(), env.modelId()), TimeUnit.SECONDS))
                .isBetween(260L, 330L);
        assertThat(redis.getExpire(GatewayConfigCacheKeys.connKey(connProfileId), TimeUnit.SECONDS))
                .isBetween(530L, 660L);
        assertThat(redis.getExpire(GatewayConfigCacheKeys.pricingKey(env.pricingVersionId()), TimeUnit.SECONDS))
                .isEqualTo(-1L);

        // Provider credentials, raw API keys, and financial truth tables MUST NEVER exist in Redis.
        Set<String> allRedisKeys = redis.keys("*");
        assertThat(allRedisKeys).isNotNull();
        for (var k : allRedisKeys) {
            assertThat(k).doesNotContain(rawKey);
            assertThat(k).doesNotContain("provider_cred");
            assertThat(k).doesNotStartWith("budget");
            assertThat(k).doesNotStartWith("billing_period");
            if (k.startsWith("cfg:")) {
                String value = redis.opsForValue().get(k);
                assertThat(value).doesNotContain("sk-live-secret-not-in-redis");
                assertThat(value).doesNotContain(rawKey);
            }
        }

        // Request 2 (warm L1): ZERO MySQL config reads; financial tables STILL hit MySQL.
        postChat(rawKey, "cache-req-2").expectStatus().isOk();
        verify(readMapper, times(1)).findCredentialByPrefix(env.prefix());
        verify(readMapper, times(1)).findVisibleModelsForOrg(env.orgId());
        verify(readMapper, times(1)).findConnectionProfileByIdUnscoped(connProfileId);
        verify(readMapper, times(1)).findActiveProviderCredential(env.orgId(), env.providerAccountId());
        verify(routingMapper, times(1)).findActivePoliciesForModel(env.orgId(), env.modelId());
        verify(reservationMapper, times(1)).findPricingVersionMetadata(env.pricingVersionId());
        verify(readMapper, times(2)).findOpenBillingPeriodId(Mockito.eq(env.orgId()), Mockito.any());
        verify(requestMapper, times(4)).lockBillingPeriodShared(env.periodId(), env.orgId());
        verify(reservationMapper, times(4)).selectBudgetByIdentity(
                env.orgId(), env.periodId(), "PROJECT", env.projectId(), "USD");

        // Wipe ONLY L1 (leaving Redis L2 intact), then Request 3:
        // All 6 Redis-backed objects hit Redis L2 (0 extra DB queries) while
        // local-only provider_credential reloads from DB because it is NEVER in Redis!
        configCache.invalidateAll();
        postChat(rawKey, "cache-req-3").expectStatus().isOk();

        verify(readMapper, times(1)).findCredentialByPrefix(env.prefix());
        verify(readMapper, times(1)).findVisibleModelsForOrg(env.orgId());
        verify(readMapper, times(1)).findConnectionProfileByIdUnscoped(connProfileId);
        verify(routingMapper, times(1)).findActivePoliciesForModel(env.orgId(), env.modelId());
        verify(reservationMapper, times(1)).findPricingVersionMetadata(env.pricingVersionId());
        verify(readMapper, times(2)).findActiveProviderCredential(env.orgId(), env.providerAccountId());
        verify(readMapper, times(3)).findOpenBillingPeriodId(Mockito.eq(env.orgId()), Mockito.any());
        verify(requestMapper, times(6)).lockBillingPeriodShared(env.periodId(), env.orgId());
    }

    @Test
    void singleFlightHundredConcurrentMissesTriggerOnlyOneMySqlLoadPerKey() throws Exception {
        var rawKey = "aic_cac000000002_" + "B".repeat(43);
        var env = GatewayTestFixture.seed(
                jdbc, "cache-sf-" + System.nanoTime(), HMAC_KEY, rawKey,
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
        byte[] expectedDigest = hmacBytes(env.secretPart());
        String digestHex = HexFormat.of().formatHex(expectedDigest);
        clearInvocations(readMapper, routingMapper);

        int threads = 100;
        var pool = Executors.newFixedThreadPool(threads);
        var gate = new CountDownLatch(1);
        var futures = new Future<?>[threads];
        try {
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    gate.await();
                    var cred = configCache.getCredentialByDigest(digestHex, env.prefix(), expectedDigest);
                    var route = configCache.getRouteConfig(env.orgId(), env.modelId());
                    assertThat(cred).isNotNull();
                    assertThat(route).isNotNull();
                    return null;
                });
            }
            gate.countDown();
            for (var f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(readMapper, times(1)).findCredentialByPrefix(env.prefix());
        verify(routingMapper, times(1)).findActivePoliciesForModel(env.orgId(), env.modelId());
        verify(routingMapper, times(1)).findActiveCandidatesForModel(env.orgId(), env.modelId());
        verify(routingMapper, times(1)).findActivePricingWindowsForModel(env.orgId(), env.modelId());
    }

    @Test
    void wrongSecretNullSentinelExpiryAndInternalSystemEnforcedOnCacheHits() {
        var validKey = "aic_cac000000003_" + "C".repeat(43);
        var wrongKey = "aic_cac000000003_" + "Z".repeat(43);
        var env = GatewayTestFixture.seed(
                jdbc, "cache-sec-" + System.nanoTime(), HMAC_KEY, validKey,
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
        clearInvocations(readMapper);

        // Wrong secret hits DB once, caches null sentinel under cfg:cred:{wrongDigest}, never poisons valid cred.
        postChat(wrongKey, "wrong-1").expectStatus().isUnauthorized();
        postChat(wrongKey, "wrong-2").expectStatus().isUnauthorized();
        verify(readMapper, times(1)).findCredentialByPrefix(env.prefix());

        String wrongDigestHex = hmacHex("Z".repeat(43));
        String wrongRedisKey = GatewayConfigCacheKeys.credKey(wrongDigestHex);
        assertThat(redis.opsForValue().get(wrongRedisKey)).isEqualTo("\"__NULL__\"");
        assertThat(redis.getExpire(wrongRedisKey, TimeUnit.SECONDS)).isBetween(50L, 66L);

        // Valid credential still authenticates on the same prefix!
        postChat(validKey, "valid-1").expectStatus().isOk();
        verify(readMapper, times(2)).findCredentialByPrefix(env.prefix());

        // Set expires_at 1.5s from DB UTC time, clear cache, authenticate while valid (warms L1+L2),
        // then wait past expiresAt WITHOUT invalidating cache -> cached expiresAt must still trigger 401!
        configCache.resetForTests();
        jdbc.update("UPDATE gateway_credential SET expires_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 SECOND) WHERE id=?",
                env.credentialId());
        Timestamp expiresAtTs = jdbc.queryForObject(
                "SELECT expires_at FROM gateway_credential WHERE id=?", Timestamp.class, env.credentialId());
        Instant expiresAt = expiresAtTs.toInstant();

        postChat(validKey, "valid-expiring").expectStatus().isOk();
        while (!Instant.now().isAfter(expiresAt.plusMillis(150))) {
            LockSupport.parkNanos(50_000_000L);
        }
        postChat(validKey, "expired-cached").expectStatus().isUnauthorized();

        // INTERNAL_SYSTEM cached credential is still rejected on every public HTTP request.
        configCache.resetForTests();
        jdbc.update("UPDATE gateway_credential SET expires_at=NULL, credential_origin='INTERNAL_SYSTEM' WHERE id=?",
                env.credentialId());
        String validDigestHex = hmacHex(env.secretPart());
        assertThat(configCache.getCredentialByDigest(validDigestHex, env.prefix(), hmacBytes(env.secretPart())))
                .isNotNull();
        postChat(validKey, "internal-sys").expectStatus().isUnauthorized();
    }

    @Test
    void modelOrgOverrideRouteProjectPolicyPricingIsolationAndConnAuthorityPreserved() {
        var rawKey = "aic_cac000000004_" + "D".repeat(43);
        var env = GatewayTestFixture.seed(
                jdbc, "cache-domain-" + System.nanoTime(), HMAC_KEY, rawKey,
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
        String uniqueModelKey = "org-override-" + env.orgId();

        // Global model vs org-private override with same model_key.
        jdbc.update("""
                INSERT INTO model_catalog(owner_org_id,model_key,name,status,capabilities_json,
                  default_max_output_tokens,max_output_tokens,created_at,updated_at)
                VALUES (NULL,?,'Global', 'ACTIVE', JSON_OBJECT(), 512, 2048, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, uniqueModelKey);
        long globalId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(owner_org_id,model_key,name,status,capabilities_json,
                  default_max_output_tokens,max_output_tokens,created_at,updated_at)
                VALUES (?, ?, 'Org Private', 'ACTIVE', JSON_OBJECT(), 1024, 4096, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, env.orgId(), uniqueModelKey);
        long orgPrivateId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        try {
            var resolved = configCache.getVisibleModelByKey(env.orgId(), uniqueModelKey);
            assertThat(resolved).isNotNull();
            assertThat(resolved.id()).isEqualTo(orgPrivateId);
            assertThat(resolved.maxOutputTokens()).isEqualTo(4096);

            // Clean up L1 before verifying DRAFT pricing not permanently cached + tenant isolation.
            assertThat(configCache.getPricingRates(env.orgId() + 10_000, env.pricingVersionId())).isEmpty();
            assertThat(configCache.getPricingRates(env.orgId(), env.pricingVersionId())).hasSize(2);

            jdbc.update("""
                    INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,currency,
                      effective_from,effective_to,status,created_at,activated_at,retired_at)
                    VALUES (?,?,?,99,'USD',DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY),NULL,'DRAFT',
                      UTC_TIMESTAMP(6),NULL,NULL)
                    """, env.orgId(), env.providerAccountId(), env.providerModelId());
            long draftPricingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("""
                    INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                    VALUES (?,?,'INPUT_TOKEN',1000,'0.00100000')
                    """, env.orgId(), draftPricingId);

            assertThat(configCache.getPricingRates(env.orgId(), draftPricingId)).hasSize(1);
            assertThat(redis.hasKey(GatewayConfigCacheKeys.pricingKey(draftPricingId))).isFalse();

            jdbc.update("UPDATE pricing_version SET status='ACTIVE', activated_at=UTC_TIMESTAMP(6) WHERE id=?",
                    draftPricingId);
            assertThat(configCache.getPricingRates(env.orgId(), draftPricingId)).hasSize(1);
            assertThat(redis.getExpire(GatewayConfigCacheKeys.pricingKey(draftPricingId), TimeUnit.SECONDS))
                    .isEqualTo(-1L);

            // Project-specific routing policy overrides org-default inside cfg:route:{orgId}:{modelId}.
            jdbc.update("""
                    INSERT INTO routing_policy(org_id,project_id,model_id,version,status,created_at,activated_at)
                    VALUES (?,?,?,2,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, env.orgId(), env.projectId(), env.modelId());
            long projectPolicyId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("""
                    INSERT INTO routing_policy_candidate(org_id,routing_policy_id,provider_account_id,
                      provider_model_id,priority,status,privacy_region_code,created_at)
                    VALUES (?,?,?,?,0,'ACTIVE',NULL,UTC_TIMESTAMP(6))
                    """, env.orgId(), projectPolicyId, env.providerAccountId(), env.providerModelId());

            configCache.resetForTests();
            var now = Instant.now();
            var forProject = policyResolver.resolve(env.orgId(), env.projectId(), env.modelId(), now);
            var forOtherProject = policyResolver.resolve(env.orgId(), env.projectId() + 12345, env.modelId(), now);
            assertThat(forProject.id()).isEqualTo(projectPolicyId);
            assertThat(forOtherProject.id()).isNotEqualTo(projectPolicyId);

            // Connection profile tenant check preserved when resolved through cfg:conn:{id}.
            long connProfileId = jdbc.queryForObject(
                    "SELECT id FROM provider_connection_profile WHERE org_id=? LIMIT 1",
                    Long.class, env.orgId());
            var foreignPrincipal = new GatewayPrincipal(
                    999L, env.orgId() + 10_000, 999L, "SERVICE",
                    null, 999L, "PROJECT", 999L, "OPTIONAL");
            var dispatch = new DispatchResult(
                    1L, "req_1", 1L, "dec_1", projectPolicyId, env.providerAccountId(),
                    env.providerModelId(), env.pricingVersionId(), "USD", mockUrl,
                    GatewayTestFixture.PROVIDER_CODE, GatewayTestFixture.PROVIDER_MODEL_NAME,
                    env.modelId(), 100, 100, env.periodId(), connProfileId,
                    "/chat/completions", "OPENAI_CHAT_COMPLETIONS", "DIRECT_PUBLIC_ONLY");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> executionContextResolver.resolve(foreignPrincipal, dispatch))
                    .isInstanceOf(ProviderContextResolutionException.class)
                    .satisfies(ex -> assertThat(ex.getCause())
                            .hasMessageContaining("organization mismatch"));
        } finally {
            jdbc.update("DELETE FROM model_catalog WHERE id IN (?,?)", orgPrivateId, globalId);
        }
    }

    @Test
    void pubSubInvalidationEvictsLocalCacheWithinMillisecondsIncludingProviderCredentials() {
        var rawKey = "aic_cac000000005_" + "E".repeat(43);
        var env = GatewayTestFixture.seed(
                jdbc, "cache-pubsub-" + System.nanoTime(), HMAC_KEY, rawKey,
                GatewayTestFixture.TEST_KEK, "sk-secret-v1", mockUrl);

        // Warm cred + local-only provider credential cache.
        postChat(rawKey, "ps-1").expectStatus().isOk();
        assertThat(new String(decryptor.decrypt(env.orgId(), env.providerAccountId()).secret(), StandardCharsets.UTF_8))
                .isEqualTo("sk-secret-v1");

        // Mutate DB: revoke credential and wipe/update DB provider secret.
        jdbc.update("UPDATE gateway_credential SET status='REVOKED', revoked_at=UTC_TIMESTAMP(6) WHERE id=?",
                env.credentialId());
        jdbc.update("UPDATE provider_credential SET status='REVOKED' WHERE org_id=? AND provider_account_id=?",
                env.orgId(), env.providerAccountId());

        // Still served from L1 before invalidation signal arrives.
        postChat(rawKey, "ps-stale-l1").expectStatus().isOk();
        assertThat(new String(decryptor.decrypt(env.orgId(), env.providerAccountId()).secret(), StandardCharsets.UTF_8))
                .isEqualTo("sk-secret-v1");

        // Publish Backend-style DEL + PUBLISH on aicostops:v2:cfg-invalidate.
        String digestHex = hmacHex(env.secretPart());
        redis.delete(GatewayConfigCacheKeys.credKey(digestHex));
        redis.convertAndSend(GatewayConfigCacheKeys.INVALIDATION_CHANNEL,
                "{\"type\":\"CRED\",\"key\":\"" + digestHex + "\"}");
        redis.convertAndSend(GatewayConfigCacheKeys.INVALIDATION_CHANNEL,
                "{\"type\":\"PROVIDER_CRED\",\"key\":\"" + env.orgId() + ":" + env.providerAccountId() + "\"}");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean evicted = false;
        while (System.nanoTime() < deadline) {
            try {
                decryptor.decrypt(env.orgId(), env.providerAccountId());
                LockSupport.parkNanos(20_000_000L);
            } catch (IllegalStateException expectedNoActive) {
                evicted = true;
                break;
            }
        }
        assertThat(evicted).isTrue();
        postChat(rawKey, "ps-revoked").expectStatus().isUnauthorized();
    }

    @Test
    void missedPubSubDeliveryConvergesAtTheCredentialL1TtlAfterRedisDeletion() {
        var prefix = "cac000000008";
        var rawKey = "aic_" + prefix + "_" + "H".repeat(43);
        var env = GatewayTestFixture.seed(jdbc, "cache-missed-pubsub", HMAC_KEY, rawKey);
        var digestHex = hmacHex(env.secretPart());
        var digest = HexFormat.of().parseHex(digestHex);
        var now = new AtomicReference<>(Instant.now());
        var clock = Mockito.mock(Clock.class);
        Mockito.when(clock.instant()).thenAnswer(call -> now.get());
        var disconnectedCache = new GatewayConfigCache(redis, objectMapper, readMapper,
                routingMapper, reservationMapper, clock);
        try {
            assertThat(disconnectedCache.getCredentialByDigest(digestHex, prefix, digest).status())
                    .isEqualTo("ACTIVE");
            jdbc.update("UPDATE gateway_credential SET status='REVOKED', revoked_at=UTC_TIMESTAMP(6) WHERE id=?",
                    env.credentialId());
            assertThat(redis.delete(GatewayConfigCacheKeys.credKey(digestHex))).isTrue();

            now.updateAndGet(time -> time.plusSeconds(29));
            assertThat(disconnectedCache.getCredentialByDigest(digestHex, prefix, digest).status())
                    .isEqualTo("ACTIVE");
            now.updateAndGet(time -> time.plusSeconds(1));
            assertThat(disconnectedCache.getCredentialByDigest(digestHex, prefix, digest).status())
                    .isEqualTo("REVOKED");
        } finally {
            disconnectedCache.resetForTests();
        }
    }

    @Test
    void redisOutageFailsOpenToMySqlOnLiveChatCompletions() {
        var rawKey = "aic_cac000000006_" + "F".repeat(43);
        GatewayTestFixture.seed(
                jdbc, "cache-outage-" + System.nanoTime(), HMAC_KEY, rawKey,
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
        configCache.resetForTests();

        doThrow(new RedisConnectionFailureException("Simulated Redis L2 outage"))
                .when(redis).opsForValue();

        postChat(rawKey, "redis-outage-1").expectStatus().isOk();
        Mockito.reset(redis);
    }

    @Test
    void realRedisContainerStopAndPubSubDisconnectClearAllLocalCachesAndFailOpen() {
        var rawKey = "aic_cac000000007_" + "G".repeat(43);
        var env = GatewayTestFixture.seed(
                jdbc, "cache-ephemeral-redis-" + System.nanoTime(), HMAC_KEY, rawKey,
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
        long connId = jdbc.queryForObject(
                "SELECT id FROM provider_connection_profile WHERE org_id=? LIMIT 1",
                Long.class, env.orgId());

        try (var ephemeralRedis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379)) {
            ephemeralRedis.start();
            var factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(ephemeralRedis.getHost(), ephemeralRedis.getMappedPort(6379)));
            factory.afterPropertiesSet();
            var template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();

            var localCache = new GatewayConfigCache(
                    template, objectMapper, readMapper, routingMapper, reservationMapper, java.time.Clock.systemUTC());
            var subscriber = new GatewayConfigInvalidationSubscriber(
                    new StaticObjectProvider<>(factory), localCache, objectMapper);
            subscriber.start();
            try {
                // Allow Pub/Sub initial SUBSCRIBE frame to settle.
                long settle = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!subscriber.isRunning() && System.nanoTime() < settle) {
                    LockSupport.parkNanos(20_000_000L);
                }
                LockSupport.parkNanos(200_000_000L);

                clearInvocations(readMapper);
                var credLoads = new java.util.concurrent.atomic.AtomicInteger();
                assertThat(localCache.getConnectionProfile(connId)).isNotNull();
                assertThat(localCache.getOrLoadProviderCredentialLocalOnly(env.orgId(), env.providerAccountId(),
                        () -> {
                            credLoads.incrementAndGet();
                            return new ProviderCredentialDecryptor.DecryptedCredential(
                                    "API_KEY", "s".getBytes(StandardCharsets.UTF_8));
                        })).isNotNull();
                verify(readMapper, times(1)).findConnectionProfileByIdUnscoped(connId);
                assertThat(credLoads.get()).isEqualTo(1);

                // Stop Redis container -> Pub/Sub disconnect must trigger invalidateAll() clearing local caches
                // (including local-only provider credentials), and subsequent lookup must fail open directly to MySQL!
                ephemeralRedis.stop();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean wiped = false;
                while (System.nanoTime() < deadline) {
                    localCache.getConnectionProfile(connId);
                    if (Mockito.mockingDetails(readMapper).getInvocations().stream()
                            .filter(inv -> inv.getMethod().getName().equals("findConnectionProfileByIdUnscoped"))
                            .count() >= 2) {
                        wiped = true;
                        break;
                    }
                    LockSupport.parkNanos(50_000_000L);
                }
                assertThat(wiped).isTrue();
                localCache.getOrLoadProviderCredentialLocalOnly(env.orgId(), env.providerAccountId(),
                        () -> {
                            credLoads.incrementAndGet();
                            return new ProviderCredentialDecryptor.DecryptedCredential(
                                    "API_KEY", "s".getBytes(StandardCharsets.UTF_8));
                        });
                assertThat(credLoads.get()).isEqualTo(2);
            } finally {
                subscriber.stop();
                factory.destroy();
            }
        }
    }

    private WebTestClient.ResponseSpec postChat(String rawKey, String idempotencyKey) {
        return web.mutate()
                .responseTimeout(Duration.ofSeconds(20))
                .build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + rawKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"default-chat","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange();
    }

    private static byte[] hmacBytes(String secretPart) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(HMAC_KEY), "HmacSHA256"));
            return mac.doFinal(secretPart.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hmacHex(String secretPart) {
        return HexFormat.of().formatHex(hmacBytes(secretPart));
    }

    private static final class StaticObjectProvider<T> implements ObjectProvider<T> {
        private final T instance;

        private StaticObjectProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }

        @Override
        public T getObject() {
            return instance;
        }
    }
}
