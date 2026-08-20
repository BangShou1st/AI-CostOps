package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Source-level gate that Ledger persistence exposes append-only writes only. */
@Tag("architecture")
class LedgerImmutabilityArchitectureTest {

    @Test
    void ledgerPersistenceHasNoDestructiveSqlPath() throws IOException {
        var sourceRoot = Path.of("src", "main", "java", "com", "aicostops", "ledger");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            var source = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().contains("Idempotency"))
                    .map(this::read)
                    .reduce("", String::concat);
            assertThat(source).doesNotContain("@Update");
            assertThat(source).doesNotContain("@Delete");
            assertThat(source.toUpperCase()).doesNotContain("UPDATE LEDGER_");
            assertThat(source.toUpperCase()).doesNotContain("DELETE FROM LEDGER_");
            assertThat(source.toUpperCase()).doesNotContain("DELETE FROM CORRECTION_GROUP");
        }
    }

    @Test
    void postingMapperUsesInsertAndSelectOnly() throws IOException {
        var mapper = Files.readString(Path.of("src", "main", "java", "com", "aicostops",
                "ledger", "infrastructure", "LedgerPostingMapper.java"));
        assertThat(mapper).contains("@Insert").contains("@Select");
        assertThat(mapper).doesNotContain("@Update").doesNotContain("@Delete");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read architecture source " + path, exception);
        }
    }
}
