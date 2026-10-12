package com.aicostops.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.aicostops.intelligence.application.CostIntelligenceAnalysisService;
import com.aicostops.intelligence.application.SavingsRecommendationGenerator;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The split analysis pipeline keeps a single commit boundary, proven against rows that were
 * genuinely written.
 *
 * <p>The savings generator is a <em>spy</em>, never a replacement: the failure cases call
 * {@code callRealMethod()} first, so anomalies, forecasts and recommendations are really persisted
 * inside the analysis transaction, and the injected exception is raised only afterwards. Counts are
 * asserted <em>inside</em> that transaction to prove the rows existed before the rollback; asserting
 * zero rows alone would pass even if nothing had ever been written.
 *
 * <p>No test-managed transaction: the rollback under test must be a real one.
 */
@SpringBootTest
@Tag("integration")
class CostIntelligenceAnalysisPipelineIntegrationTest extends AuthenticationContainersSupport {
    @Autowired private com.aicostops.intelligence.application.CostAnalysisInputService inputs;

    private static final LocalDate ANALYSIS_DATE = LocalDate.now().minusDays(1);

    @Autowired
    private CostIntelligenceAnalysisService analysis;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private SavingsRecommendationGenerator savings;

    private final AtomicInteger anomaliesInsideTransaction = new AtomicInteger();
    private final AtomicInteger forecastsInsideTransaction = new AtomicInteger();
    private final AtomicInteger recommendationsInsideTransaction = new AtomicInteger();

    private long org;
    private long projectId;

    @BeforeEach
    void createOrganization() {
        var slug = "pipeline-" + UUID.randomUUID();
        jdbc.update("INSERT INTO organization(name,slug,status,created_at,updated_at) "
                + "VALUES('Pipeline test',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", slug);
        org = jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
        projectId = insertProject();
        // Two production-ready, priced candidates for one logical model, so the real generator
        // emits a savings_recommendation row before any injected failure.
        var logicalModelId = insertLogicalModel();
        var wireA = insertProviderModel(logicalModelId, "pipeline-wire-a");
        var wireB = insertProviderModel(logicalModelId, "pipeline-wire-b");
        var accountExpensive = insertAccount("Pipeline Account A");
        var accountCheaper = insertAccount("Pipeline Account B");
        var pricingA = insertPricing(accountExpensive, wireA, "2.00");
        insertPricing(accountCheaper, wireB, "1.00");
        insertConnection(accountExpensive);
        insertConnection(accountCheaper);
        // Real settled usage on the expensive route: 19 flat days then a material spike, which both
        // feeds the anomaly engine and gives the generator a usage vector to replay.
        insertSettledDailySeries(pricingA, accountExpensive, wireA, "500.50000000");
        resetInsideTransactionCounts();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM savings_recommendation WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_forecast_snapshot WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_anomaly WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_intelligence_run WHERE org_id=?", org);
        jdbc.update("DELETE FROM ledger_entry WHERE org_id=?", org);
        jdbc.update("DELETE FROM gateway_settlement WHERE org_id=?", org);
        jdbc.update("DELETE FROM gateway_usage_dimension WHERE org_id=?", org);
        jdbc.update("DELETE FROM gateway_usage_fact WHERE org_id=?", org);
        jdbc.update("DELETE FROM ledger_posting WHERE org_id=?", org);
        jdbc.update("DELETE FROM pricing_rate WHERE org_id=?", org);
        jdbc.update("DELETE FROM pricing_version WHERE org_id=?", org);
        jdbc.update("DELETE FROM provider_connection_profile WHERE org_id=?", org);
        jdbc.update("DELETE FROM provider_model WHERE provider_model_name LIKE 'pipeline-wire-%'");
        jdbc.update("DELETE FROM model_catalog WHERE model_key LIKE 'pipeline-logical-%'");
        jdbc.update("DELETE FROM provider_account WHERE org_id=?", org);
        jdbc.update("DELETE FROM project WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_history_coverage_revision WHERE org_id=?", org);
        jdbc.update("DELETE FROM organization WHERE id=?", org);
    }

    /**
     * The real generator writes recommendations, then the injected failure rolls back the whole
     * run. Distinct positive pre-rollback counts are the load-bearing evidence.
     */
    @Test
    void generatorFailureRollsBackDerivedResultsThatWereReallyWritten() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            // In-transaction proof that all three derived types exist right now.
            captureInsideTransactionCounts();
            assertThat(forecastsInsideTransaction.get())
                    .as("forecasts must be persisted before the injected failure")
                    .isPositive();
            assertThat(anomaliesInsideTransaction.get())
                    .as("the ledger history must be anomalous enough to persist a cost_anomaly")
                    .isPositive();
            assertThat(recommendationsInsideTransaction.get())
                    .as("the cheaper priced candidate must persist a savings_recommendation")
                    .isPositive();
            throw new IllegalStateException("Injected post-generation failure");
        }).when(savings).generate(anyLong(), anyLong(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> analysis.runAnalysis(org, ANALYSIS_DATE, "USD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Injected post-generation failure");

        // The rows provably existed, and every one of them is gone.
        assertThat(forecastsInsideTransaction.get()).isPositive();
        assertThat(anomaliesInsideTransaction.get()).isPositive();
        assertThat(recommendationsInsideTransaction.get()).isPositive();
        assertThat(derivedCounts()).containsExactly(0, 0, 0);
        assertThat(runStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM cost_intelligence_run WHERE org_id=?",
                String.class, org)).isEqualTo("IllegalStateException");
    }

    /**
     * Even without the generator succeeding, the analysis itself has already written anomalies and
     * forecasts by the time the callback runs; a failure there must roll those back too.
     */
    @Test
    void failureAfterRealAnomalyAndForecastWritesRollsThemBack() {
        doAnswer(invocation -> {
            // Deliberately NOT calling callRealMethod: anomalies and forecasts were already
            // persisted by the analysis before this callback, and that is exactly what we assert.
            captureInsideTransactionCounts();
            assertThat(anomaliesInsideTransaction.get()).isPositive();
            assertThat(forecastsInsideTransaction.get()).isPositive();
            throw new IllegalStateException("Injected analysis failure");
        }).when(savings).generate(anyLong(), anyLong(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> analysis.runAnalysis(org, ANALYSIS_DATE, "USD"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(anomaliesInsideTransaction.get()).isPositive();
        assertThat(forecastsInsideTransaction.get()).isPositive();
        assertThat(derivedCounts()).containsExactly(0, 0, 0);
        assertThat(runStatus()).isEqualTo("FAILED");
    }

    /**
     * Control case: the identical fixture with no injected failure commits all three result types.
     * Without this, the zero-row assertions above could pass simply because nothing is ever written.
     */
    @Test
    void completedRunCommitsEveryDerivedTypeAndReplaysWithoutRewriting() {
        var outcome = analysis.runAnalysis(org, ANALYSIS_DATE, "USD");

        assertThat(outcome.status()).isEqualTo("COMPLETED");
        var committed = derivedCounts();
        assertThat(committed.get(0)).as("anomalies committed").isPositive();
        assertThat(committed.get(1)).as("forecasts committed").isPositive();
        assertThat(committed.get(2)).as("recommendations committed").isPositive();

        // Replay converges on the same run and does not rewrite the derived rows.
        assertThat(analysis.runAnalysis(org, ANALYSIS_DATE, "USD")).isEqualTo(outcome);
        assertThat(derivedCounts()).isEqualTo(committed);
    }

    @Test
    void failedRunIsTerminalAndNotRecomputed() {
        doThrow(new IllegalStateException("Injected post-generation failure"))
                .when(savings).generate(anyLong(), anyLong(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> analysis.runAnalysis(org, ANALYSIS_DATE, "USD"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(analysis.runAnalysis(org, ANALYSIS_DATE, "USD").status()).isEqualTo("FAILED");
        assertThat(derivedCounts()).containsExactly(0, 0, 0);
    }

    @Test
    void lateAppendRequiresNewInputIdentityAndPreservesEarlierForecasts() {
        var first = analysis.runAnalysis(org, ANALYSIS_DATE, "USD");
        var original = jdbc.queryForObject("SELECT input_fingerprint FROM cost_intelligence_run WHERE id=?",
                String.class, first.runId());
        var originalForecasts = jdbc.queryForObject("SELECT COUNT(*) FROM cost_forecast_snapshot WHERE run_id=?",
                Integer.class, first.runId());
        jdbc.update("INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,currency,project_id,"
                + "source_gateway_settlement_id,created_at) SELECT org_id,posting_id,999,'COST',1,currency,project_id,"
                + "source_gateway_settlement_id,UTC_TIMESTAMP(6) FROM ledger_entry WHERE org_id=? ORDER BY id DESC LIMIT 1", org);
        assertThat(inputs.currentFingerprint(org, "USD", ANALYSIS_DATE,
                com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION)).isNotEqualTo(original);
        var second = analysis.runAnalysis(org, ANALYSIS_DATE, "USD");
        assertThat(second.runId()).isNotEqualTo(first.runId());
        assertThat(second.status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_forecast_snapshot WHERE run_id=?",
                Integer.class, first.runId())).isEqualTo(originalForecasts);
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void sparseLedgerProducesNoForecastAndPreservesLegacySnapshotWithoutReusingIt() {
        jdbc.update("DELETE le FROM ledger_entry le JOIN ledger_posting lp ON lp.id=le.posting_id "
                + "WHERE le.org_id=? AND DATE(lp.posted_at) < ?", org, ANALYSIS_DATE.minusDays(1));
        jdbc.update("INSERT INTO cost_intelligence_run(org_id,analysis_date,currency,run_version,status,created_at) "
                + "VALUES (?,?,'USD',1,'COMPLETED',UTC_TIMESTAMP(6))", org, ANALYSIS_DATE);
        var oldId = jdbc.queryForObject("SELECT id FROM cost_intelligence_run WHERE org_id=? AND run_version=1",
                Long.class, org);
        jdbc.update("INSERT INTO cost_forecast_snapshot(org_id,run_id,scope_type,scope_key,currency,projected_amount,"
                + "method,history_bucket_count,confidence,observed_through,created_at) "
                + "VALUES (?,?,'ORGANIZATION',?,'USD',999,'DAMPED_HOLT',29,'HIGH',?,UTC_TIMESTAMP(6))",
                org, oldId, "org:" + org, ANALYSIS_DATE.minusDays(1));
        var outcome = analysis.runAnalysis(org, ANALYSIS_DATE, "USD");
        assertThat(outcome.status()).isEqualTo("COMPLETED");
        assertThat(outcome.runId()).isNotEqualTo(oldId);
        assertThat(jdbc.queryForObject("SELECT run_version FROM cost_intelligence_run WHERE id=?",
                Integer.class, outcome.runId())).isEqualTo(com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_forecast_snapshot WHERE run_id=?",
                Integer.class, outcome.runId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_forecast_snapshot WHERE run_id=?",
                Integer.class, oldId)).isEqualTo(1);
    }

    private void resetInsideTransactionCounts() {
        anomaliesInsideTransaction.set(0);
        forecastsInsideTransaction.set(0);
        recommendationsInsideTransaction.set(0);
    }

    private void captureInsideTransactionCounts() {
        anomaliesInsideTransaction.set(count("cost_anomaly"));
        forecastsInsideTransaction.set(count("cost_forecast_snapshot"));
        recommendationsInsideTransaction.set(count("savings_recommendation"));
    }

    /** anomalies, forecasts, recommendations — in that order. */
    private java.util.List<Integer> derivedCounts() {
        return java.util.List.of(count("cost_anomaly"), count("cost_forecast_snapshot"),
                count("savings_recommendation"));
    }

    private String runStatus() {
        return jdbc.queryForObject("SELECT status FROM cost_intelligence_run WHERE org_id=?", String.class,
                org);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE org_id=?", Integer.class, org);
    }

    // ------------------------------------------------------------------ fixtures

    private long insertProject() {
        jdbc.update("INSERT INTO project(org_id,code,name,status,created_at,updated_at)"
                + " VALUES (?,'pipeline-proj','Pipeline','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org);
        return jdbc.queryForObject("SELECT id FROM project WHERE org_id=? AND code='pipeline-proj'",
                Long.class, org);
    }

    private long insertLogicalModel() {
        var key = "pipeline-logical-" + System.nanoTime();
        jdbc.update("INSERT INTO model_catalog(model_key,name,status,capabilities_json,"
                + "default_max_output_tokens,max_output_tokens,created_at,updated_at)"
                + " VALUES (?,'Pipeline Model','ACTIVE',JSON_OBJECT(),1024,8192,"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", key);
        return jdbc.queryForObject("SELECT id FROM model_catalog WHERE model_key=?", Long.class, key);
    }

    private long insertProviderModel(long logicalId, String wireName) {
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES ('CUSTOM_OPENAI_COMPATIBLE',"
                + "'Custom OpenAI-Compatible','CUSTOM_OPENAI_COMPATIBLE','https://example.invalid',"
                + "'DISABLED',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE updated_at=UTC_TIMESTAMP(6)");
        jdbc.update("INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,"
                + "routing_eligible,capabilities_json,created_at,updated_at)"
                + " VALUES ('CUSTOM_OPENAI_COMPATIBLE',?,?,'ACTIVE',TRUE,JSON_OBJECT(),"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", logicalId, wireName);
        return jdbc.queryForObject(
                "SELECT id FROM provider_model WHERE provider_model_name=?", Long.class, wireName);
    }

    private long insertAccount(String displayName) {
        jdbc.update("INSERT INTO provider_account(org_id,provider_code,display_name,status,created_at,"
                + "updated_at) VALUES (?,'CUSTOM_OPENAI_COMPATIBLE',?,'ACTIVE',UTC_TIMESTAMP(6),"
                + "UTC_TIMESTAMP(6))", org, displayName);
        return jdbc.queryForObject("SELECT id FROM provider_account WHERE org_id=? AND display_name=?",
                Long.class, org, displayName);
    }

    private void insertConnection(long accountId) {
        jdbc.update("INSERT INTO provider_connection_profile(org_id,provider_account_id,version,"
                + "connection_kind,template_code,protocol_code,base_url,completion_path,models_path,"
                + "auth_type,auth_header_name,network_policy,user_agent,connect_timeout_ms,"
                + "response_timeout_ms,status,created_by,created_at,activated_at,retired_at)"
                + " VALUES (?,?,1,'CUSTOM',NULL,'OPENAI_CHAT_COMPLETIONS','https://example.invalid',"
                + "'/chat/completions','/models','NONE',NULL,'DIRECT_PUBLIC_ONLY',NULL,5000,60000,"
                + "'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)", org, accountId);
    }

    private long insertPricing(long accountId, long modelId, String inputPrice) {
        jdbc.update("INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,"
                + "currency,effective_from,effective_to,status,created_at)"
                + " VALUES (?,?,?,1,'USD',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 10 DAY),NULL,'ACTIVE',"
                + "UTC_TIMESTAMP(6))", org, accountId, modelId);
        var pricingId = jdbc.queryForObject("SELECT id FROM pricing_version WHERE org_id=?"
                + " AND provider_account_id=? AND provider_model_id=?", Long.class, org, accountId, modelId);
        jdbc.update("INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,"
                + "unit_price) VALUES (?,?,'INPUT_TOKEN',1,?)", org, pricingId, new BigDecimal(inputPrice));
        return pricingId;
    }

    /**
     * One settled usage fact plus one Ledger posting per day across the analysis window: 19 flat
     * days and a material spike on the final observed day. Separate postings keep a real per-day
     * series (a single posting would collapse every entry onto one posted_at).
     *
     * <p>The partial Gateway lineage is inserted with a session-level FK exemption, which only
     * holds inside one real transaction — hence the explicit template rather than bare
     * {@code JdbcTemplate} calls on a pooled connection.
     */
    private void insertSettledDailySeries(long pricingId, long accountId, long modelId, String quantity) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var settlementDay = ANALYSIS_DATE.minusDays(20);
            jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
            try {
                jdbc.update("INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,"
                        + "status,usage_effective_at,usage_effective_at_source,pricing_version_id,currency,"
                        + "observed_at,created_at) VALUES (?,1,1,1,'FINAL',?,"
                        + "'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?, 'USD',UTC_TIMESTAMP(6),"
                        + "UTC_TIMESTAMP(6))", org, settlementDay.atStartOfDay(), pricingId);
                var usageId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("INSERT INTO gateway_usage_dimension(org_id,usage_fact_id,dimension_code,"
                        + "quantity,provenance) VALUES (?,?, 'INPUT_TOKEN',?,'PROVIDER_FINAL')", org,
                        usageId, new BigDecimal(quantity));
                jdbc.update("INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,"
                        + "billing_period_id,status,posting_actor_type,posted_by_member_id,posted_at,"
                        + "created_at) VALUES (?,?,'GATEWAY_SETTLEMENT',?,1,'POSTED','SYSTEM',NULL,?,"
                        + "UTC_TIMESTAMP(6))", org, "pipeline-anchor-" + usageId, usageId,
                        settlementDay.atStartOfDay());
                var anchorPostingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("INSERT INTO gateway_settlement(org_id,settlement_key,request_id,"
                        + "route_attempt_id,usage_fact_id,billing_period_id,financial_scope_type,"
                        + "financial_scope_id,provider_account_id,provider_model_id,pricing_version_id,"
                        + "currency,calculated_amount_raw,posted_amount,rounding_delta,status,"
                        + "attempt_count,ledger_posting_id,created_at,settled_at,updated_at)"
                        + " VALUES (?,?,1,1,?,1,'PROJECT',?,?,?,?,'USD',1001,1001,0,'SETTLED',0,?,"
                        + "UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6))",
                        org, "pipeline-settle-" + usageId, usageId, projectId, accountId, modelId,
                        pricingId, anchorPostingId, settlementDay.atStartOfDay());
                var settlementId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                // Day 0..18 flatten at 10.00; day 19 (the last observed bucket) spikes to 80.00.
                for (var dayOffset = 0; dayOffset < 20; dayOffset++) {
                    var postedAt = settlementDay.plusDays(dayOffset).atStartOfDay();
                    var amount = dayOffset == 19 ? new BigDecimal("80.00000000")
                            : new BigDecimal("10.00000000");
                    jdbc.update("INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,"
                            + "billing_period_id,status,posting_actor_type,posted_by_member_id,posted_at,"
                            + "created_at) VALUES (?,?,'GATEWAY_SETTLEMENT',?,1,'POSTED','SYSTEM',NULL,"
                            + "?,UTC_TIMESTAMP(6))",
                            org, "pipeline-lp-" + usageId + "-" + dayOffset, settlementId, postedAt);
                    var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    jdbc.update("INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,"
                            + "amount,currency,project_id,team_id,cost_center_id,budget_id,"
                            + "source_charge_fact_id,source_expense_claim_id,"
                            + "source_gateway_settlement_id,source_reconciliation_adjustment_id,"
                            + "allocation_line_id,correction_group_id,reverses_entry_id,created_at)"
                            + " VALUES (?,?,0,'COST',?,'USD',?,NULL,NULL,NULL,NULL,NULL,?,NULL,NULL,NULL,"
                            + "NULL,UTC_TIMESTAMP(6))", org, postingId, amount, projectId, settlementId);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        });
    }
}
