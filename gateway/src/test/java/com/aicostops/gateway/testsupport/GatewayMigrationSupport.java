package com.aicostops.gateway.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Discovers the Backend-owned Flyway scripts without initializing test containers. */
final class GatewayMigrationSupport {

    private static final Path MIGRATION_ROOT = Path.of(
            "..", "backend", "src", "main", "resources", "db", "migration");
    private static final Pattern MIGRATION_NAME =
            Pattern.compile("^V(\\d+)__[A-Za-z0-9_]+\\.sql$");

    private GatewayMigrationSupport() {
    }

    static List<Path> migrationPaths() {
        try (var paths = Files.list(MIGRATION_ROOT)) {
            var migrations = paths
                    .filter(GatewayMigrationSupport::isMigration)
                    .sorted((left, right) -> Integer.compare(
                            migrationVersion(left), migrationVersion(right)))
                    .toList();
            if (migrations.isEmpty()) {
                throw new IllegalStateException("No Backend migrations were found under " + MIGRATION_ROOT);
            }
            return migrations;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not enumerate Backend migrations under " + MIGRATION_ROOT, exception);
        }
    }

    static boolean isMigration(Path path) {
        return MIGRATION_NAME.matcher(path.getFileName().toString()).matches();
    }

    static int migrationVersion(Path path) {
        var matcher = MIGRATION_NAME.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a Flyway migration: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
