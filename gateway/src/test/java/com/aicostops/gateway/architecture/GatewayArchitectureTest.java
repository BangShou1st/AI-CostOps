package com.aicostops.gateway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.aicostops.gateway.GatewayApplication;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;
import org.junit.jupiter.api.Tag;

/** Frozen M10/HC-04 boundary: the Gateway data plane is an independent deployable. */
@AnalyzeClasses(
        packagesOf = GatewayApplication.class,
        importOptions = ImportOption.DoNotIncludeTests.class)
@Tag("architecture")
class GatewayArchitectureTest {

    @ArchTest
    static final ArchRule gateway_never_depends_on_backend_module_classes =
            noClasses()
                    .that()
                    .resideInAPackage("com.aicostops.gateway..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.aicostops.allocation..",
                            "com.aicostops.attribution..",
                            "com.aicostops.audit..",
                            "com.aicostops.budget..",
                            "com.aicostops.cost..",
                            "com.aicostops.evidence..",
                            "com.aicostops.expense..",
                            "com.aicostops.iam..",
                            "com.aicostops.ingestion..",
                            "com.aicostops.ledger..",
                            "com.aicostops.organization..",
                            "com.aicostops.reconciliation..",
                            "com.aicostops.reporting..",
                            "com.aicostops.shared..")
                    .as("Gateway must never reference Control Plane / CostOps Core backend classes");

    @ArchTest
    static final ArchRule gateway_never_depends_on_flyway =
            noClasses()
                    .that()
                    .resideInAPackage("com.aicostops.gateway..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.flywaydb..")
                    .as("Backend is the sole production Flyway migration owner; Gateway has no Flyway");

    @ArchTest
    static final ArchRule gateway_never_references_backend_application_package =
            noClasses()
                    .that()
                    .resideInAPackage("com.aicostops.gateway..")
                    .should()
                    .dependOnClassesThat()
                    .haveSimpleName("AiCostOpsApplication")
                    .orShould()
                    .dependOnClassesThat()
                    .resideInAPackage("com.aicostops.gatewayadmin..")
                    .as("Gateway must stay independent of the backend entrypoint and admin module");

    @ArchTest
    static final ArchRule gateway_mappers_are_plain_mybatis_not_springdata =
            noFields()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage("com.aicostops.gateway..")
                    .should()
                    .beAnnotatedWith("org.springframework.data.jpa.repository.JpaRepository")
                    .as("Gateway persistence is plain JDBC/MyBatis; JPA is not part of the data plane");

    @ArchTest
    static final ArchRule gateway_has_no_netty_eventloop_in_main_by_default =
            noClasses()
                    .that()
                    .resideInAPackage("com.aicostops.gateway..")
                    .should()
                    .callMethod("reactor.core.scheduler.Schedulers", "boundedElastic")
                    .as("The shared global bounded-elastic scheduler is never exposed as the DB boundary");

    @ArchTest
    static final ArchRule gateway_never_blocks_on_reactive_mono_in_db_path =
            noClasses()
                    .that()
                    .resideInAPackage("com.aicostops.gateway.request..")
                    .or()
                    .resideInAPackage("com.aicostops.gateway.budget..")
                    .and()
                    .haveSimpleNameNotEndingWith("Test")
                    .and()
                    .haveSimpleNameNotEndingWith("Tests")
                    .should()
                    .callMethod("reactor.core.publisher.Mono", "block")
                    .as("Synchronous DB-path production code must use sync entries, never Mono.block on the bounded scheduler");

    private static final Set<String> ALLOWED_BUDGET_MUTATION_COLUMNS = Set.of(
            "reserved_amount", "version", "updated_at");
    private static final Set<String> FORBIDDEN_BUDGET_COLUMNS = Set.of(
            "actual_amount", "committed_amount", "total_amount");
    private static final Pattern MUTATING_TARGET_PATTERN = Pattern.compile(
            "(?:(?<!\\bfor\\s)\\bupdate\\s+([a-z0-9_]+)\\s+(?:[a-z_][a-z0-9_]*\\s+)?set\\b|\\b(?:insert\\s+(?:ignore\\s+)?into|delete\\s+from|replace\\s+into)\\s+([a-z0-9_]+)\\b)");
    private static final Pattern UPDATE_BUDGET_SET_PATTERN = Pattern.compile(
            "(?<!\\bfor\\s)\\bupdate\\s+budget\\b(?:\\s+[a-z_][a-z0-9_]*)?\\s+set\\s+(.*?)\\s+where\\b");

    @ArchTest
    static final ArchRule gateway_budget_writes_only_its_own_tables =
            noClasses()
                    .that()
                    .resideInAPackage("com.aicostops.gateway.budget..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.aicostops.ledger..",
                            "com.aicostops.budget..",
                            "com.aicostops.reconciliation..")
                    .as("Gateway budget code uses its own mappers; Ledger/Budget Actual/Commitment/Settlement stay backend-owned");

    @ArchTest
    static final ArchRule gateway_production_never_uses_jdbc_template =
            noClasses()
                    .that()
                    .resideInAPackage("com.aicostops.gateway..")
                    .and()
                    .haveSimpleNameNotEndingWith("Test")
                    .and()
                    .haveSimpleNameNotEndingWith("Tests")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.springframework.jdbc.core..")
                    .as("Gateway SQL must stay inside MyBatis mappers so ArchUnit can audit all mutations");

    @ArchTest
    static final ArchRule gateway_mappers_only_mutate_budget_reserved_amount =
            methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage("com.aicostops.gateway..")
                    .and()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(Mapper.class)
                    .should(onlyMutateAllowedBudgetColumnsAndNeverTouchLedgerOrSettlement())
                    .as("Gateway mappers may only mutate budget.reserved_amount and never actual_amount, committed_amount, total_amount, ledger_*, or gateway_settlement");

    private static ArchCondition<JavaMethod> onlyMutateAllowedBudgetColumnsAndNeverTouchLedgerOrSettlement() {
        return new ArchCondition<>("only mutate budget.reserved_amount and never ledger_* or gateway_settlement") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (var annotation : method.getAnnotations()) {
                    var typeName = annotation.getRawType().getName();
                    if (typeName.equals(UpdateProvider.class.getName())
                            || typeName.equals(InsertProvider.class.getName())
                            || typeName.equals(DeleteProvider.class.getName())) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " uses dynamic SQL provider " + typeName));
                        continue;
                    }
                    boolean mutating = typeName.equals(Update.class.getName())
                            || typeName.equals(Insert.class.getName())
                            || typeName.equals(Delete.class.getName());
                    boolean select = typeName.equals(Select.class.getName());
                    if (!mutating && !select) {
                        continue;
                    }
                    var rawValue = annotation.get("value").orElse(null);
                    if (!(rawValue instanceof String[] parts)) {
                        continue;
                    }
                    var sql = normalizeSql(String.join(" ", parts));
                    if (select) {
                        if (MUTATING_TARGET_PATTERN.matcher(sql).find()) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " @Select contains mutating SQL: " + sql));
                        }
                        continue;
                    }
                    var violation = findMutatingSqlViolation(sql);
                    if (violation != null) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " violates Gateway SQL boundary: " + violation));
                    }
                }
            }
        };
    }

    static String normalizeSql(String rawSql) {
        return rawSql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    static String findMutatingSqlViolation(String normalizedSql) {
        if (normalizedSql.contains("ledger_") || normalizedSql.contains("gateway_settlement")) {
            return "mutating SQL references ledger_* or gateway_settlement: " + normalizedSql;
        }
        var targetMatcher = MUTATING_TARGET_PATTERN.matcher(normalizedSql);
        while (targetMatcher.find()) {
            var targetTable = targetMatcher.group(1) != null
                    ? targetMatcher.group(1)
                    : targetMatcher.group(2);
            if ("budget".equals(targetTable)) {
                if (!normalizedSql.startsWith("update budget")) {
                    return "Gateway may only UPDATE budget, never INSERT/DELETE: " + normalizedSql;
                }
                var setMatcher = UPDATE_BUDGET_SET_PATTERN.matcher(normalizedSql);
                if (!setMatcher.find()) {
                    return "UPDATE budget must have explicit SET ... WHERE clause: " + normalizedSql;
                }
                var setClause = setMatcher.group(1);
                for (var assignment : splitTopLevelCommas(setClause)) {
                    var eqIdx = assignment.indexOf('=');
                    if (eqIdx <= 0) {
                        return "Malformed SET assignment in UPDATE budget: " + assignment;
                    }
                    var col = assignment.substring(0, eqIdx).trim();
                    var dotIdx = col.lastIndexOf('.');
                    if (dotIdx >= 0) {
                        col = col.substring(dotIdx + 1).trim();
                    }
                    if (!ALLOWED_BUDGET_MUTATION_COLUMNS.contains(col)) {
                        return "UPDATE budget mutates disallowed column '" + col + "' in: " + normalizedSql;
                    }
                }
            } else {
                var setIdx = normalizedSql.indexOf(" set ");
                var whereIdx = normalizedSql.indexOf(" where ");
                var checkRegion = setIdx >= 0
                        ? (whereIdx > setIdx ? normalizedSql.substring(setIdx, whereIdx) : normalizedSql.substring(setIdx))
                        : normalizedSql;
                for (var forbiddenCol : FORBIDDEN_BUDGET_COLUMNS) {
                    if (checkRegion.contains(forbiddenCol)) {
                        return "Mutating SQL references forbidden budget column '" + forbiddenCol + "': " + normalizedSql;
                    }
                }
            }
        }
        return null;
    }

    private static java.util.List<String> splitTopLevelCommas(String input) {
        var result = new java.util.ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')' && depth > 0) {
                depth--;
            } else if (ch == ',' && depth == 0) {
                result.add(input.substring(start, i));
                start = i + 1;
            }
        }
        result.add(input.substring(start));
        return result;
    }
}
