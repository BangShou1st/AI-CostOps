package com.aicostops.intelligence;

import static org.junit.jupiter.api.Assertions.*;

import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ledger-truth cost series over real MySQL (M18 round-3 P1): the intelligence series equals the
 * effective POSTED ledger lineage — initial settlement postings, reconciliation adjustments,
 * signed correction reversal/replacement pairs — plus legal direct Provider Charge effects in the
 * aggregate grains. Provider/model grains never invent attribution for lineage-free charges.
 *
 * <p>FK checks are disabled inside the test transaction (rolled back afterwards) so the read path
 * can be proven without fabricating entire Gateway/import object graphs; all joined lineage used
 * for attribution assertions is inserted for real.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=ledger-test-only-signing-secret-with-more-than-32-bytes" })
@Transactional
@Tag("integration")
class LedgerCostFactsIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private CostFactsMapper facts;
    @Autowired
    private JdbcTemplate jdbc;

    private long org;
    private long projectA;
    private long accountId;
    private final LocalDate day = LocalDate.now().minusDays(5);

    private void setUp() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)"
                    + " VALUES ('Ledger Org','ledger-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),"
                    + "UTC_TIMESTAMP(6))");
            org = jdbc.queryForObject("SELECT id FROM organization WHERE slug='ledger-org'", Long.class);
            jdbc.update("INSERT INTO project(org_id,code,name,status,created_at,updated_at)"
                    + " VALUES (?,'proj-a','A','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org);
            projectA = jdbc.queryForObject("SELECT id FROM project WHERE org_id=? AND code='proj-a'",
                    Long.class, org);
            jdbc.update("INSERT INTO provider_account(org_id,provider_code,display_name,status,created_at,"
                    + "updated_at) VALUES (?,'CUSTOM_OPENAI_COMPATIBLE','Ledger Account','ACTIVE',"
                    + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org);
            accountId = jdbc.queryForObject(
                    "SELECT id FROM provider_account WHERE org_id=? AND display_name='Ledger Account'",
                    Long.class, org);
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    @Test
    void settlementPlusAdjustmentDefinesOrgSeries() {
        setUp();
        postGatewayEntry("COST", "10.00000000", projectA);
        postEntry("ADJUSTMENT", "2.00000000", "adjustment", 8001L, projectA);
        assertAmount("12.00000000", orgTotal());
        assertAmount("12.00000000", scopedTotal(facts.projectDaily(org, "USD", day, day.plusDays(1))));
    }

    @Test
    void correctionReversalNetsToReplacementWithoutDuplicates() {
        setUp();
        var target = postGatewayEntry("COST", "10.00000000", projectA);
        var settlementId = jdbc.queryForObject(
                "SELECT source_gateway_settlement_id FROM ledger_entry WHERE id=?", Long.class,
                target.entryId());
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("INSERT INTO correction_group(org_id,correction_key,reason_code,reason_text,"
                    + "target_entry_id,target_posting_id,status,created_by_member_id,created_at)"
                    + " VALUES (?,?,'TEST','test',?,?, 'POSTED',1,UTC_TIMESTAMP(6))",
                    org, "corr-1", target.entryId(), target.postingId());
            var groupId = jdbc.queryForObject(
                    "SELECT id FROM correction_group WHERE org_id=? AND correction_key='corr-1'", Long.class,
                    org);
            var correctionPosting = postPosting("CORRECTION", groupId);
            insertEntry(correctionPosting, 0, "REVERSAL", "-10.00000000", "gateway", settlementId,
                    projectA, groupId, target.entryId());
            insertEntry(correctionPosting, 1, "ADJUSTMENT", "8.00000000", "gateway", settlementId,
                    projectA, groupId, null);
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        // 10 - 10 + 8: history preserved, effective lineage nets to the replacement exactly once.
        assertAmount("8.00000000", orgTotal());
    }

    @Test
    void directChargeVisibleInTotalsButNeverInventedPerProvider() {
        setUp();
        postGatewayEntry("COST", "10.00000000", projectA);
        postEntry("COST", "3.00000000", "charge", 7001L, projectA);
        assertAmount("13.00000000", orgTotal());
        assertAmount("13.00000000", scopedTotal(facts.projectDaily(org, "USD", day, day.plusDays(1))));
        // No import lineage exists for charge 7001, so the provider grain must not invent one:
        // the provider series sees only the settlement lineage.
        assertAmount("10.00000000",
                scopedTotal(facts.providerDaily(org, "USD", day, day.plusDays(1))));
    }

    @Test
    void directChargeWithImportLineageAttributesProvider() {
        setUp();
        // The rewiring UPDATE inside insertImportChain targets the placeholder 7002, so the
        // ledger entry must exist before the import chain is inserted.
        postEntry("COST", "3.00000000", "charge", 7002L, projectA);
        var batchId = insertImportChain();
        var provider = jdbc.queryForObject("SELECT provider_account_id FROM import_batch WHERE id=?",
                Long.class, batchId);
        assertEquals(accountId, provider);
        var byProvider = facts.providerDaily(org, "USD", day, day.plusDays(1)).stream()
                .collect(Collectors.toMap(r -> r.scopeId(), r -> r.amount()));
        assertAmount("3.00000000", byProvider.get(accountId));
    }

    private record PostedIds(long postingId, long entryId) {
    }

    private PostedIds postGatewayEntry(String type, String amount, long project) {
        var settlementId = insertSettlement();
        var posting = postPosting("GATEWAY_SETTLEMENT", settlementId);
        var entry = insertEntry(posting, 0, type, amount, "gateway", settlementId, project, null, null);
        return new PostedIds(posting, entry);
    }

    private long insertSettlement() {
        var nano = System.nanoTime();
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("INSERT INTO gateway_settlement(org_id,settlement_key,request_id,route_attempt_id,"
                    + "usage_fact_id,billing_period_id,financial_scope_type,financial_scope_id,"
                    + "provider_account_id,provider_model_id,pricing_version_id,currency,"
                    + "calculated_amount_raw,posted_amount,rounding_delta,status,attempt_count,"
                    + "created_at,settled_at,updated_at)"
                    + " VALUES (?,? ,?,?,?,1,'PROJECT',?,?,?,?, 'USD',10,10,0,'PENDING',0,"
                    + "UTC_TIMESTAMP(6),NULL,UTC_TIMESTAMP(6))",
                    org, "ledger-settle-" + nano, nano % 1000000000L, nano % 1000000000L + 1,
                    nano % 1000000000L + 2, projectA, accountId, 1, 1);
            return jdbc.queryForObject("SELECT id FROM gateway_settlement WHERE org_id=? AND settlement_key=?",
                    Long.class, org, "ledger-settle-" + nano);
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private PostedIds postEntry(String type, String amount, String sourceKind, long sourceId, long project) {
        var postingType = switch (sourceKind) {
            case "gateway" -> "GATEWAY_SETTLEMENT";
            case "adjustment" -> "RECONCILIATION_ADJUSTMENT";
            default -> "PROVIDER_CHARGE";
        };
        var posting = postPosting(postingType, sourceId);
        var entry = insertEntry(posting, 0, type, amount, sourceKind, sourceId, project, null, null);
        return new PostedIds(posting, entry);
    }

    private long postPosting(String sourceType, long sourceId) {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,"
                    + "billing_period_id,status,posting_actor_type,posted_by_member_id,posted_at,created_at)"
                    + " VALUES (?,?,?,? ,1,'POSTED','SYSTEM',NULL,?,UTC_TIMESTAMP(6))",
                    org, "lp-" + sourceType + "-" + sourceId + "-" + System.nanoTime(), sourceType,
                    sourceId, day.atStartOfDay().plusHours(12));
            return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private long insertEntry(long postingId, int index, String type, String amount, String sourceKind,
            long sourceId, long project, Long correctionGroupId, Long reversesEntryId) {
        Long gatewayId = "gateway".equals(sourceKind) ? sourceId : null;
        Long adjustmentId = "adjustment".equals(sourceKind) ? sourceId : null;
        Long chargeId = "charge".equals(sourceKind) ? sourceId : null;
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,currency,"
                    + "project_id,team_id,cost_center_id,budget_id,source_charge_fact_id,"
                    + "source_expense_claim_id,source_gateway_settlement_id,"
                    + "source_reconciliation_adjustment_id,allocation_line_id,correction_group_id,"
                    + "reverses_entry_id,created_at)"
                    + " VALUES (?,?,?, ?,?,'USD',?,NULL,NULL,NULL,?,NULL,?,?,NULL,?,?,UTC_TIMESTAMP(6))",
                    org, postingId, index, type, new BigDecimal(amount), project, chargeId, gatewayId,
                    adjustmentId, correctionGroupId, reversesEntryId);
            return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private long insertImportChain() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("INSERT INTO import_batch(org_id,evidence_id,provider_account_id,"
                    + "expected_provider_code,source_type,parser_version,status,created_by_member_id,"
                    + "created_at,updated_at)"
                    + " VALUES (?,1,?,'CUSTOM_OPENAI_COMPATIBLE','CSV','v1','PARSED',1,"
                    + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    org, accountId);
            var batchId = jdbc.queryForObject(
                    "SELECT id FROM import_batch WHERE org_id=? AND provider_account_id=?", Long.class, org,
                    accountId);
            jdbc.update("INSERT INTO import_attempt(import_batch_id,attempt_no,status,trigger_type,"
                    + "available_at,parser_version,created_at)"
                    + " VALUES (?,1,'SUCCEEDED','INITIAL',UTC_TIMESTAMP(6),'v1',UTC_TIMESTAMP(6))", batchId);
            var attemptId = jdbc.queryForObject(
                    "SELECT id FROM import_attempt WHERE import_batch_id=?", Long.class, batchId);
            jdbc.update("INSERT INTO raw_provider_record(import_attempt_id,record_index,record_locator,"
                    + "raw_payload,normalize_status,created_at)"
                    + " VALUES (?,0,'r1',CAST('{}' AS JSON),'NORMALIZED',UTC_TIMESTAMP(6))", attemptId);
            var rawId = jdbc.queryForObject(
                    "SELECT id FROM raw_provider_record WHERE import_attempt_id=?", Long.class, attemptId);
            jdbc.update("INSERT INTO charge_fact(org_id,raw_record_id,fact_index,provider_code,"
                    + "charge_category,amount,currency,review_status,created_at)"
                    + " VALUES (?,?,0,'CUSTOM_OPENAI_COMPATIBLE','USAGE',3.00000000,'USD','CLEAN',"
                    + "UTC_TIMESTAMP(6))",
                    org, rawId);
            var chargeId = jdbc.queryForObject("SELECT id FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                    Long.class, org, rawId);
            // Rewire the direct-charge ledger entry used by the attribution test to this charge.
            jdbc.update("UPDATE ledger_entry SET source_charge_fact_id=?"
                    + " WHERE org_id=? AND source_charge_fact_id=7002", chargeId, org);
            return batchId;
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private BigDecimal orgTotal() {
        return facts.orgDaily(org, "USD", day, day.plusDays(1)).stream().map(r -> r.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal scopedTotal(java.util.List<? extends Object> rows) {
        return rows.stream().map(row -> {
            if (row instanceof CostFactsMapper.ScopedDailyTotal scoped) return scoped.amount();
            if (row instanceof CostFactsMapper.LabeledDailyTotal labeled) return labeled.amount();
            throw new IllegalStateException("Unexpected grain row");
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected);
    }
}
