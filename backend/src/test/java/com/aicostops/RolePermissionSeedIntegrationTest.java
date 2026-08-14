package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class RolePermissionSeedIntegrationTest extends MySqlContainerSupport {

    private static final Set<String> ROLES = Set.of(
            "EMPLOYEE", "PROJECT_OWNER", "FINANCE_REVIEWER", "FINANCE_ADMIN", "SYSTEM_ADMIN");

    private static final Set<String> PERMISSIONS = Set.of(
            "USER_READ", "USER_MANAGE", "USER_INVITE", "ROLE_READ", "ROLE_ASSIGN",
            "PROJECT_READ", "PROJECT_MANAGE", "PROJECT_MEMBER_MANAGE", "TEAM_READ", "TEAM_MANAGE",
            "COST_CENTER_READ", "COST_CENTER_MANAGE", "PROVIDER_ACCOUNT_READ", "PROVIDER_ACCOUNT_MANAGE",
            "EVIDENCE_UPLOAD_OWN", "EVIDENCE_UPLOAD_PROVIDER", "EVIDENCE_READ", "EVIDENCE_DOWNLOAD",
            "IMPORT_READ", "IMPORT_RETRY", "IMPORT_CONFIRM", "IMPORT_CANCEL", "COST_READ", "DUPLICATE_REVIEW",
            "ALLOCATION_READ", "ALLOCATION_EDIT", "ALLOCATION_CONFIRM", "ALLOCATION_RULE_MANAGE",
            "EXPENSE_CREATE_OWN", "EXPENSE_READ_OWN", "EXPENSE_SUBMIT_OWN", "EXPENSE_REVIEW", "EXPENSE_POST",
            "BUDGET_READ", "BUDGET_MANAGE", "COMMITMENT_REQUEST", "COMMITMENT_APPROVE", "COMMITMENT_RELEASE",
            "LEDGER_READ", "LEDGER_POST", "LEDGER_CORRECT", "RECONCILIATION_READ", "RECONCILIATION_RUN",
            "RECONCILIATION_RESOLVE", "PERIOD_READ", "PERIOD_CLOSE", "PERIOD_REOPEN", "AUDIT_READ");

    private static final Map<String, Set<String>> EXPECTED_MAPPINGS = Map.of(
            "EMPLOYEE", Set.of("EVIDENCE_UPLOAD_OWN", "EXPENSE_CREATE_OWN", "EXPENSE_READ_OWN",
                    "EXPENSE_SUBMIT_OWN", "COMMITMENT_REQUEST"),
            "PROJECT_OWNER", Set.of("EVIDENCE_UPLOAD_OWN", "EXPENSE_CREATE_OWN", "EXPENSE_READ_OWN",
                    "EXPENSE_SUBMIT_OWN", "PROJECT_READ", "PROJECT_MEMBER_MANAGE", "COST_READ",
                    "DUPLICATE_REVIEW", "ALLOCATION_READ", "ALLOCATION_EDIT", "ALLOCATION_CONFIRM",
                    "BUDGET_READ", "COMMITMENT_REQUEST", "COMMITMENT_APPROVE"),
            "FINANCE_REVIEWER", Set.of("EVIDENCE_UPLOAD_PROVIDER", "EVIDENCE_READ", "EVIDENCE_DOWNLOAD",
                    "IMPORT_READ", "IMPORT_RETRY", "IMPORT_CONFIRM", "IMPORT_CANCEL", "COST_READ",
                    "DUPLICATE_REVIEW", "ALLOCATION_READ", "ALLOCATION_EDIT", "ALLOCATION_CONFIRM",
                    "EXPENSE_REVIEW", "EXPENSE_POST", "BUDGET_READ", "COMMITMENT_REQUEST",
                    "COMMITMENT_APPROVE", "COMMITMENT_RELEASE", "LEDGER_READ", "LEDGER_POST",
                    "RECONCILIATION_READ", "RECONCILIATION_RUN", "RECONCILIATION_RESOLVE", "PERIOD_READ",
                    "AUDIT_READ", "PROVIDER_ACCOUNT_READ"),
            "FINANCE_ADMIN", Set.of("EVIDENCE_UPLOAD_PROVIDER", "EVIDENCE_READ", "EVIDENCE_DOWNLOAD",
                    "IMPORT_READ", "IMPORT_RETRY", "IMPORT_CONFIRM", "IMPORT_CANCEL", "COST_READ",
                    "DUPLICATE_REVIEW", "ALLOCATION_READ", "ALLOCATION_EDIT", "ALLOCATION_CONFIRM",
                    "ALLOCATION_RULE_MANAGE", "EXPENSE_REVIEW", "EXPENSE_POST", "BUDGET_READ",
                    "BUDGET_MANAGE", "COMMITMENT_REQUEST", "COMMITMENT_APPROVE", "COMMITMENT_RELEASE",
                    "LEDGER_READ", "LEDGER_POST", "LEDGER_CORRECT", "RECONCILIATION_READ",
                    "RECONCILIATION_RUN", "RECONCILIATION_RESOLVE", "PERIOD_READ", "PERIOD_CLOSE",
                    "PERIOD_REOPEN", "PROVIDER_ACCOUNT_READ", "PROVIDER_ACCOUNT_MANAGE", "AUDIT_READ"),
            "SYSTEM_ADMIN", Set.of("USER_READ", "USER_MANAGE", "USER_INVITE", "ROLE_READ", "ROLE_ASSIGN",
                    "PROJECT_READ", "PROJECT_MANAGE", "PROJECT_MEMBER_MANAGE", "TEAM_READ", "TEAM_MANAGE",
                    "COST_CENTER_READ", "COST_CENTER_MANAGE", "PROVIDER_ACCOUNT_READ",
                    "PROVIDER_ACCOUNT_MANAGE", "AUDIT_READ"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seedsTheExactDocumentedRoleAndPermissionCatalogs() {
        assertThat(Set.copyOf(jdbcTemplate.queryForList("SELECT code FROM role", String.class))).isEqualTo(ROLES);
        assertThat(Set.copyOf(jdbcTemplate.queryForList("SELECT code FROM permission", String.class))).isEqualTo(PERMISSIONS);
    }

    @Test
    void seedsEveryDocumentedRolePermissionMappingExactly() {
        var mappings = mappingsByRole();

        assertThat(mappings).isEqualTo(EXPECTED_MAPPINGS);
        assertThat(mappings.get("SYSTEM_ADMIN")).doesNotContain(
                "LEDGER_POST", "LEDGER_CORRECT", "BUDGET_MANAGE", "PERIOD_CLOSE", "PERIOD_REOPEN");
        assertThat(mappings.get("FINANCE_REVIEWER"))
                .contains("PROVIDER_ACCOUNT_READ")
                .doesNotContain("PROVIDER_ACCOUNT_MANAGE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM role_permission", Integer.class))
                .isEqualTo(EXPECTED_MAPPINGS.values().stream().mapToInt(Set::size).sum());
    }

    private Map<String, Set<String>> mappingsByRole() {
        return jdbcTemplate.queryForList("""
                        SELECT r.code AS role_code, p.code AS permission_code
                        FROM role_permission rp
                        JOIN role r ON r.id=rp.role_id
                        JOIN permission p ON p.id=rp.permission_id
                        """).stream().collect(Collectors.groupingBy(
                        row -> (String) row.get("role_code"),
                        Collectors.mapping(row -> (String) row.get("permission_code"), Collectors.toSet())));
    }
}
