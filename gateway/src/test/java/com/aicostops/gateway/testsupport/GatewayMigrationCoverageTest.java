package com.aicostops.gateway.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayMigrationCoverageTest {

    @Test
    void appliesEveryBackendMigrationInNumericOrder() throws Exception {
        var root = Path.of("..", "backend", "src", "main", "resources", "db", "migration");
        final List<Path> expected;
        try (var paths = Files.list(root)) {
            expected = paths
                    .filter(GatewayMigrationSupport::isMigration)
                    .sorted(Comparator.comparingInt(GatewayMigrationSupport::migrationVersion))
                    .toList();
        }

        assertThat(expected).isNotEmpty();
        assertThat(GatewayMigrationSupport.migrationPaths())
                .containsExactlyElementsOf(expected);
    }
}
