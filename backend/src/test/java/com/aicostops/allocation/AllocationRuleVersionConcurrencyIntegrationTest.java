package com.aicostops.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.allocation.application.AllocationCommands.RuleDefinitionCommand;
import com.aicostops.allocation.application.AllocationRuleCommandService;
import com.aicostops.attribution.domain.AllocationRuleMatchType;
import com.aicostops.shared.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Concurrent rule version creation of the same new rule key: the
 * organization-row lock serializes {@code maxVersion + 1} so the versions are
 * exactly 1 and 2 — never a duplicate number.
 */
@SpringBootTest
@Tag("integration")
class AllocationRuleVersionConcurrencyIntegrationTest extends AllocationApiTestSupport {

    private static final Instant FROM_2020 = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    private AllocationRuleCommandService commands;

    @Test
    void concurrentVersionsOfTheSameNewKeyAreExactlyOneAndTwo() throws Exception {
        var ruleKey = "race-key-" + System.nanoTime();
        // Adjacent half-open ranges: overlapping ACTIVE ranges of one key are
        // rejected, so both concurrent definitions must stay inside the
        // overlap invariant while still racing on maxVersion + 1.
        var firstRange = new RuleDefinitionCommand(
                "Race rule", "GLM", null, AllocationRuleMatchType.PROVIDER_PROJECT,
                "race-project", 1, projectId, null, null, FROM_2020,
                Instant.parse("2021-01-01T00:00:00Z"));
        var secondRange = new RuleDefinitionCommand(
                "Race rule", "GLM", null, AllocationRuleMatchType.PROVIDER_PROJECT,
                "race-project", 1, projectId, null, null,
                Instant.parse("2021-01-01T00:00:00Z"), null);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = List.of(
                    pool.submit(task(start, () ->
                            commands.createVersion(user(), ruleKey, firstRange, "race-version-a"))),
                    pool.submit(task(start, () ->
                            commands.createVersion(user(), ruleKey, secondRange, "race-version-b"))));
            start.countDown();
            for (Future<com.aicostops.attribution.domain.AllocationRule> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isNotNull();
            }
        }

        var versions = jdbc.queryForList("""
                SELECT version FROM allocation_rule
                WHERE org_id=? AND rule_key=?
                ORDER BY version ASC
                """, orgId, ruleKey);
        assertThat(versions).hasSize(2);
        assertThat(((Number) versions.get(0).get("version")).intValue()).isEqualTo(1);
        assertThat(((Number) versions.get(1).get("version")).intValue()).isEqualTo(2);
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(actorUserId, 7);
    }

    private static <T> java.util.concurrent.Callable<T> task(CountDownLatch start,
            java.util.concurrent.Callable<T> body) {
        return () -> {
            start.await();
            return body.call();
        };
    }
}
