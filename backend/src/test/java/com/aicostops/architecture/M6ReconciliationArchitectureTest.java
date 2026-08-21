package com.aicostops.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class M6ReconciliationArchitectureTest {

    @Test
    void reconciliationApplicationDoesNotReachIntoForeignInfrastructure() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops");

        classes()
                .that().resideInAPackage("com.aicostops.reconciliation.application..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "com.aicostops.ingestion.infrastructure..",
                        "com.aicostops.cost.infrastructure..",
                        "com.aicostops.cost.review.infrastructure..",
                        "com.aicostops.expense.infrastructure..",
                        "com.aicostops.budget.infrastructure..",
                        "com.aicostops.ledger.infrastructure..")
                .check(productionClasses);
    }
}
