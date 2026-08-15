package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class M2ImportWorkflowReviewIndexIntegrationTest extends MySqlContainerSupport {

    private static final List<IndexExpectation> REVIEW_INDEXES = List.of(
            new IndexExpectation("evidence", "idx_evidence_org_created", "org_id,created_at,id"),
            new IndexExpectation("import_batch", "idx_import_batch_org_created", "org_id,created_at,id"),
            new IndexExpectation("import_batch", "idx_import_batch_org_status_created",
                    "org_id,status,created_at,id"),
            new IndexExpectation("import_batch", "idx_import_batch_org_provider_created",
                    "org_id,provider_account_id,created_at,id"),
            new IndexExpectation("raw_provider_record", "idx_raw_provider_record_attempt_status_index",
                    "import_attempt_id,normalize_status,record_index,id"),
            new IndexExpectation("import_issue", "idx_import_issue_attempt_severity_id",
                    "import_attempt_id,severity,id"),
            new IndexExpectation("import_issue", "idx_import_issue_attempt_code_id",
                    "import_attempt_id,issue_code,id"));

    private record IndexExpectation(String table, String indexName, String expectedColumns) {
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void addsEveryApprovedReviewIndexWithLeadingColumnOrder() {
        for (var expectation : REVIEW_INDEXES) {
            assertIndex(expectation.table(), expectation.indexName(), expectation.expectedColumns());
        }
    }

    @Test
    void keepsExistingWorkflowUniqueConstraintsIntact() {
        var indexes = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name IN "
                        + "('evidence','import_batch','import_attempt','raw_provider_record','import_issue')",
                String.class));

        assertThat(indexes).contains(
                "uq_raw_provider_record_attempt_index",
                "uq_import_attempt_batch_no",
                "uq_evidence_org_sha256",
                "uq_import_batch_identity");
    }

    private void assertIndex(String table, String indexName, String expectedColumns) {
        var columns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                ORDER BY seq_in_index
                """, String.class, table, indexName);
        assertThat(columns).as("index %s on %s", indexName, table)
                .containsExactly(expectedColumns.split(","));
    }
}
