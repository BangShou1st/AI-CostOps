package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.ingestion.application.ImportWorkflowReadModels.AttemptSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.EvidenceRef;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.ImportSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.IssueSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.RawRecordSummary;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Tag("integration")
class ImportWorkflowQueryServiceIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ImportWorkflowQueryService queries;

    private final ObjectMapper json = new ObjectMapper();

    private long organizationId;
    private long foreignOrganizationId;
    private long actorUserId;
    private long actorMemberId;
    private long evidenceId;
    private long otherEvidenceId;
    private long foreignEvidenceId;
    private long accountId;
    private long otherAccountId;
    private long batchId;
    private long otherBatchId;
    private long foreignBatchId;
    private long attempt1Id;
    private long attempt2Id;
    private long attempt3Id;
    private long otherAttemptId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Import Workflow", "import-workflow");
        foreignOrganizationId = insertOrganization("Foreign", "import-workflow-foreign");
        actorUserId = insertUser("import-reviewer@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("IMPORT_READER", List.of("IMPORT_READ"));
        assign("IMPORT_READER", "ORG", organizationId);

        evidenceId = insertEvidence(organizationId, "1".repeat(64), "2026-08-01 08:00:00");
        otherEvidenceId = insertEvidence(organizationId, "2".repeat(64), "2026-08-02 08:00:00");
        foreignEvidenceId = insertEvidence(foreignOrganizationId, "3".repeat(64), "2026-08-03 08:00:00");
        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        otherAccountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Secondary");
        batchId = insertBatch(organizationId, evidenceId, accountId, "TEST_PROVIDER", "FAILED",
                "2026-08-03 09:00:00");
        otherBatchId = insertBatch(organizationId, otherEvidenceId, otherAccountId, "TEST_PROVIDER", "PARSED",
                "2026-08-04 09:00:00");
        foreignBatchId = insertBatch(foreignOrganizationId, foreignEvidenceId, accountId, "TEST_PROVIDER",
                "PENDING", "2026-08-05 09:00:00");
        attempt1Id = insertAttempt(batchId, 1, "FAILED", "INITIAL", null, "2026-08-03 09:00:00");
        attempt2Id = insertAttempt(batchId, 2, "SUCCEEDED", "MANUAL_RETRY", attempt1Id, "2026-08-03 10:00:00");
        attempt3Id = insertAttempt(batchId, 3, "QUEUED", "MANUAL_RETRY", attempt2Id, "2026-08-03 11:00:00");
        otherAttemptId = insertAttempt(otherBatchId, 1, "SUCCEEDED", "INITIAL", null, "2026-08-04 09:00:00");
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void listsImportsNewestFirstWithStatusAndProviderAccountFilters() {
        var all = queries.listImports(user(), 0, 50, null, null);
        assertThat(all.items()).extracting(ImportSummary::id).containsExactly(otherBatchId, batchId);
        assertThat(all.totalElements()).isEqualTo(2);

        var failed = queries.listImports(user(), 0, 50, "FAILED", null);
        assertThat(failed.items()).extracting(ImportSummary::id).containsExactly(batchId);

        var byAccount = queries.listImports(user(), 0, 50, null, otherAccountId);
        assertThat(byAccount.items()).extracting(ImportSummary::id).containsExactly(otherBatchId);

        var empty = queries.listImports(user(), 0, 50, "CANCELED", null);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.totalElements()).isZero();
    }

    @Test
    void importSummaryCarriesLineageRefsAndStateDerivedFlags() {
        var summary = queries.getImport(user(), batchId);
        assertThat(summary.id()).isEqualTo(batchId);
        assertThat(summary.evidence()).isEqualTo(new EvidenceRef(evidenceId, evidenceFilename(evidenceId)));
        assertThat(summary.providerAccount().displayName()).isEqualTo("Primary");
        assertThat(summary.status().name()).isEqualTo("FAILED");
        assertThat(summary.latestAttempt().attemptNo()).isEqualTo(3);
        assertThat(summary.latestAttempt().predecessorAttemptId()).isEqualTo(attempt2Id);
        assertThat(summary.retryable()).isTrue();
        assertThat(summary.cancelable()).isFalse();

        var parsed = queries.getImport(user(), otherBatchId);
        assertThat(parsed.retryable()).isFalse();
        assertThat(parsed.cancelable()).isFalse();
    }

    @Test
    void listsEvidenceImportsForAnOrgEvidence() {
        var page = queries.listEvidenceImports(user(), evidenceId, 0, 50);
        assertThat(page.items()).extracting(ImportSummary::id).containsExactly(batchId);
    }

    @Test
    void listsAttemptsNewestAttemptNoFirst() {
        var page = queries.listAttempts(user(), batchId, 0, 50);
        assertThat(page.items()).extracting(AttemptSummary::attemptNo).containsExactly(3, 2, 1);
        assertThat(page.totalElements()).isEqualTo(3);
    }

    @Test
    void listsIssuesByIdAscendingWithSeverityAndCodeFilters() {
        insertIssue(attempt1Id, null, "WARN", "FIELD_EMPTY", "row=1", "model", "model is empty", "sk-x", 1);
        insertIssue(attempt1Id, null, "ERROR", "UNKNOWN_FIELD", "row=2", "cost", "cost unknown", null, 2);
        insertIssue(attempt1Id, null, "WARN", "FIELD_EMPTY", "row=3", "usage", "usage empty", null, 3);

        var all = queries.listIssues(user(), batchId, attempt1Id, 0, 50, null, null);
        assertThat(all.items()).extracting(IssueSummary::id).containsExactly(1L, 2L, 3L);
        assertThat(all.items()).extracting(IssueSummary::severity)
                .containsExactly(ImportIssueSeverity.WARN, ImportIssueSeverity.ERROR, ImportIssueSeverity.WARN);

        var warns = queries.listIssues(user(), batchId, attempt1Id, 0, 50, "WARN", null);
        assertThat(warns.items()).extracting(IssueSummary::id).containsExactly(1L, 3L);

        var emptyField = queries.listIssues(user(), batchId, attempt1Id, 0, 50, null, "UNKNOWN_FIELD");
        assertThat(emptyField.items()).extracting(IssueSummary::id).containsExactly(2L);
    }

    @Test
    void listsRawRecordsByIndexWithNormalizeStatusFilterAndKeySummariesOnly() {
        insertRawRecord(attempt3Id, 0, "cost.csv:row=1", "row-key-1",
                Map.of("model", "x", "future_note", "safe", "api_key", "[REDACTED]"),
                Map.of("model", "x"), "NORMALIZED", "2026-08-03 11:00:00");
        insertRawRecord(attempt3Id, 1, "cost.csv:row=2", "row-key-2",
                Map.of("model", "y"), null, "WARN", "2026-08-03 11:01:00");

        var page = queries.listRawRecords(user(), batchId, attempt3Id, 0, 50, null);
        assertThat(page.items()).extracting(RawRecordSummary::recordIndex).containsExactly(0L, 1L);
        assertThat(page.totalElements()).isEqualTo(2);

        var warned = queries.listRawRecords(user(), batchId, attempt3Id, 0, 50, "WARN");
        assertThat(warned.items()).extracting(RawRecordSummary::recordIndex).containsExactly(1L);

        var first = page.items().get(0);
        assertThat(first.rawPayloadKeys().keyCount()).isEqualTo(3);
        assertThat(first.rawPayloadKeys().keys()).containsExactlyInAnyOrder("model", "future_note", "api_key");
        assertThat(first.rawPayloadKeys().keysTruncated()).isFalse();
        assertThat(first.normalizedPayloadKeys().keyCount()).isEqualTo(1);
        assertThat(first.normalizedPayloadKeys().keys()).containsExactly("model");

        assertThat(page.items().toString()).doesNotContain("\"x\"", "\"safe\"");
    }

    @Test
    void rawListKeySummaryIsBoundedWhenPayloadHasMoreThanThirtyTwoKeys() {
        var wide = new LinkedHashMap<String, Object>();
        for (var i = 0; i < 40; i++) {
            wide.put("field_" + i, "value-" + i);
        }
        insertRawRecord(attempt3Id, 0, "wide.csv:row=1", "wide-key", wide, null, "NORMALIZED",
                "2026-08-03 11:00:00");

        var page = queries.listRawRecords(user(), batchId, attempt3Id, 0, 50, null);
        var summary = page.items().get(0);
        assertThat(summary.rawPayloadKeys().keyCount()).isEqualTo(40);
        assertThat(summary.rawPayloadKeys().keysTruncated()).isTrue();
        assertThat(summary.rawPayloadKeys().keys()).hasSizeLessThanOrEqualTo(32);
        assertThat(page.items().toString()).doesNotContain("value-39");
    }

    @Test
    void rawDetailLazyLoadsRedactedPayloads() {
        insertRawRecord(attempt3Id, 0, "cost.csv:row=1", "row-key-1",
                Map.of("model", "x", "future_note", "safe", "api_key", "[REDACTED]"),
                Map.of("model", "x"), "NORMALIZED", "2026-08-03 11:00:00");
        var recordId = jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attempt3Id);

        var detail = queries.getRawRecord(user(), batchId, attempt3Id, recordId);
        @SuppressWarnings("unchecked")
        var raw = (Map<String, Object>) detail.rawPayload();
        assertThat(raw).containsEntry("model", "x");
        assertThat(raw).containsEntry("api_key", "[REDACTED]");
        assertThat(detail.normalizedPayload()).isInstanceOf(Map.class);
    }

    @Test
    void paginationBoundsAreEnforcedByServerSideQueries() {
        insertIssue(attempt1Id, null, "WARN", "FIELD_EMPTY", "row=1", "model", "m", null, 1);
        insertIssue(attempt1Id, null, "ERROR", "UNKNOWN_FIELD", "row=2", "cost", "c", null, 2);
        insertIssue(attempt1Id, null, "WARN", "FIELD_EMPTY", "row=3", "usage", "u", null, 3);

        var page = queries.listIssues(user(), batchId, attempt1Id, 0, 2, null, null);
        assertThat(page.items()).hasSize(2);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);

        var second = queries.listIssues(user(), batchId, attempt1Id, 1, 2, null, null);
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().get(0).id()).isEqualTo(3L);
    }

    @Test
    void parentChildMismatchIsNotFound() {
        assertThatThrownBy(() -> queries.listAttempts(user(), 999999L, 0, 50))
                .isInstanceOf(DomainException.class).satisfies(this::isNotFound);
        assertThatThrownBy(() -> queries.listIssues(user(), batchId, otherAttemptId, 0, 50, null, null))
                .isInstanceOf(DomainException.class).satisfies(this::isNotFound);
        assertThatThrownBy(() -> queries.listRawRecords(user(), batchId, otherAttemptId, 0, 50, null))
                .isInstanceOf(DomainException.class).satisfies(this::isNotFound);
        assertThatThrownBy(() -> queries.getRawRecord(user(), batchId, attempt1Id, 999999L))
                .isInstanceOf(DomainException.class).satisfies(this::isNotFound);
    }

    @Test
    void crossOrganizationResourcesAreNotFoundAfterPermission() {
        assertThatThrownBy(() -> queries.getImport(user(), foreignBatchId))
                .isInstanceOf(DomainException.class).satisfies(this::isNotFound);
        assertThatThrownBy(() -> queries.listEvidenceImports(user(), foreignEvidenceId, 0, 50))
                .isInstanceOf(DomainException.class).satisfies(this::isNotFound);
        assertThatThrownBy(() -> queries.listAttempts(user(), foreignBatchId, 0, 50))
                .isInstanceOf(DomainException.class).satisfies(this::isNotFound);
    }

    @Test
    void missingReadPermissionIsForbidden() {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        assertThatThrownBy(() -> queries.listImports(user(), 0, 50, null, null))
                .isInstanceOf(DomainException.class).satisfies(this::isForbidden);
    }

    @Test
    void unknownFiltersFailClosedWithValidationError() {
        assertThatThrownBy(() -> queries.listImports(user(), 0, 50, "INVENTED", null))
                .isInstanceOf(DomainException.class).satisfies(this::isValidationFailed);
        assertThatThrownBy(() -> queries.listIssues(user(), batchId, attempt1Id, 0, 50, "FATAL", null))
                .isInstanceOf(DomainException.class).satisfies(this::isValidationFailed);
        assertThatThrownBy(() -> queries.listRawRecords(user(), batchId, attempt3Id, 0, 50, "INVENTED"))
                .isInstanceOf(DomainException.class).satisfies(this::isValidationFailed);
    }

    @Test
    void invalidPaginationFailsValidationInsteadOfClamping() {
        assertThatThrownBy(() -> queries.listImports(user(), -1, 50, null, null))
                .isInstanceOf(DomainException.class).satisfies(this::isValidationFailed);
        assertThatThrownBy(() -> queries.listImports(user(), 0, 0, null, null))
                .isInstanceOf(DomainException.class).satisfies(this::isValidationFailed);
        assertThatThrownBy(() -> queries.listImports(user(), 0, 201, null, null))
                .isInstanceOf(DomainException.class).satisfies(this::isValidationFailed);
    }

    private void isNotFound(Throwable throwable) {
        assertDomain(throwable, 404, "RESOURCE_NOT_FOUND");
    }

    private void isForbidden(Throwable throwable) {
        assertDomain(throwable, 403, "FORBIDDEN");
    }

    private void isValidationFailed(Throwable throwable) {
        assertDomain(throwable, 400, "VALIDATION_FAILED");
    }

    private void assertDomain(Throwable throwable, int status, String code) {
        assertThat(throwable).isInstanceOf(DomainException.class);
        var exception = (DomainException) throwable;
        assertThat(exception.status().value()).isEqualTo(status);
        assertThat(exception.code().name()).isEqualTo(code);
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(actorUserId, 7);
    }

    private String evidenceFilename(long evId) {
        return "evidence-" + evId + ".csv";
    }

    private long insertEvidence(long orgId, String sha256, String createdAt) {
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,'pending.csv','text/csv',1,?,'AVAILABLE',NULL,?,?)
                """, orgId, sha256, "org/" + orgId + "/evidence/" + sha256, actorMemberId, createdAt, createdAt);
        var evId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
        jdbc.update("UPDATE evidence SET original_filename=? WHERE id=?",
                "evidence-" + evId + ".csv", evId);
        return evId;
    }

    private long insertProviderAccount(long orgId, String providerCode, String displayName) {
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, displayName);
        return jdbc.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND provider_code=? AND display_name=?
                """, Long.class, orgId, providerCode, displayName);
    }

    private long insertBatch(long orgId, long evId, long account, String providerCode, String status,
            String createdAt) {
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,NULL,NULL,?,?,?)
                """, orgId, evId, account, providerCode, "FILE_EXPORT", "test-parser-v1", status,
                actorMemberId, createdAt, createdAt);
        return jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?", Long.class, evId);
    }

    private long insertAttempt(long batch, int attemptNo, String status, String trigger, Long predecessor,
            String createdAt) {
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,?,?,?,?,?,NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,'ERR','summary',0,0,0,0,?)
                """, batch, attemptNo, status, trigger, predecessor, createdAt, createdAt);
        return jdbc.queryForObject("""
                SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=?
                """, Long.class, batch, attemptNo);
    }

    private void insertRawRecord(long attempt, long recordIndex, String locator, String recordKey,
            Map<String, Object> raw, Map<String, Object> normalized, String normalizeStatus, String createdAt) {
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),NULL,NULL,?,?)
                """, attempt, recordIndex, locator, recordKey, writeJson(raw), writeJson(normalized),
                normalizeStatus, createdAt);
    }

    private void insertIssue(long attempt, Long recordId, String severity, String code, String locator,
            String fieldName, String message, String rawValueMasked, long id) {
        jdbc.update("""
                INSERT INTO import_issue(
                    id,import_attempt_id,raw_provider_record_id,severity,issue_code,record_locator,
                    field_name,message,raw_value_masked,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, id, attempt, recordId, severity, code, locator, fieldName, message, rawValueMasked);
    }

    private String writeJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Test JSON serialization failed", exception);
        }
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code='IMPORT_READER'
                """);
        jdbc.update("DELETE FROM `role` WHERE code='IMPORT_READER'");
    }

    private void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, roleCode, permission);
        }
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, scopeType, scopeId, roleCode);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,'Import Reviewer','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }
}
