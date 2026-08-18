package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.MySqlContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Canonical AIC-041 migration contract. This test intentionally lands before
 * V11 so the first CI run proves the new migration is actually required.
 */
@SpringBootTest
@Tag("integration")
class V11MigrationIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migratesAllVersionsThroughV11() {
        var successfulVersions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1", String.class);

        assertThat(successfulVersions).contains("11");
        assertThat(successfulVersions)
                .contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }
}
