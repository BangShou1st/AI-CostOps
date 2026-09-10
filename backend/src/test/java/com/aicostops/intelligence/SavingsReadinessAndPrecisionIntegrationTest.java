package com.aicostops.intelligence;

import static org.junit.jupiter.api.Assertions.*;

import com.aicostops.intelligence.application.CostIntelligenceService;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Savings readiness and fractional precision over real MySQL (M18 round-3 P1): a fully
 * provisioned/credentialed/priced/connection-ready candidate that is NOT referenced by any ACTIVE
 * routing revision is still recommendable (flagged routing-change-required), and fractional
 * DECIMAL(30,8) usage replays exactly with no long truncation.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=savings-test-only-signing-secret-with-more-than-32-bytes" })
@Transactional
@Tag("integration")
class SavingsReadinessAndPrecisionIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private CostIntelligenceService intelligence;
    @Autowired
    private IntelligenceMapper store;
    @Autowired
    private CostFactsMapper facts;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void nonRoutedCheaperCandidateIsRecommendedWithFlagAndExactMoney() {
        var org = insertOrg();
        var logical = insertLogicalModel("sav-logical");
        var accountA = insertAccount(org, "Account A");
        var accountB = insertAccount(org, "Account B");
        var modelA = insertProviderModel(logical, "sav-wire-a");
        var modelB = insertProviderModel(logical, "sav-wire-b");
        var pricingA = insertPricing(org, accountA, modelA, "2.00");
        insertPricing(org, accountB, modelB, "1.00");
        insertConnection(org, accountA);
        insertConnection(org, accountB);
        // Only the expensive source route has historical usage (fractional), settled in-window.
        insertUsage(org, pricingA, accountA, modelA, "500.50000000");
        var yesterday = LocalDate.now().minusDays(1);
        var outcome = intelligence.runAnalysis(org, yesterday, "USD");
        assertEquals("COMPLETED", outcome.status());
        var runId = store.findRunId(org, yesterday, "USD",
                CostIntelligenceService.RUN_VERSION);
        assertNotNull(runId);
        var recs = store.listRecommendations(org, runId);
        assertEquals(1, recs.size());
        var rec = recs.get(0);
        assertEquals(accountA, rec.currentProviderAccountId());
        assertEquals(accountB, rec.candidateProviderAccountId());
        assertEquals(0, rec.currentCost().compareTo(new BigDecimal("1001.00")));
        assertEquals(0, rec.candidateCost().compareTo(new BigDecimal("500.50")));
        assertTrue(rec.routingChangeRequired(),
                "Candidate B is production-ready but not routed; the flag must be set");
    }

    @Test
    void pricedCandidatesDoNotRequireRoutingReferences() {
        var org = insertOrg();
        var logical = insertLogicalModel("sav-logical-nr");
        var accountA = insertAccount(org, "Account A");
        var accountB = insertAccount(org, "Account B");
        var modelA = insertProviderModel(logical, "sav-nr-a");
        var modelB = insertProviderModel(logical, "sav-nr-b");
        insertPricing(org, accountA, modelA, "2.00");
        insertPricing(org, accountB, modelB, "1.00");
        insertConnection(org, accountA);
        insertConnection(org, accountB);
        var candidates = facts.pricingCandidates(org, "USD", logical, java.time.Instant.now());
        assertEquals(2, candidates.size());
    }

    private long insertOrg() {
        jdbc.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)"
                + " VALUES ('Sav Org','sav-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug='sav-org'", Long.class);
    }

    private long insertAccount(long org, String name) {
        jdbc.update("INSERT INTO provider_account(org_id,provider_code,display_name,status,created_at,updated_at)"
                + " VALUES (?,'CUSTOM_OPENAI_COMPATIBLE',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                org, name);
        return jdbc.queryForObject("SELECT id FROM provider_account WHERE org_id=? AND display_name=?",
                Long.class, org, name);
    }

    private long insertLogicalModel(String key) {
        jdbc.update("INSERT INTO model_catalog(model_key,name,status,capabilities_json,"
                + "default_max_output_tokens,max_output_tokens,created_at,updated_at)"
                + " VALUES (?,'Savings Model','ACTIVE',JSON_OBJECT(),1024,8192,"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", key);
        return jdbc.queryForObject("SELECT id FROM model_catalog WHERE model_key=?", Long.class, key);
    }

    private long insertProviderModel(long logicalId, String wireName) {
        // Full-suite order wipes provider_catalog between classes; reseed idempotently.
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES ('CUSTOM_OPENAI_COMPATIBLE',"
                + "'Custom OpenAI-Compatible','CUSTOM_OPENAI_COMPATIBLE','https://example.invalid',"
                + "'DISABLED',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE updated_at=UTC_TIMESTAMP(6)");
        jdbc.update("INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,"
                + "routing_eligible,capabilities_json,created_at,updated_at)"
                + " VALUES ('CUSTOM_OPENAI_COMPATIBLE',?,?,'ACTIVE',TRUE,JSON_OBJECT(),"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                logicalId, wireName);
        return jdbc.queryForObject(
                "SELECT id FROM provider_model WHERE provider_model_name=?", Long.class, wireName);
    }

    private long insertPricing(long org, long accountId, long modelId, String inputPrice) {
        jdbc.update("INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,"
                + "currency,effective_from,effective_to,status,created_at)"
                + " VALUES (?,?,?,1,'USD',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 10 DAY),NULL,'ACTIVE',"
                + "UTC_TIMESTAMP(6))",
                org, accountId, modelId);
        var pricingId = jdbc.queryForObject("SELECT id FROM pricing_version"
                + " WHERE org_id=? AND provider_account_id=? AND provider_model_id=?", Long.class, org,
                accountId, modelId);
        jdbc.update("INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,"
                + "unit_price) VALUES (?,?, 'INPUT_TOKEN',1,?)", org, pricingId,
                new BigDecimal(inputPrice));
        return pricingId;
    }

    private void insertConnection(long org, long accountId) {
        jdbc.update("INSERT INTO provider_connection_profile(org_id,provider_account_id,version,"
                + "connection_kind,template_code,protocol_code,base_url,completion_path,models_path,auth_type,"
                + "auth_header_name,network_policy,user_agent,connect_timeout_ms,response_timeout_ms,status,"
                + "created_by,created_at,activated_at,retired_at)"
                + " VALUES (?, ?,1,'CUSTOM',NULL,'OPENAI_CHAT_COMPLETIONS','https://example.invalid',"
                + "'/chat/completions','/models','NONE',NULL,'DIRECT_PUBLIC_ONLY',NULL,5000,60000,'ACTIVE',"
                + "NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)", org, accountId);
    }

    private void insertUsage(long org, long pricingId, long accountId, long modelId, String quantity) {
        var settledAt = LocalDate.now().minusDays(5).atStartOfDay();
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,status,"
                    + "usage_effective_at,usage_effective_at_source,pricing_version_id,currency,observed_at,"
                    + "created_at) VALUES (?,1,1,1,'FINAL',?,"
                    + "'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?, 'USD',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    org, settledAt, pricingId);
            var usageId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO gateway_usage_dimension(org_id,usage_fact_id,dimension_code,quantity,"
                    + "provenance) VALUES (?,?, 'INPUT_TOKEN',?,'PROVIDER_FINAL')", org, usageId,
                    new BigDecimal(quantity));
            jdbc.update("INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,"
                    + "billing_period_id,status,posting_actor_type,posted_by_member_id,posted_at,created_at)"
                    + " VALUES (?,?,'GATEWAY_SETTLEMENT',?,1,'POSTED','SYSTEM',NULL,?,UTC_TIMESTAMP(6))",
                    org, "sav-lp-" + usageId, usageId, settledAt);
            var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO gateway_settlement(org_id,settlement_key,request_id,route_attempt_id,"
                    + "usage_fact_id,billing_period_id,financial_scope_type,financial_scope_id,"
                    + "provider_account_id,provider_model_id,pricing_version_id,currency,calculated_amount_raw,"
                    + "posted_amount,rounding_delta,status,attempt_count,ledger_posting_id,"
                    + "created_at,settled_at,updated_at)"
                    + " VALUES (?,? ,1,1,?,1,'PROJECT',1,?,?,?,'USD',1001,1001,0,'SETTLED',0,?,"
                    + "UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6))",
                    org, "sav-settle-" + usageId, usageId, accountId, modelId, pricingId, postingId,
                    settledAt);
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }
}
