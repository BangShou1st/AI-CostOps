package com.aicostops.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ModuleDependencyArchitectureTest {

    @Test
    void sharedDependsOnlyOnFoundationLibraries() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule sharedDependencyRule = classes()
                .that().resideInAPackage("com.aicostops.shared..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "com.fasterxml.jackson..",
                        "tools.jackson..");

        sharedDependencyRule.check(productionClasses);
    }

    @Test
    void evidenceMustNotDependOnIngestion() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule evidenceRule = classes()
                .that().resideInAPackage("com.aicostops.evidence..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages("com.aicostops.ingestion..");

        evidenceRule.check(productionClasses);
    }

    @Test
    void ingestionMustNotDependOnCanonicalCostAndPlanningModules() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule ingestionRule = classes()
                .that().resideInAPackage("com.aicostops.ingestion..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "com.aicostops.ledger..",
                        "com.aicostops.budget..",
                        "com.aicostops.attribution..",
                        "com.aicostops.reporting..");

        ingestionRule.check(productionClasses);
    }

    @Test
    void ingestionMayDependOnEvidenceAndOrganizationReadPorts() {
        // Guard against over-restrictive future edits: ingestion -> evidence and
        // ingestion -> organization remain legal, so the rule only asserts the
        // absence of the forbidden modules (covered by ingestionMustNotDependOn...).
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule allowedIngestionDependencies = classes()
                .that().resideInAPackage("com.aicostops.ingestion.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.ingestion..",
                        "com.aicostops.cost.application..",
                        "com.aicostops.evidence..",
                        "com.aicostops.organization..",
                        "com.aicostops.iam..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        allowedIngestionDependencies.check(productionClasses);
    }

    @Test
    void costMustNotDependOnIngestion() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule costRule = classes()
                .that().resideInAPackage("com.aicostops.cost..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages("com.aicostops.ingestion..");

        costRule.check(productionClasses);
    }

    @Test
    void costMustNotDependOnPlanningModules() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule costRule = classes()
                .that().resideInAPackage("com.aicostops.cost..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "com.aicostops.attribution..",
                        "com.aicostops.ledger..",
                        "com.aicostops.budget..",
                        "com.aicostops.reporting..");

        costRule.check(productionClasses);
    }

    @Test
    void costReviewDomainDependsOnlyOnCostDomainAndShared() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.cost.review.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.cost.review.domain..",
                        "com.aicostops.cost.domain..",
                        "com.aicostops.shared..",
                        "java..");

        rule.check(productionClasses);
    }

    @Test
    void costReviewApplicationNeverReachesInfrastructureOrPersistence() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.cost.review.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.cost.review.application..",
                        "com.aicostops.cost.review.domain..",
                        "com.aicostops.cost.domain..",
                        // AuthorizationContextService.current(user) hands the caller
                        // iam.domain.AuthorizationContext; using that return type is the
                        // documented integration style (ingestion allows all of iam..).
                        "com.aicostops.iam.application..",
                        "com.aicostops.iam.domain..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        rule.check(productionClasses);
    }

    @Test
    void costReviewInfrastructureDependsOnlyOnReviewLayersAuditAndFramework() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.cost.review.infrastructure..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.cost.review.infrastructure..",
                        "com.aicostops.cost.review.application..",
                        "com.aicostops.cost.review.domain..",
                        "com.aicostops.cost.domain..",
                        "com.aicostops.audit.application..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "org.apache.ibatis..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        rule.check(productionClasses);
    }

    @Test
    void costReviewApiDependsOnlyOnReviewApplicationAndDomain() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.cost.review.api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.cost.review.api..",
                        "com.aicostops.cost.review.application..",
                        "com.aicostops.cost.review.domain..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..");

        rule.check(productionClasses);
    }

    @Test
    void budgetDomainStaysFrameworkFree() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.budget.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.budget.domain..",
                        // Budget scope_type reuses the IAM ScopeType enum.
                        "com.aicostops.iam.domain..",
                        "com.aicostops.shared..",
                        "java..");

        rule.check(productionClasses);
    }

    @Test
    void budgetApplicationNeverReachesInfrastructureOrWorkflowModules() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.budget.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        // Application services use the module's own mapper
                        // directly (Evidence-module style).
                        "com.aicostops.budget..",
                        // The active same-org target directory is the documented
                        // scope/target validation seam.
                        "com.aicostops.attribution.application..",
                        "com.aicostops.attribution.domain..",
                        "com.aicostops.iam.application..",
                        "com.aicostops.iam.domain..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        rule.check(productionClasses);
    }

    @Test
    void budgetInfrastructureDependsOnlyOnBudgetLayersAuditAndFramework() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.budget.infrastructure..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.budget.infrastructure..",
                        "com.aicostops.budget.application..",
                        "com.aicostops.budget.domain..",
                        "com.aicostops.audit.application..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "org.apache.ibatis..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        rule.check(productionClasses);
    }

    @Test
    void budgetApiDependsOnlyOnBudgetApplicationAndShared() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.budget.api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.budget.api..",
                        "com.aicostops.budget.application..",
                        "com.aicostops.budget.domain..",
                        "com.aicostops.iam.domain..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..");

        rule.check(productionClasses);
    }

    @Test
    void attributionMustNotDependOnWorkflowOrIdentityModules() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.attribution..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "com.aicostops.ingestion..",
                        "com.aicostops.evidence..",
                        "com.aicostops.iam..",
                        "com.aicostops.audit..");

        rule.check(productionClasses);
    }

    @Test
    void attributionDomainStaysFrameworkFree() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.attribution.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.attribution.domain..",
                        "com.aicostops.shared..",
                        "java..")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    void attributionApplicationDependsOnlyOnDomainAndShared() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.attribution.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.attribution.application..",
                        "com.aicostops.attribution.domain..",
                        "com.aicostops.shared..",
                        "java..")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    void attributionInfrastructureDependsOnlyOnAttributionLayersAndFramework() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.attribution.infrastructure..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.attribution.infrastructure..",
                        "com.aicostops.attribution.application..",
                        "com.aicostops.attribution.domain..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "org.apache.ibatis..")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    void expenseDomainStaysFrameworkFree() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.expense.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.expense.domain..",
                        "com.aicostops.shared..",
                        "java..");

        rule.check(productionClasses);
    }

    @Test
    void expenseApplicationNeverReachesInfrastructureOrAllocation() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.expense.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        // Application services use the module's own mapper
                        // directly (Evidence-module style), never allocation or
                        // other modules' persistence.
                        "com.aicostops.expense..",
                        // Evidence storage/download services are the
                        // documented integration seams; the Evidence record
                        // and its storage status flow through them.
                        "com.aicostops.evidence.application..",
                        "com.aicostops.evidence.domain..",
                        "com.aicostops.iam.application..",
                        "com.aicostops.iam.domain..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        rule.check(productionClasses);
    }

    @Test
    void expenseInfrastructureDependsOnlyOnExpenseLayersAuditAndFramework() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.expense.infrastructure..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.expense.infrastructure..",
                        "com.aicostops.expense.application..",
                        "com.aicostops.expense.domain..",
                        "com.aicostops.audit.application..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "org.apache.ibatis..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        rule.check(productionClasses);
    }

    @Test
    void expenseApiDependsOnlyOnExpenseApplicationAndShared() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.expense.api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.expense.api..",
                        "com.aicostops.expense.application..",
                        "com.aicostops.expense.domain..",
                        // EvidenceDownload is the streaming download seam
                        // and carries the Evidence record.
                        "com.aicostops.evidence.application..",
                        "com.aicostops.evidence.domain..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..");

        rule.check(productionClasses);
    }

    @Test
    void allocationApplicationNeverReachesExpenseOrEvidence() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.allocation.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.allocation.application..",
                        "com.aicostops.allocation.infrastructure..",
                        "com.aicostops.attribution..",
                        // M3 charge read models carry the cost review status.
                        "com.aicostops.cost.domain..",
                        // The expense source port is the only allowed expense
                        // dependency (subject abstraction seam).
                        "com.aicostops.expense.application..",
                        "com.aicostops.iam..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        rule.check(productionClasses);
    }

    @Test
    void costDomainStaysFrameworkFree() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule costDomainRule = classes()
                .that().resideInAPackage("com.aicostops.cost.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.cost.domain..",
                        "com.aicostops.shared..",
                        "java..");

        costDomainRule.check(productionClasses);
    }

    @Test
    void costApplicationDependsOnlyOnDomainSharedAndJson() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule costApplicationRule = classes()
                .that().resideInAPackage("com.aicostops.cost.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.cost.application..",
                        "com.aicostops.cost.domain..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        costApplicationRule.check(productionClasses);
    }

    @Test
    void costInfrastructureDependsOnlyOnApplicationFrameworkAndMybatis() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule costInfrastructureRule = classes()
                .that().resideInAPackage("com.aicostops.cost.infrastructure..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.cost..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "org.apache.ibatis..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        costInfrastructureRule.check(productionClasses);
    }

    @Test
    void providerAdapterPackagesStayInsideParserBoundaries() {
        // Provider adapters may use the ingestion application/domain contracts and
        // parser libraries only. They must never reach MyBatis mappers, MinIO
        // implementation, organization mutation services, or canonical-cost modules.
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule providerRule = classes()
                .that().resideInAPackage("com.aicostops.ingestion.providers..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.ingestion.providers..",
                        "com.aicostops.ingestion.application..",
                        "com.aicostops.ingestion.domain..",
                        "java..",
                        "javax.xml..",
                        "org.xml.sax..",
                        "org.springframework..",
                        "org.apache.commons..",
                        "org.apache.poi..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        providerRule.check(productionClasses);
    }

    @Test
    void ledgerApplicationUsesPortsAndItsOwnPersistenceOnly() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.ledger.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.ledger..",
                        "com.aicostops.budget.application..",
                        "com.aicostops.budget.domain..",
                        "com.aicostops.allocation.application..",
                        "com.aicostops.attribution.domain..",
                        "com.aicostops.cost.application..",
                        "com.aicostops.cost.domain..",
                        "com.aicostops.expense.application..",
                        "com.aicostops.iam..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        rule.check(productionClasses);
    }

    @Test
    void ledgerDomainStaysFrameworkFree() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.ledger.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.ledger.domain..", "java..");

        rule.check(productionClasses);
    }

    @Test
    void reportingIsReadOnlyAndNeverTouchesBusinessModules() {
        // 01-module-boundaries.md: reporting may only read via its own MySQL
        // read queries plus Redis cache. It must not reach into any business
        // module (and therefore can never obtain a financial mutation
        // repository); authorization context comes from iam, errors from
        // shared.
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule reportingRule = classes()
                .that().resideInAPackage("com.aicostops.reporting..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.aicostops.reporting..",
                        "com.aicostops.iam..",
                        "com.aicostops.shared..",
                        "java..",
                        "jakarta..",
                        "org.springframework..",
                        "org.apache.ibatis..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..");

        reportingRule.check(productionClasses);
    }

    @Test
    void ledgerApplicationDoesNotReachForeignInfrastructure() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        ArchRule rule = classes()
                .that().resideInAPackage("com.aicostops.ledger.application..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "com.aicostops.allocation.infrastructure..",
                        "com.aicostops.budget.infrastructure..",
                        "com.aicostops.cost.infrastructure..",
                        "com.aicostops.expense.infrastructure..",
                        "com.aicostops.iam.infrastructure..");

        rule.check(productionClasses);
    }
}
