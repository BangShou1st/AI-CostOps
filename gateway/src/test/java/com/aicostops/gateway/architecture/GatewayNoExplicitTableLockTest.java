package com.aicostops.gateway.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * M16 least-privilege guard: Gateway production code must never issue an
 * explicit {@code LOCK TABLES} statement. The runtime identity holds
 * database-level LOCK TABLES only as the MySQL-mandated prerequisite for
 * row-level locking reads (SELECT ... FOR UPDATE); an explicit LOCK TABLES
 * statement would escalate to table-level locks outside the mapper-owned
 * row-lock discipline. Forbidden financial writes are denied by MySQL
 * grants, proven by scripts/m16/verify-m16-gateway-privileges.ps1.
 */
class GatewayNoExplicitTableLockTest {

    @Test
    void productionCodeContainsNoExplicitLockTablesStatement() throws Exception {
        var root = findMainRoot();
        List<String> violations;
        try (Stream<Path> files = Files.walk(root)) {
            violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsLockTablesStatement(path))
                    .map(path -> root.relativize(path).toString())
                    .sorted()
                    .toList();
        }
        assertThat(violations).as("explicit LOCK TABLES statements in Gateway production code").isEmpty();
    }

    private static boolean containsLockTablesStatement(Path path) {
        try {
            var content = Files.readString(path, StandardCharsets.UTF_8);
            return content.toUpperCase().contains("LOCK TABLES");
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read " + path, ex);
        }
    }

    private static Path findMainRoot() {
        var current = Path.of("").toAbsolutePath();
        while (current != null) {
            var candidate = current.resolve("src/main/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate src/main/java from working directory");
    }
}
