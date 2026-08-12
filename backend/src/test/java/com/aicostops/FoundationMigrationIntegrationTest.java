package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class FoundationMigrationIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesAnEmptyMySqlDatabaseToTheFoundationBaseline() {
        var successfulBaselineMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class);

        assertThat(successfulBaselineMigrations).isEqualTo(1);
    }
}
