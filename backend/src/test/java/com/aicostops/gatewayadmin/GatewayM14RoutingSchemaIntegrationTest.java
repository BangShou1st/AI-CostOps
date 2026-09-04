package com.aicostops.gatewayadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** M14 V22 routing administration schema contract on real MySQL. */
@SpringBootTest
@Tag("integration")
class GatewayM14RoutingSchemaIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void migrationCreatesRoutingTablesAndM14AttemptLineage() {
        assertThat(tables()).contains("routing_policy", "routing_policy_candidate");
        assertThat(columns("routing_policy"))
                .contains("project_scope_key", "active_scope_slot");
        assertThat(columns("gateway_route_attempt"))
                .contains("routing_policy_candidate_id", "routing_policy_version");
        assertThat(indexes("routing_policy"))
                .contains("uq_routing_policy_scope_version", "uq_routing_policy_active_scope");
    }

    private List<String> tables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                """, String.class);
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table);
    }

    private List<String> indexes(String table) {
        return jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table);
    }
}
