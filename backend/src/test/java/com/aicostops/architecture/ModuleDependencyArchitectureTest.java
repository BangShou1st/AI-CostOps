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
}
