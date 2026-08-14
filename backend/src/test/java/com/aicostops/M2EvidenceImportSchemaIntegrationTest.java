package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class M2EvidenceImportSchemaIntegrationTest extends MySqlContainerSupport {

    private static final Set<String> M2_TABLES = Set.of(
            "evidence", "import_batch", "import_attempt", "raw_provider_record", "import_issue");

    private static final Set<String> M2_INDEXES = Set.of(
            "uq_evidence_org_sha256",
            "uq_import_batch_identity",
            "uq_import_attempt_batch_no",
            "idx_import_attempt_queue",
            "idx_import_attempt_lease",
            "idx_import_attempt_batch_status",
            "uq_raw_provider_record_attempt_index");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesEveryM2EvidenceAndImportTable() {
        var tables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class));

        assertThat(tables).containsAll(M2_TABLES);
    }

    @Test
    void createsM2DeduplicationUniqueIndexesAndQueueLookupIndexes() {
        var indexes = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE()",
                String.class));

        assertThat(indexes).containsAll(M2_INDEXES);
    }

    @Test
    void evidenceForeignKeysReferenceOrganizationAndUploaderMembership() {
        assertThat(foreignKeysOf("evidence")).contains(
                "org_id -> organization.id",
                "uploaded_by_member_id -> organization_member.id");
    }

    @Test
    void importBatchForeignKeysReferenceOrganizationEvidenceProviderAccountAndCreatorMembership() {
        assertThat(foreignKeysOf("import_batch")).contains(
                "org_id -> organization.id",
                "evidence_id -> evidence.id",
                "provider_account_id -> provider_account.id",
                "created_by_member_id -> organization_member.id");
    }

    @Test
    void importAttemptForeignKeysReferenceItsBatchAndPredecessorAttempt() {
        assertThat(foreignKeysOf("import_attempt")).contains(
                "import_batch_id -> import_batch.id",
                "predecessor_attempt_id -> import_attempt.id");
    }

    @Test
    void rawProviderRecordForeignKeyReferencesItsImportAttempt() {
        assertThat(foreignKeysOf("raw_provider_record")).contains(
                "import_attempt_id -> import_attempt.id");
    }

    @Test
    void importIssueForeignKeysReferenceImportAttemptAndRawProviderRecord() {
        assertThat(foreignKeysOf("import_issue")).contains(
                "import_attempt_id -> import_attempt.id",
                "raw_provider_record_id -> raw_provider_record.id");
    }

    private Set<String> foreignKeysOf(String table) {
        return Set.copyOf(jdbcTemplate.query(
                "SELECT column_name, referenced_table_name, referenced_column_name "
                        + "FROM information_schema.key_column_usage "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND referenced_table_name IS NOT NULL",
                (rs, rowNum) -> rs.getString("column_name") + " -> "
                        + rs.getString("referenced_table_name") + "." + rs.getString("referenced_column_name"),
                table));
    }
}
