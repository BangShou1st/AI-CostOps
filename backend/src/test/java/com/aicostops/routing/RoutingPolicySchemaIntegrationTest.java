package com.aicostops.routing;

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

/** M14 V22 schema contract, including physical NULL-scope enforcement. */
@SpringBootTest
@Tag("integration")
class RoutingPolicySchemaIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void routingTablesAndRouteReasonArePresentAfterV22() {
        assertThat(queryTables()).contains("routing_policy", "routing_policy_candidate");
        assertThat(queryColumns("routing_policy"))
                .contains("project_scope_key", "active_slot");
        assertThat(queryColumns("gateway_route_attempt")).contains("route_reason_code");
        assertThat(queryConstraints("gateway_route_attempt"))
                .contains("fk_gateway_route_attempt_policy_org");
    }

    @Test
    void exactScopeVersionAndActiveIndexesAreNotNullableUniqueGaps() {
        assertThat(queryIndexes("routing_policy"))
                .contains("uq_routing_policy_scope_version", "uq_routing_policy_scope_active");
        assertThat(columnType("routing_policy", "project_scope_key")).isEqualTo("bigint");
        assertThat(columnType("routing_policy", "active_slot")).isEqualTo("tinyint");
    }

    private List<String> queryTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                """, String.class);
    }

    private List<String> queryColumns(String table) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table);
    }

    private List<String> queryIndexes(String table) {
        return jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table);
    }

    private List<String> queryConstraints(String table) {
        return jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, String.class, table, column);
    }
}
