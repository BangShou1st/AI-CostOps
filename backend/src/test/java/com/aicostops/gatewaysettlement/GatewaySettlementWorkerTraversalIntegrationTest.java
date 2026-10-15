package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.gatewaysettlement.application.GatewaySettlementDiscoveryService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementWorker;
import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper;
import com.aicostops.observability.AiCostOpsMetrics;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gateway Settlement Worker organization traversal on real MySQL 8.4.
 *
 * <p>The worker is built by hand (not the scheduled Spring bean) so the cursor and
 * round count are fully controlled by the test. Only the downstream
 * {@link GatewaySettlementService} is mocked: this isolates "which organizations
 * did the worker visit" — the exact behaviour under test — while every
 * organization, request and settlement row is real MySQL. Financial convergence,
 * idempotency and rollback are covered by
 * {@code GatewaySettlementTransactionIntegrationTest} and
 * {@code PeriodCloseCoordinatorIntegrationTest} and are not re-tested here.
 *
 * <p>Work is seeded as durable PENDING {@code gateway_settlement} rows (the same
 * discovery branch2 the worker scans) so a low-id organization keeps having work
 * across rounds — the precondition for the starvation defect.
 */
@SpringBootTest
@Tag("integration")
class GatewaySettlementWorkerTraversalIntegrationTest extends MySqlContainerSupport {

    private static final int SETTLEABLE_ORGS = 101;

    @Autowired JdbcTemplate jdbc;
    @Autowired GatewaySettlementMapper settlements;
    @Autowired GatewaySettlementDiscoveryService discovery;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    // -- G-01: bounded, fair traversal across rounds ----------------------------

    @Test
    void repeatedRoundsEventuallyReachEveryOrganizationBeyondOnePage() {
        var orgs = seedOrganizationsWithPendingSettlements(SETTLEABLE_ORGS);
        var lowOrgs = orgs.subList(0, SETTLEABLE_ORGS - 1);
        var highestOrg = orgs.get(SETTLEABLE_ORGS - 1);
        var service = Mockito.mock(GatewaySettlementService.class);
        var visited = new LinkedHashSet<Long>();
        recordVisited(service, visited);
        var worker = worker(service, discovery, Mockito.mock(AiCostOpsMetrics.class));

        worker.runOnce();
        // One bounded poll holds exactly one page: the low ids are served first.
        assertThat(visited).containsExactlyInAnyOrderElementsOf(lowOrgs);
        assertThat(visited).doesNotContain(highestOrg);

        for (var round = 0; round < 4; round++) {
            worker.runOnce();
        }

        // The defect: the fixed ORDER BY org_id ASC LIMIT 100 keeps returning the
        // same 100 organizations, so the highest id is never visited at all.
        assertThat(visited).contains(highestOrg);
        assertThat(visited).containsAll(orgs);
    }

    @Test
    void rotationDoesNotDuplicateSettlementRowsWhenOrganizationsRepeat() {
        var orgs = seedOrganizationsWithPendingSettlements(SETTLEABLE_ORGS);
        var service = Mockito.mock(GatewaySettlementService.class);
        var visited = new LinkedHashSet<Long>();
        recordVisited(service, visited);
        var worker = worker(service, discovery, Mockito.mock(AiCostOpsMetrics.class));

        for (var round = 0; round < 4; round++) {
            worker.runOnce();
        }

        assertThat(visited).containsAll(orgs);
        // Paging must not create a second settlement row for any organization.
        for (var orgId : orgs) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gateway_settlement WHERE org_id=?", Integer.class, orgId))
                    .as("organization %s keeps exactly one settlement row", orgId)
                    .isEqualTo(1);
        }
    }

    @Test
    void independentWorkerInstancesEachTraverseEveryOrganization() {
        var orgs = seedOrganizationsWithPendingSettlements(SETTLEABLE_ORGS);
        var visitedA = new LinkedHashSet<Long>();
        var visitedB = new LinkedHashSet<Long>();
        var workerA = worker(recordingService(visitedA), discovery, Mockito.mock(AiCostOpsMetrics.class));
        var workerB = worker(recordingService(visitedB), discovery, Mockito.mock(AiCostOpsMetrics.class));

        // Two workers with independent in-memory cursors interleaved round by round.
        for (var round = 0; round < 4; round++) {
            workerA.runOnce();
            workerB.runOnce();
        }

        assertThat(visitedA).containsAll(orgs);
        assertThat(visitedB).containsAll(orgs);
    }

    // -- G-02: per-organization isolation vs. global dependency failure ---------

    @Test
    void organizationFailureIsIsolatedAndTheRestOfTheRoundStillSettles() {
        var orgs = seedOrganizationsWithPendingSettlements(2);
        var failingOrg = orgs.get(0);
        var healthyOrg = orgs.get(1);
        var spy = Mockito.spy(discovery);
        doThrow(new IllegalStateException("organization discovery exploded"))
                .when(spy).discover(eq(failingOrg), anyInt());
        var service = Mockito.mock(GatewaySettlementService.class);
        var visited = new LinkedHashSet<Long>();
        recordVisited(service, visited);
        var metrics = Mockito.mock(AiCostOpsMetrics.class);
        var worker = worker(service, spy, metrics);

        // The defect: only TransientDataAccessException is caught, so this
        // IllegalStateException used to abort the whole round before healthyOrg.
        assertThatCode(worker::runOnce).doesNotThrowAnyException();

        assertThat(visited).contains(healthyOrg);
        assertThat(visited).doesNotContain(failingOrg);
        verify(metrics).gatewaySettlementRetry("SETTLEMENT_ISOLATED_FAILURE");
    }

    @Test
    void datasourceOutageStopsTheRoundWithoutPreventingRecovery() {
        var orgs = seedOrganizationsWithPendingSettlements(2);
        var firstOrg = orgs.get(0);
        var spy = Mockito.spy(discovery);
        doThrow(new DataAccessResourceFailureException("database unreachable"))
                .when(spy).discover(eq(firstOrg), anyInt());
        var service = Mockito.mock(GatewaySettlementService.class);
        var visited = new LinkedHashSet<Long>();
        recordVisited(service, visited);
        var metrics = Mockito.mock(AiCostOpsMetrics.class);
        var worker = worker(service, spy, metrics);

        // A reachability failure is not confined to one organization: it stops the
        // round instead of plowing through every remaining organization.
        assertThatCode(worker::runOnce).doesNotThrowAnyException();
        assertThat(visited).isEmpty();
        verify(metrics, Mockito.never()).gatewaySettlementRetry("SETTLEMENT_ISOLATED_FAILURE");

        // Once the dependency recovers the next round settles normally.
        Mockito.reset(spy);
        worker.runOnce();
        assertThat(visited).containsExactlyInAnyOrderElementsOf(orgs);
    }

    @Test
    void initialScanDatasourceFailureStopsTheRoundSafelyAndRecovers() {
        var orgs = seedOrganizationsWithPendingSettlements(2);
        var scanWrapper = Mockito.mock(GatewaySettlementMapper.class);
        when(scanWrapper.selectOrganizationsWithWork(any(), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("database unreachable"))
                .thenAnswer(invocation -> settlements.selectOrganizationsWithWork(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2)));
        var service = Mockito.mock(GatewaySettlementService.class);
        var visited = new LinkedHashSet<Long>();
        recordVisited(service, visited);
        var metrics = Mockito.mock(AiCostOpsMetrics.class);
        var worker = worker(scanWrapper, service, discovery, metrics);

        // The initial organization scan runs before the per-organization try/catch.
        assertThat(worker.runOnce()).isZero();
        assertThat(visited).isEmpty();
        verify(metrics).dependencyError("DATABASE");
        // A datasource outage must never be mistaken for "no pending work": every
        // organization's durable work survives untouched.
        for (var orgId : orgs) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gateway_settlement WHERE org_id=?", Integer.class, orgId))
                    .isEqualTo(1);
        }

        // The cursor did not advance, so the very same organizations are traversed
        // once the datasource recovers.
        worker.runOnce();
        assertThat(visited).containsExactlyInAnyOrderElementsOf(orgs);
    }

    // -- helpers ---------------------------------------------------------------

    private GatewaySettlementWorker worker(GatewaySettlementMapper mapper,
            GatewaySettlementService service,
            GatewaySettlementDiscoveryService discoveryService, AiCostOpsMetrics metrics) {
        return new GatewaySettlementWorker(mapper, discoveryService, service, metrics,
                transactionManager, clock);
    }

    private GatewaySettlementWorker worker(GatewaySettlementService service,
            GatewaySettlementDiscoveryService discoveryService, AiCostOpsMetrics metrics) {
        return new GatewaySettlementWorker(settlements, discoveryService, service, metrics,
                transactionManager, clock);
    }

    private static GatewaySettlementService recordingService(Set<Long> visited) {
        var service = Mockito.mock(GatewaySettlementService.class);
        recordVisited(service, visited);
        return service;
    }

    private static void recordVisited(GatewaySettlementService service, Set<Long> visited) {
        doAnswer(invocation -> {
            visited.add(invocation.getArgument(0));
            return null;
        }).when(service).settle(anyLong(), anyLong());
    }

    /**
     * Seeds {@code count} active organizations each with one durable PENDING
     * settlement row. FK checks are disabled on the single bound transaction
     * connection so the fixture does not have to fabricate the whole Gateway
     * object graph; the rows still reference their real organization.
     */
    private List<Long> seedOrganizationsWithPendingSettlements(int count) {
        var orgIds = new ArrayList<Long>(count);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
            try {
                for (var i = 0; i < count; i++) {
                    var slug = "settlement-worker-traversal-" + UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO organization(name,slug,status,created_at,updated_at)
                            VALUES('Settlement worker traversal test',?,'ACTIVE',
                              UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                            """, slug);
                    var orgId = jdbc.queryForObject(
                            "SELECT id FROM organization WHERE slug=?", Long.class, slug);
                    var marker = UUID.randomUUID().toString().replace("-", "");
                    jdbc.update("""
                            INSERT INTO gateway_settlement(org_id,settlement_key,request_id,
                              route_attempt_id,usage_fact_id,billing_period_id,financial_scope_type,
                              financial_scope_id,provider_account_id,provider_model_id,
                              pricing_version_id,currency,status,attempt_count,created_at,updated_at)
                            VALUES (?,?,?,?,?,?,'PROJECT',?,?,?,?,'USD','PENDING',0,
                              UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                            """, orgId, "WORKER-TRAVERSAL:" + marker, i + 1L, i + 1L, i + 1L, i + 1L,
                            i + 1L, i + 1L, i + 1L, i + 1L);
                    orgIds.add(orgId);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        });
        orgIds.sort(Comparator.naturalOrder());
        return orgIds;
    }
}
