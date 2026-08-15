package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.cost.application.CanonicalCostWritePort;
import com.aicostops.cost.application.CanonicalizationInput;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Canonical persistence boundary: service-level write() calls straight into the
 * port, DB assertions, the persistence currency guard, and the bounded-transaction
 * semantics of the full ingestion persist loop (rollback / retry lineage /
 * stale-lease fencing / ERROR zero-write).
 */
@SpringBootTest
@Tag("integration")
class ImportCanonicalizationIntegrationTest extends MySqlContainerSupport {

    private static final Instant USAGE_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant USAGE_END = Instant.parse("2026-01-02T00:00:00Z");

    private static final Map<String, Object> EMPTY_EXPORT_PAYLOAD = Map.of(
            "sourceSchema", "openai.observed-empty-export.v1",
            "recordKind", "EMPTY_USAGE_BUCKET");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CanonicalCostWritePort canonicalCostWritePort;

    @Autowired
    private ImportLeaseService leases;

    @Autowired
    private ImportRawPersistenceService persistence;

    private long orgId;

    @BeforeEach
    void setUp() {
        cleanCanonical();
        M2DatabaseCleaner.clean(jdbcTemplate);
        orgId = insertFixture();
        insertBatchWithAttempt();
    }

    @AfterEach
    void tearDown() {
        cleanCanonical();
        M2DatabaseCleaner.clean(jdbcTemplate);
    }

    private void cleanCanonical() {
        jdbcTemplate.update("DELETE FROM attribution_hint");
        jdbcTemplate.update("DELETE FROM charge_fact");
        jdbcTemplate.update("DELETE FROM pricing_fact");
        jdbcTemplate.update("DELETE FROM consumption_fact");
        jdbcTemplate.update("DELETE FROM external_document");
    }

    // ------------------------------------------------------------------
    // Service-level write() through the port
    // ------------------------------------------------------------------

    @Test
    void persistsOpenAiUsageFactsWithMetersAndProviderNativeHints() {
        write(openAiUsagePayload(), insertDirectRawRecord());

        assertThat(rows("consumption_fact")).isEqualTo(3);
        var meters = jdbcTemplate.queryForList(
                "SELECT meter_code, unit, quantity, fact_index, provider_code, model, "
                        + "provider_user_ref, provider_project_ref, provider_api_key_label "
                        + "FROM consumption_fact ORDER BY fact_index");
        assertThat(meters).hasSize(3);
        assertThat(meters.get(0)).containsEntry("METER_CODE", "input_tokens")
                .containsEntry("UNIT", "tokens")
                .containsEntry("QUANTITY", new BigDecimal("100.00000000"))
                .containsEntry("FACT_INDEX", 0)
                .containsEntry("PROVIDER_CODE", "OPENAI")
                .containsEntry("MODEL", "gpt-fake")
                .containsEntry("PROVIDER_USER_REF", "user_x")
                .containsEntry("PROVIDER_PROJECT_REF", "proj_y")
                .containsEntry("PROVIDER_API_KEY_LABEL", "key_123");
        assertThat(meters.get(2)).containsEntry("METER_CODE", "num_model_requests")
                .containsEntry("UNIT", "requests");

        assertThat(rows("attribution_hint")).isEqualTo(3);
        var hints = jdbcTemplate.queryForList(
                "SELECT hint_type, provider_value, confidence, candidate_scope_type, candidate_scope_id "
                        + "FROM attribution_hint ORDER BY fact_index");
        assertThat(hints).hasSize(3);
        assertThat(hints.get(0)).containsEntry("HINT_TYPE", "PROVIDER_USER")
                .containsEntry("PROVIDER_VALUE", "user_x");
        assertThat(hints.get(1)).containsEntry("HINT_TYPE", "PROVIDER_PROJECT")
                .containsEntry("PROVIDER_VALUE", "proj_y");
        assertThat(hints.get(2)).containsEntry("HINT_TYPE", "PROVIDER_API_KEY")
                .containsEntry("PROVIDER_VALUE", "key_123");
        assertThat(hints).allSatisfy(h -> {
            assertThat(h.get("CONFIDENCE")).isNull();
            assertThat(h.get("CANDIDATE_SCOPE_TYPE")).isNull();
            assertThat(h.get("CANDIDATE_SCOPE_ID")).isNull();
        });
    }

    @Test
    void persistsOpenAiCostChargeWithProviderFieldMetadata() {
        write("""
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"COST",
                 "dimensions":{"providerProject":"proj_y","credentialId":"key_123"},
                 "providerFields":{"lineItem":"Chat","quantity":1234},
                 "money":{"currency":"USD","reportedAmount":12.34}}
                """, insertDirectRawRecord());

        assertThat(rows("charge_fact")).isEqualTo(1);
        var charge = jdbcTemplate.queryForMap(
                "SELECT provider_code, charge_category, amount, currency, review_status, "
                        + "period_start, period_end, metadata_json FROM charge_fact");
        assertThat(charge).containsEntry("PROVIDER_CODE", "OPENAI")
                .containsEntry("CHARGE_CATEGORY", "USAGE")
                .containsEntry("AMOUNT", new BigDecimal("12.34000000"))
                .containsEntry("CURRENCY", "USD")
                .containsEntry("REVIEW_STATUS", "CLEAN");
        assertThat(charge.get("PERIOD_START")).isNotNull();
        assertThat(charge.get("PERIOD_END")).isNotNull();
        assertThat(charge.get("METADATA_JSON").toString()).contains("\"lineItem\"", "\"Chat\"")
                .contains("\"quantity\"", "\"1234\"");
        assertThat(rows("attribution_hint")).isEqualTo(2);
        assertThat(rows("consumption_fact")).isZero();
        assertThat(rows("external_document")).isZero();
    }

    @Test
    void persistsGlmDocumentWithNullCurrencyOnNullableColumn() {
        write("""
                {"sourceSchema":"glm.monthly-billing-summary-workbook.v1","recordKind":"BILLING_SUMMARY",
                 "providerFields":{"billingMonth":"2026-01","settlementStatus":"SETTLED"},
                 "money":{"components":{"catalogAmount":100.0,"consumptionAmount":90.0,
                          "creditPaymentAmount":5.0,"promotionalDeductionAmount":5.0,
                          "payableAmount":80.0,"paidAmount":80.0,"outstandingAmount":0.0}}}
                """, insertDirectRawRecord());

        assertThat(rows("external_document")).isEqualTo(1);
        var document = jdbcTemplate.queryForMap(
                "SELECT document_type, currency, reported_total_amount, reported_payable_amount, "
                        + "reported_paid_amount, reported_outstanding_amount, metadata_json "
                        + "FROM external_document");
        assertThat(document).containsEntry("DOCUMENT_TYPE", "BILL_SUMMARY")
                .containsEntry("CURRENCY", null)
                .containsEntry("REPORTED_TOTAL_AMOUNT", new BigDecimal("90.00000000"))
                .containsEntry("REPORTED_PAYABLE_AMOUNT", new BigDecimal("80.00000000"))
                .containsEntry("REPORTED_PAID_AMOUNT", new BigDecimal("80.00000000"))
                .containsEntry("REPORTED_OUTSTANDING_AMOUNT", new BigDecimal("0.00000000"));
        assertThat(document.get("METADATA_JSON").toString()).contains("\"billingMonth\"", "\"2026-01\"")
                .contains("\"catalogAmount\"", "\"100.0\"");
        assertThat(rows("consumption_fact")).isZero();
        assertThat(rows("charge_fact")).isZero();
    }

    @Test
    void acceptsExactlyThreeCharacterCurrencyOnNonNullableColumn() {
        write("""
                {"sourceSchema":"deepseek.usage-zip.v1","recordKind":"COST",
                 "dimensions":{"model":"deepseek-chat","providerUser":"ds-user"},
                 "providerFields":{"walletType":"prepaid"},
                 "money":{"currency":"CNY","reportedAmount":0.123456}}
                """, insertDirectRawRecord());

        assertThat(rows("charge_fact")).isEqualTo(1);
        var charge = jdbcTemplate.queryForMap("SELECT currency FROM charge_fact");
        assertThat(charge).containsEntry("CURRENCY", "CNY");
    }

    @Test
    void rejectsMissingCurrencyOnNonNullableChargeColumn() {
        var rawId = insertDirectRawRecord();
        assertThatThrownBy(() -> write("""
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"COST",
                 "dimensions":{"providerProject":"proj_y"},
                 "money":{"reportedAmount":12.34}}
                """, rawId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void rejectsBlankCurrencyOnNonNullableChargeColumn() {
        var rawId = insertDirectRawRecord();
        assertThatThrownBy(() -> write("""
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"COST",
                 "dimensions":{"providerProject":"proj_y"},
                 "money":{"currency":"   ","reportedAmount":12.34}}
                """, rawId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void rejectsCurrencyWithWrongLength() {
        var rawId = insertDirectRawRecord();
        assertThatThrownBy(() -> write("""
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"COST",
                 "dimensions":{"providerProject":"proj_y"},
                 "money":{"currency":"US","reportedAmount":12.34}}
                """, rawId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> write("""
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"COST",
                 "dimensions":{"providerProject":"proj_y"},
                 "money":{"currency":"USDD","reportedAmount":12.34}}
                """, rawId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void exactDecimalGuardRejectsUnrepresentableMoney() {
        var rawId = insertDirectRawRecord();
        assertThatThrownBy(() -> write("""
                {"sourceSchema":"openai.organization-costs-json.v1","recordKind":"COST",
                 "dimensions":{"providerProject":"proj_y"},
                 "money":{"currency":"USD","reportedAmount":1.123456789}}
                """, rawId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not exactly representable at scale 8");
    }

    @Test
    void emptyBatchWritesNothing() {
        write("""
                {"sourceSchema":"openai.observed-empty-export.v1","recordKind":"EMPTY_USAGE_BUCKET",
                 "providerFields":{"exportKind":"USAGE"}}
                """, insertDirectRawRecord());

        assertThat(rows("external_document")).isZero();
        assertThat(rows("consumption_fact")).isZero();
        assertThat(rows("pricing_fact")).isZero();
        assertThat(rows("charge_fact")).isZero();
        assertThat(rows("attribution_hint")).isZero();
    }

    // ------------------------------------------------------------------
    // Full persist-loop semantics: rollback, retry lineage, fencing, ERROR
    // ------------------------------------------------------------------

    @Test
    void canonicalFailureRollsBackTheWholeBoundedTransaction() {
        var lease = claimLease("canonical-worker");
        var records = List.of(
                recordWithPayload(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), EMPTY_EXPORT_PAYLOAD),
                recordWithPayload(1, RawRecordNormalizeStatus.NORMALIZED, List.of(), invalidCurrencyCostsPayload()));

        assertThatThrownBy(() -> persistence.persist(lease, records, orgId, "OPENAI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");

        assertThat(rowsOfAttempt("raw_provider_record", lease.attemptId())).isZero();
        assertThat(rows("external_document")).isZero();
        assertThat(rows("consumption_fact")).isZero();
        assertThat(rows("charge_fact")).isZero();
        assertThat(rows("attribution_hint")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT records_seen FROM import_attempt WHERE id=?",
                Long.class, lease.attemptId())).isZero();
    }

    @Test
    void retryAttemptWritesNewFactsWhileOldFactsStayUntouched() {
        // Attempt #1 on batch X persists canonical facts, then fails.
        var firstLease = claimLease("canonical-worker-1");
        var firstAttemptId = firstLease.attemptId();
        persistence.persist(firstLease, List.of(
                recordWithPayload(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), costsPayload("1.00"))),
                orgId, "OPENAI");
        var firstRawId = rawIdOf(firstAttemptId, 0);
        jdbcTemplate.update("""
                UPDATE import_attempt SET status='FAILED', finished_at=UTC_TIMESTAMP(6),
                    error_code='DATA_ERRORS' WHERE id=?
                """, firstAttemptId);
        var batchId = jdbcTemplate.queryForObject(
                "SELECT import_batch_id FROM import_attempt WHERE id=?", Long.class, firstAttemptId);

        // Attempt #2 is a MANUAL_RETRY successor on the SAME batch.
        jdbcTemplate.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,2,'QUEUED','MANUAL_RETRY',?,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId, firstAttemptId);
        var secondLease = claimLease("canonical-worker-2");
        var secondAttemptId = secondLease.attemptId();
        persistence.persist(secondLease, List.of(
                recordWithPayload(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), costsPayload("2.00"))),
                orgId, "OPENAI");
        var secondRawId = rawIdOf(secondAttemptId, 0);

        // Same-batch retry lineage.
        assertThat(secondAttemptId).isNotEqualTo(firstAttemptId);
        var lineage = jdbcTemplate.queryForMap(
                "SELECT import_batch_id, attempt_no, predecessor_attempt_id FROM import_attempt WHERE id=?",
                secondAttemptId);
        assertThat(((Number) lineage.get("import_batch_id")).longValue()).isEqualTo(batchId);
        assertThat(((Number) lineage.get("attempt_no")).longValue()).isEqualTo(2);
        assertThat(((Number) lineage.get("predecessor_attempt_id")).longValue()).isEqualTo(firstAttemptId);

        // Old facts under attempt #1's raw row, new facts under attempt #2's raw row.
        assertThat(firstRawId).isNotEqualTo(secondRawId);
        assertThat(rows("charge_fact")).isEqualTo(2);
        assertThat(rows("attribution_hint")).isEqualTo(4);
        assertThat(chargeAmountOf(firstRawId)).isEqualByComparingTo("1.00");
        assertThat(chargeAmountOf(secondRawId)).isEqualByComparingTo("2.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charge_fact WHERE raw_record_id=?",
                Integer.class, firstRawId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charge_fact WHERE raw_record_id=?",
                Integer.class, secondRawId)).isEqualTo(1);
        // No deletion/overwrite: both raw rows survive under their own attempts.
        assertThat(rowsOfAttempt("raw_provider_record", firstAttemptId)).isEqualTo(1);
        assertThat(rowsOfAttempt("raw_provider_record", secondAttemptId)).isEqualTo(1);
    }

    @Test
    void staleLeaseWritesZeroRawAndCanonicalRows() {
        var lease = claimLease("canonical-worker");
        jdbcTemplate.update("""
                UPDATE import_attempt SET lease_owner='usurper', lease_version=lease_version+10 WHERE id=?
                """, lease.attemptId());

        var result = persistence.persist(lease, List.of(
                recordWithPayload(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), costsPayload("1.00"))),
                orgId, "OPENAI");

        assertThat(result.leaseLost()).isTrue();
        assertThat(result.recordsPersisted()).isZero();
        assertThat(rowsOfAttempt("raw_provider_record", lease.attemptId())).isZero();
        assertThat(rows("charge_fact")).isZero();
        assertThat(rows("attribution_hint")).isZero();
        assertThat(rows("consumption_fact")).isZero();
        assertThat(rows("external_document")).isZero();
    }

    @Test
    void errorRecordsPersistRawRowsButNeverCanonicalFacts() {
        var lease = claimLease("canonical-worker");
        var records = List.of(recordWithPayload(0, RawRecordNormalizeStatus.ERROR, List.of(
                new ImportIssueDraft(ImportIssueSeverity.ERROR, "INVALID_REQUIRED_NUMBER",
                        "row=1", "input_tokens", "must be an integral number", "masked")),
                costsPayload("1.00")));

        var result = persistence.persist(lease, records, orgId, "OPENAI");

        assertThat(result.recordsPersisted()).isEqualTo(1);
        assertThat(rowsOfAttempt("raw_provider_record", lease.attemptId())).isEqualTo(1);
        assertThat(rows("charge_fact")).isZero();
        assertThat(rows("attribution_hint")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_count FROM import_attempt WHERE id=?",
                Long.class, lease.attemptId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // R1 review fix: one sanitized normalized JSON source
    // ------------------------------------------------------------------

    @Test
    void persistedNormalizedUsageMetersStayNumericAndMatchCanonicalQuantity() {
        var lease = claimLease("canonical-worker");
        var records = List.of(recordWithPayload(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of(
                "sourceSchema", "openai.organization-usage-completions-json.v1",
                "recordKind", "USAGE",
                "dimensions", Map.of("providerUser", "user_x"),
                "usage", Map.of("inputTokens", 123L, "outputTokens", 0L, "numModelRequests", 2L))));

        persistence.persist(lease, records, orgId, "OPENAI");

        var storedInputTokens = jdbcTemplate.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(normalized_payload, '$.usage.inputTokens'))
                FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0
                """, String.class, lease.attemptId());
        assertThat(storedInputTokens).isEqualTo("123");
        var storedOutputTokens = jdbcTemplate.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(normalized_payload, '$.usage.outputTokens'))
                FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0
                """, String.class, lease.attemptId());
        assertThat(storedOutputTokens).isEqualTo("0");

        var inputQuantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM consumption_fact WHERE raw_record_id=? AND fact_index=0",
                BigDecimal.class, rawIdOf(lease.attemptId(), 0));
        assertThat(inputQuantity).isEqualByComparingTo(storedInputTokens);
        var outputQuantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM consumption_fact WHERE raw_record_id=? AND fact_index=1",
                BigDecimal.class, rawIdOf(lease.attemptId(), 0));
        assertThat(outputQuantity).isEqualByComparingTo("0");
    }

    @Test
    void secretShapedNormalizedIdentityNeverReachesCanonicalTables() {
        var lease = claimLease("canonical-worker");
        var records = List.of(recordWithPayload(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of(
                "sourceSchema", "deepseek.usage-zip.v1",
                "recordKind", "USAGE",
                "dimensions", Map.of("providerUser", "ds-user"),
                "providerFields", Map.of("credentialLabel", "sk-SECRET-SENTINEL-12345678"))));

        persistence.persist(lease, records, orgId, "DEEPSEEK");

        var stored = jdbcTemplate.queryForObject("""
                SELECT normalized_payload FROM raw_provider_record
                WHERE import_attempt_id=? AND record_index=0
                """, String.class, lease.attemptId());
        assertThat(stored).doesNotContain("SECRET-SENTINEL");

        // Only the safe provider-user hint survives; the credential is never a hint.
        var hints = jdbcTemplate.queryForList(
                "SELECT hint_type, provider_value FROM attribution_hint");
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0)).containsEntry("HINT_TYPE", "PROVIDER_USER")
                .containsEntry("PROVIDER_VALUE", "ds-user");
        assertThat(hints.get(0).values()).doesNotContain("sk-SECRET-SENTINEL-12345678");
        assertThat(jdbcTemplate.queryForList(
                "SELECT provider_value, metadata_json FROM attribution_hint"))
                .allSatisfy(row -> assertThat(row.toString()).doesNotContain("SECRET-SENTINEL"));
        assertThat(jdbcTemplate.queryForList(
                "SELECT metadata_json FROM charge_fact WHERE metadata_json IS NOT NULL"))
                .allSatisfy(row -> assertThat(row.toString()).doesNotContain("SECRET-SENTINEL"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ImportLeaseService.ImportLease claimLease(String worker) {
        return leases.claimNext(worker).orElseThrow();
    }

    private void write(String payloadJson, long rawId) {
        var input = new CanonicalizationInput(orgId, "OPENAI", 1L, rawId, 0L,
                "data[0].results[0]", payloadJson, USAGE_START, USAGE_END);
        canonicalCostWritePort.write(input);
    }

    private String openAiUsagePayload() {
        return """
                {"sourceSchema":"openai.organization-usage-completions-json.v1","recordKind":"USAGE",
                 "dimensions":{"model":"gpt-fake","providerUser":"user_x","providerProject":"proj_y","credentialId":"key_123"},
                 "usage":{"inputTokens":100,"outputTokens":50,"numModelRequests":3}}
                """;
    }

    private static Map<String, Object> costsPayload(String amount) {
        return Map.of(
                "sourceSchema", "openai.organization-costs-json.v1",
                "recordKind", "COST",
                "dimensions", Map.of("providerProject", "proj_y", "credentialId", "key_123"),
                "money", Map.of("currency", "USD", "reportedAmount", new BigDecimal(amount)));
    }

    private static Map<String, Object> invalidCurrencyCostsPayload() {
        return Map.of(
                "sourceSchema", "openai.organization-costs-json.v1",
                "recordKind", "COST",
                "dimensions", Map.of("providerProject", "proj_y"),
                "money", Map.of("currency", "TOOLONG", "reportedAmount", new BigDecimal("1.00")));
    }

    private static NormalizedProviderRecord recordWithPayload(
            int index, RawRecordNormalizeStatus status, List<ImportIssueDraft> issues,
            Map<String, Object> normalized) {
        return new NormalizedProviderRecord(index, "cost.csv:row=" + (index + 1), "record-" + index,
                Map.of("row", index), normalized, null, null, status, issues);
    }

    private long rows(String table) {
        var count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private long rowsOfAttempt(String table, long attemptId) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE import_attempt_id=?", Long.class, attemptId);
        return count == null ? 0 : count;
    }

    private long rawIdOf(long attemptId, int recordIndex) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=?",
                Long.class, attemptId, recordIndex);
    }

    private BigDecimal chargeAmountOf(long rawRecordId) {
        return jdbcTemplate.queryForObject(
                "SELECT amount FROM charge_fact WHERE raw_record_id=?", BigDecimal.class, rawRecordId);
    }

    private long insertFixture() {
        jdbcTemplate.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "Canonical Org", "canonical-" + System.nanoTime());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug LIKE 'canonical-%' ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    private long insertBatchWithAttempt() {
        var sha256 = "d" + String.format("%063d", System.nanoTime());
        jdbcTemplate.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?, 'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "canonical-" + System.nanoTime() + "@example.com", "Canonical User");
        var userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized LIKE 'canonical-%@example.com' "
                        + "ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        var memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
        jdbcTemplate.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha256, "org/" + orgId + "/evidence/" + sha256, "usage.csv", "text/csv", 1L, memberId);
        var evidenceId = jdbcTemplate.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
        jdbcTemplate.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "OPENAI", "Canonical Account " + System.nanoTime());
        var accountId = jdbcTemplate.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND provider_code='OPENAI'
                ORDER BY id DESC LIMIT 1
                """, Long.class, orgId);
        jdbcTemplate.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, accountId, "OPENAI", "USAGE_API_JSON", "test-parser-v1", memberId);
        var batchId = jdbcTemplate.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbcTemplate.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'QUEUED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        return batchId;
    }

    private long insertDirectRawRecord() {
        var attemptId = jdbcTemplate.queryForObject(
                "SELECT id FROM import_attempt ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,'cost.csv:row=1',NULL,JSON_OBJECT(),JSON_OBJECT(),
                    '2026-01-01 00:00:00','2026-01-02 00:00:00','NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
    }
}
