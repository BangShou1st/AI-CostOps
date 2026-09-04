package com.aicostops.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** M13-B module, persistence, and secret-boundary guards. */
@Tag("architecture")
class GatewaySettlementArchitectureTest {

    @Test
    void settlementNeverDependsOnGatewayRuntimeProvidersOrRedis() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aicostops.gatewaysettlement");

        classes().that().resideInAPackage("com.aicostops.gatewaysettlement..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "com.aicostops.gatewayadmin..",
                        "com.aicostops.ingestion.providers..",
                        "org.springframework.data.redis..",
                        "org.springframework.web.client..",
                        "org.springframework.web.reactive.function.client..")
                .check(productionClasses);
    }

    @Test
    void settlementProductionSourceHasNoSensitivePayloadOrCredentialPersistencePath()
            throws IOException {
        var sourceRoot = Path.of("src", "main", "java", "com", "aicostops",
                "gatewaysettlement");
        var source = readAll(sourceRoot);

        assertThat(source).doesNotContain("prompt", "completion", "reasoning",
                "providerrawbody", "provider_body", "api_key", "secret_digest",
                "decrypt", "redistemplate", "status='processing'", "status = 'processing'",
                "worker_lease", "claimed_by");
    }

    private static String readAll(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .map(GatewaySettlementArchitectureTest::read)
                    .reduce("", String::concat);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path).toLowerCase(java.util.Locale.ROOT);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read architecture source " + path, exception);
        }
    }
}
