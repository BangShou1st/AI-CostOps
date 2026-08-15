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
}
