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
}
