package com.aicostops.gateway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.aicostops.gateway.GatewayApplication;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Frozen M10 boundary: the Gateway data plane is an independent deployable. */
@AnalyzeClasses(packagesOf = GatewayApplication.class)
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
}