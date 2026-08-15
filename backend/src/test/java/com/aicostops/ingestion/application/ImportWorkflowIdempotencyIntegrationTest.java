package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.ingestion.application.ImportCommandIdempotency.IdempotencyDecision;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Tag("integration")
class ImportWorkflowIdempotencyIntegrationTest extends AuthenticationContainersSupport {

    private static final String OPERATION = "IMPORT_RETRY";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ImportCommandIdempotency idempotency;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private long organizationId;
    private long actorMemberId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        organizationId = insertOrganization("Idempotency", "idempotency");
        actorMemberId = insertMember(organizationId);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void newKeyReservesProvisionalRowAndFinalizeCommitsAtomically() {
        var transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            var decision = idempotency.reserve(organizationId, actorMemberId, OPERATION, "idem-1",
                    ImportCommandIdempotency.requestHash(OPERATION, organizationId, actorMemberId, 42L));
            assertThat(decision.replay()).isFalse();
            idempotency.finalize(decision.id(), 200, "{\"state\":\"DONE\"}");
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT response_status FROM api_idempotency", Integer.class))
                .isEqualTo(200);
    }

    @Test
    void sameKeySameHashAfterCompletedResultReplaysStoredResponse() {
        var hash = ImportCommandIdempotency.requestHash(OPERATION, organizationId, actorMemberId, 42L);
        var transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            var decision = idempotency.reserve(organizationId, actorMemberId, OPERATION, "idem-1", hash);
            assertThat(decision.replay()).isFalse();
            idempotency.finalize(decision.id(), 200, "{\"state\":\"DONE\"}");
        });

        var replay = transactions.execute(status ->
                idempotency.reserve(organizationId, actorMemberId, OPERATION, "idem-1", hash));
        assertThat(replay).isNotNull();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.responseStatus()).isEqualTo(200);
        assertThatJson(replay.responseBody());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentRequestHashConflicts() {
        var transactions = new TransactionTemplate(transactionManager);
        var firstHash = ImportCommandIdempotency.requestHash(OPERATION, organizationId, actorMemberId, 42L);
        transactions.executeWithoutResult(status -> {
            var decision = idempotency.reserve(organizationId, actorMemberId, OPERATION, "idem-1", firstHash);
            idempotency.finalize(decision.id(), 200, "{\"state\":\"DONE\"}");
        });

        var secondHash = ImportCommandIdempotency.requestHash(OPERATION, organizationId, actorMemberId, 43L);
        assertThatThrownBy(() -> transactions.execute(status ->
                idempotency.reserve(organizationId, actorMemberId, OPERATION, "idem-1", secondHash)))
                .isInstanceOf(DomainException.class).satisfies(exception -> {
                    assertThat(((DomainException) exception).status().value()).isEqualTo(409);
                    assertThat(((DomainException) exception).code().name()).isEqualTo("STATE_CONFLICT");
                });
    }

    @Test
    void concurrentSameKeyConvergesOnOneCommittedResult() throws Exception {
        var hash = ImportCommandIdempotency.requestHash(OPERATION, organizationId, actorMemberId, 42L);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var winnerReserved = new CountDownLatch(1);
            var winnerFinalized = new CountDownLatch(1);

            var winner = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var decision = idempotency.reserve(organizationId, actorMemberId, OPERATION, "idem-race", hash);
                winnerReserved.countDown();
                try {
                    assertThat(winnerFinalized.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting finalize signal", exception);
                }
                idempotency.finalize(decision.id(), 200, "{\"state\":\"DONE\"}");
                return decision.id();
            }));

            var loser = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                try {
                    assertThat(winnerReserved.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting winner reservation", exception);
                }
                var decision = idempotency.reserve(organizationId, actorMemberId, OPERATION, "idem-race", hash);
                assertThat(decision.replay()).isTrue();
                assertThat(decision.responseStatus()).isEqualTo(200);
                assertThatJson(decision.responseBody());
                return decision.id();
            }));

            // Winner finalizes and commits only after the loser started its own
            // reserve; the loser's INSERT blocks on the unique key until commit.
            winnerReserved.await(10, TimeUnit.SECONDS);
            winnerFinalized.countDown();

            var winnerId = winner.get(30, TimeUnit.SECONDS);
            var loserId = loser.get(30, TimeUnit.SECONDS);
            assertThat(winnerId).isEqualTo(loserId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rolledBackReservationIsNotReplayable() {
        var transactions = new TransactionTemplate(transactionManager);
        var hash = ImportCommandIdempotency.requestHash(OPERATION, organizationId, actorMemberId, 42L);

        try {
            transactions.execute(status -> {
                idempotency.reserve(organizationId, actorMemberId, OPERATION, "idem-rollback", hash);
                throw new IllegalStateException("roll back");
            });
        } catch (IllegalStateException expected) {
            // transaction rolled back
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();

        var retry = transactions.execute(status ->
                idempotency.reserve(organizationId, actorMemberId, OPERATION, "idem-rollback", hash));
        assertThat(retry).isNotNull();
        assertThat(retry.replay()).isFalse();
    }

    @Test
    void fingerprintDistinguishesRawKeyCaseAndIsDeterministic() {
        var upper = ImportCommandIdempotency.keyFingerprint("ABC");
        var lower = ImportCommandIdempotency.keyFingerprint("abc");
        assertThat(upper).isNotEqualTo(lower);
        assertThat(upper).isEqualTo(ImportCommandIdempotency.keyFingerprint("ABC"));
        assertThat(upper).hasSize(64);
        assertThat(upper).matches("[0-9a-f]{64}");
    }

    @Test
    void overLengthKeyFailsValidationBeforeAnyDatabaseMutation() {
        var overLong = "k".repeat(201);
        assertThatThrownBy(() -> ImportCommandIdempotency.validateKey(overLong))
                .isInstanceOf(DomainException.class).satisfies(exception -> {
                    assertThat(((DomainException) exception).status().value()).isEqualTo(400);
                    assertThat(((DomainException) exception).code().name()).isEqualTo("VALIDATION_FAILED");
                });
        assertThatThrownBy(() -> ImportCommandIdempotency.validateKey("  "))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ImportCommandIdempotency.validateKey(null))
                .isInstanceOf(DomainException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
    }

    private void assertThatJson(String body) {
        try {
            var parsed = new ObjectMapper().readTree(body);
            assertThat(parsed.get("state").asText()).isEqualTo("DONE");
        } catch (Exception exception) {
            throw new AssertionError("Stored response body is not valid JSON: " + body, exception);
        }
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertMember(long orgId) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,'Idempotency','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "idempotency-" + orgId + "@example.com");
        var userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized=?", String.class,
                "idempotency-" + orgId + "@example.com");
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }
}
