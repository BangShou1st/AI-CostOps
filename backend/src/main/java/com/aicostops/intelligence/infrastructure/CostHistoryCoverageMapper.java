package com.aicostops.intelligence.infrastructure;

import com.aicostops.intelligence.domain.CostHistoryCoverage.LedgerStamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** All review and ledger queries are tenant/currency bound; scope names never become SQL identifiers. */
@Mapper
public interface CostHistoryCoverageMapper {
    // Same effective roots and feedback protection as CostFactsMapper; only parameter names differ.
    String COST_WINDOW = """
            le.org_id=#{org} AND le.currency=#{currency} AND lp.status='POSTED'
              AND lp.posted_at >= #{start} AND lp.posted_at &lt; #{end}
              AND (le.source_gateway_settlement_id IS NOT NULL
                OR le.source_reconciliation_adjustment_id IS NOT NULL OR le.source_charge_fact_id IS NOT NULL)
              AND (si.id IS NULL OR si.code != 'AICOSTOPS_ADVISOR')
              AND (si2.id IS NULL OR si2.code != 'AICOSTOPS_ADVISOR')
            """;
    @Insert("INSERT IGNORE INTO cost_history_coverage_revision(org_id,currency,revision) VALUES(#{org},#{currency},0)")
    int ensureRevision(@Param("org") long org, @Param("currency") String currency);

    @Select("SELECT revision FROM cost_history_coverage_revision WHERE org_id=#{org} AND currency=#{currency} FOR UPDATE")
    long lockRevision(@Param("org") long org, @Param("currency") String currency);

    @Select("SELECT COALESCE((SELECT revision FROM cost_history_coverage_revision WHERE org_id=#{org} AND currency=#{currency}),0)")
    long revision(@Param("org") long org, @Param("currency") String currency);

    @Update("UPDATE cost_history_coverage_revision SET revision=revision+1 WHERE org_id=#{org} AND currency=#{currency}")
    int advanceRevision(@Param("org") long org, @Param("currency") String currency);

    @Select("""
            <script>
            SELECT COUNT(*) FROM
            <choose>
              <when test="scope == 'PROJECT'">project</when>
              <when test="scope == 'TEAM'">team</when>
              <when test="scope == 'COST_CENTER'">cost_center</when>
              <otherwise>organization</otherwise>
            </choose>
            WHERE id=#{scopeId}
            <choose>
              <when test="scope == 'ORGANIZATION'">AND id=#{org}</when>
              <otherwise>AND org_id=#{org}</otherwise>
            </choose>
            </script>
            """)
    int ownedScope(@Param("org") long org, @Param("scope") String scope, @Param("scopeId") long scopeId);

    @Select("""
            SELECT scope,scopeId,name FROM (
              SELECT 'ORGANIZATION' AS scope,CAST(id AS CHAR) AS scopeId,name FROM organization WHERE id=#{org}
              UNION ALL SELECT 'PROJECT',CAST(id AS CHAR),name FROM project WHERE org_id=#{org}
              UNION ALL SELECT 'TEAM',CAST(id AS CHAR),name FROM team WHERE org_id=#{org}
              UNION ALL SELECT 'COST_CENTER',CAST(id AS CHAR),name FROM cost_center WHERE org_id=#{org}
            ) choices WHERE name LIKE CONCAT('%',#{search},'%') ORDER BY scope,name,scopeId LIMIT 50
            """)
    List<ScopeOption> scopes(@Param("org") long org, @Param("search") String search);
    record ScopeOption(String scope, String scopeId, String name) {}

    @Select("""
            <script>
            SELECT COUNT(*) AS entryCount,COALESCE(MAX(le.id),0) AS maximumEntryId
            """ + CostFactsMapper.LEDGER_JOINS + " WHERE " + COST_WINDOW + """
            <choose>
              <when test="scope == 'PROJECT'">AND le.project_id=#{scopeId}</when>
              <when test="scope == 'TEAM'">AND le.team_id=#{scopeId}</when>
              <when test="scope == 'COST_CENTER'">AND le.cost_center_id=#{scopeId}</when>
            </choose>
            </script>
            """)
    LedgerStamp ledgerStamp(@Param("org") long org, @Param("currency") String currency,
            @Param("scope") String scope, @Param("scopeId") long scopeId,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    String COLUMNS = "id,scope_type,scope_id,posting_day,status,basis,review_note,ledger_entry_count,"
            + "ledger_max_entry_id,reviewed_by_user_id,updated_at";

    @Select("SELECT " + COLUMNS + " FROM cost_history_coverage WHERE org_id=#{org} AND currency=#{currency} "
            + "AND scope_type=#{scope} AND scope_id=#{scopeId} AND posting_day=#{day}")
    CoverageRow find(@Param("org") long org, @Param("currency") String currency,
            @Param("scope") String scope, @Param("scopeId") long scopeId, @Param("day") LocalDate day);

    @Select("""
            <script>
            SELECT c.scope_type,c.scope_id,c.posting_day FROM cost_history_coverage c
            LEFT JOIN (
              SELECT le.id,le.project_id,le.team_id,le.cost_center_id,DATE(lp.posted_at) AS posting_day
            """ + CostFactsMapper.LEDGER_JOINS + " WHERE " + COST_WINDOW + """
            ) d ON d.posting_day=c.posting_day AND (
              c.scope_type='ORGANIZATION' OR (c.scope_type='PROJECT' AND d.project_id=c.scope_id)
              OR (c.scope_type='TEAM' AND d.team_id=c.scope_id)
              OR (c.scope_type='COST_CENTER' AND d.cost_center_id=c.scope_id))
            WHERE c.org_id=#{org} AND c.currency=#{currency} AND c.status='REVIEWED_COMPLETE'
              AND c.posting_day >= #{start} AND c.posting_day &lt; #{end}
            GROUP BY c.id
            HAVING COUNT(d.id)=MAX(c.ledger_entry_count) AND COALESCE(MAX(d.id),0)=MAX(c.ledger_max_entry_id)
            </script>
            """)
    List<CoveredDay> validDays(@Param("org") long org, @Param("currency") String currency,
            @Param("start") LocalDate start, @Param("end") LocalDate end);
    record CoveredDay(String scopeType, long scopeId, LocalDate postingDay) {}

    @Select("SELECT " + COLUMNS + " FROM cost_history_coverage WHERE org_id=#{org} AND currency=#{currency} "
            + "AND scope_type=#{scope} AND scope_id=#{scopeId} AND posting_day >= #{start} "
            + "AND posting_day < #{end} ORDER BY posting_day")
    List<CoverageRow> listScope(@Param("org") long org, @Param("currency") String currency,
            @Param("scope") String scope, @Param("scopeId") long scopeId,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Insert("""
            INSERT INTO cost_history_coverage(org_id,currency,scope_type,scope_id,posting_day,status,basis,
              review_note,ledger_entry_count,ledger_max_entry_id,reviewed_by_user_id,updated_at)
            VALUES(#{org},#{currency},#{scope},#{scopeId},#{day},#{status},'MANUAL_LEDGER_REVIEW',
              #{note},#{stamp.entryCount},#{stamp.maximumEntryId},#{actor},#{now})
            ON DUPLICATE KEY UPDATE status=#{status},review_note=#{note},
              ledger_entry_count=#{stamp.entryCount},ledger_max_entry_id=#{stamp.maximumEntryId},
              reviewed_by_user_id=#{actor},updated_at=#{now}
            """)
    int save(@Param("org") long org, @Param("currency") String currency, @Param("scope") String scope,
            @Param("scopeId") long scopeId, @Param("day") LocalDate day, @Param("status") String status,
            @Param("note") String note, @Param("stamp") LedgerStamp stamp,
            @Param("actor") long actor, @Param("now") Instant now);

    record CoverageRow(long id, String scopeType, long scopeId, LocalDate postingDay, String status,
            String basis, String reviewNote, long ledgerEntryCount, long ledgerMaxEntryId,
            long reviewedByUserId, Instant updatedAt) {}
}
