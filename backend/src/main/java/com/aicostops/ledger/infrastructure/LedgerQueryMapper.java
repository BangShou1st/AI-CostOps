package com.aicostops.ledger.infrastructure;

import com.aicostops.ledger.application.LedgerReadModels.LedgerLineage;
import com.aicostops.ledger.domain.LedgerEntry;
import com.aicostops.ledger.domain.LedgerPosting;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only Ledger projections, including source-lineage joins. */
@Mapper
public interface LedgerQueryMapper {

    String POSTING_COLUMNS = LedgerPostingMapper.POSTING_COLUMNS;
    String ENTRY_COLUMNS = LedgerPostingMapper.ENTRY_COLUMNS;

    record VisibilityScope(String scopeType, long scopeId) {
    }

    @Select("""
            <script>
            SELECT
            """ + POSTING_COLUMNS + """
            FROM ledger_posting lp
            WHERE lp.org_id=#{organizationId}
              AND EXISTS (
                SELECT 1 FROM ledger_entry le
                WHERE le.org_id=lp.org_id AND le.posting_id=lp.id
                <if test="!organizationWide">
                  AND (
                    <foreach collection="visibleScopes" item="scope" separator=" OR ">
                      (le.project_id=#{scope.scopeId} AND #{scope.scopeType}='PROJECT')
                      OR (le.team_id=#{scope.scopeId} AND #{scope.scopeType}='TEAM')
                      OR (le.cost_center_id=#{scope.scopeId} AND #{scope.scopeType}='COST_CENTER')
                    </foreach>
                  )
                </if>
                <if test="billingPeriodId != null">
                  AND lp.billing_period_id=#{billingPeriodId}
                </if>
                <if test="sourceType != null">
                  AND lp.source_type=#{sourceType}
                </if>
                <if test="projectId != null">AND le.project_id=#{projectId}</if>
                <if test="costCenterId != null">AND le.cost_center_id=#{costCenterId}</if>
                <if test="teamId != null">AND le.team_id=#{teamId}</if>
              )
            ORDER BY ${sortColumn} ${sortDirection}, lp.id ${sortDirection}
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<LedgerPosting> selectPostingPage(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") Long billingPeriodId,
            @Param("sourceType") String sourceType,
            @Param("projectId") Long projectId,
            @Param("costCenterId") Long costCenterId,
            @Param("teamId") Long teamId,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<VisibilityScope> visibleScopes,
            @Param("sortColumn") String sortColumn,
            @Param("sortDirection") String sortDirection,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ledger_posting lp
            WHERE lp.org_id=#{organizationId}
              AND EXISTS (
                SELECT 1 FROM ledger_entry le
                WHERE le.org_id=lp.org_id AND le.posting_id=lp.id
                <if test="!organizationWide">
                  AND (
                    <foreach collection="visibleScopes" item="scope" separator=" OR ">
                      (le.project_id=#{scope.scopeId} AND #{scope.scopeType}='PROJECT')
                      OR (le.team_id=#{scope.scopeId} AND #{scope.scopeType}='TEAM')
                      OR (le.cost_center_id=#{scope.scopeId} AND #{scope.scopeType}='COST_CENTER')
                    </foreach>
                  )
                </if>
                <if test="billingPeriodId != null">AND lp.billing_period_id=#{billingPeriodId}</if>
                <if test="sourceType != null">AND lp.source_type=#{sourceType}</if>
                <if test="projectId != null">AND le.project_id=#{projectId}</if>
                <if test="costCenterId != null">AND le.cost_center_id=#{costCenterId}</if>
                <if test="teamId != null">AND le.team_id=#{teamId}</if>
              )
            </script>
            """)
    long countPostings(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") Long billingPeriodId,
            @Param("sourceType") String sourceType,
            @Param("projectId") Long projectId,
            @Param("costCenterId") Long costCenterId,
            @Param("teamId") Long teamId,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<VisibilityScope> visibleScopes);

    @Select("""
            <script>
            SELECT
            """ + POSTING_COLUMNS + """
            FROM ledger_posting lp
            WHERE lp.org_id=#{organizationId} AND lp.id=#{postingId}
              AND EXISTS (
                SELECT 1 FROM ledger_entry le
                WHERE le.org_id=lp.org_id AND le.posting_id=lp.id
                <if test="!organizationWide">
                  AND (
                    <foreach collection="visibleScopes" item="scope" separator=" OR ">
                      (le.project_id=#{scope.scopeId} AND #{scope.scopeType}='PROJECT')
                      OR (le.team_id=#{scope.scopeId} AND #{scope.scopeType}='TEAM')
                      OR (le.cost_center_id=#{scope.scopeId} AND #{scope.scopeType}='COST_CENTER')
                    </foreach>
                  )
                </if>
              )
            </script>
            """)
    LedgerPosting selectPostingVisible(
            @Param("organizationId") long organizationId,
            @Param("postingId") long postingId,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<VisibilityScope> visibleScopes);

    @Select("""
            <script>
            SELECT
            """ + ENTRY_COLUMNS + """
            FROM ledger_entry le
            JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
            WHERE le.org_id=#{organizationId} AND le.posting_id=#{postingId}
            <if test="!organizationWide">
              AND (
                <foreach collection="visibleScopes" item="scope" separator=" OR ">
                  (le.project_id=#{scope.scopeId} AND #{scope.scopeType}='PROJECT')
                  OR (le.team_id=#{scope.scopeId} AND #{scope.scopeType}='TEAM')
                  OR (le.cost_center_id=#{scope.scopeId} AND #{scope.scopeType}='COST_CENTER')
                </foreach>
              )
            </if>
            <if test="projectId != null">AND le.project_id=#{projectId}</if>
            <if test="costCenterId != null">AND le.cost_center_id=#{costCenterId}</if>
            <if test="teamId != null">AND le.team_id=#{teamId}</if>
            ORDER BY le.entry_index ASC, le.id ASC
            </script>
            """)
    List<LedgerEntry> selectEntriesForPosting(
            @Param("organizationId") long organizationId,
            @Param("postingId") long postingId,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<VisibilityScope> visibleScopes,
            @Param("projectId") Long projectId,
            @Param("costCenterId") Long costCenterId,
            @Param("teamId") Long teamId);

    @Select("""
            <script>
            SELECT
            """ + ENTRY_COLUMNS + """
            FROM ledger_entry le
            JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
            WHERE le.org_id=#{organizationId}
            <if test="!organizationWide">
              AND (
                <foreach collection="visibleScopes" item="scope" separator=" OR ">
                  (le.project_id=#{scope.scopeId} AND #{scope.scopeType}='PROJECT')
                  OR (le.team_id=#{scope.scopeId} AND #{scope.scopeType}='TEAM')
                  OR (le.cost_center_id=#{scope.scopeId} AND #{scope.scopeType}='COST_CENTER')
                </foreach>
              )
            </if>
            <if test="billingPeriodId != null">AND lp.billing_period_id=#{billingPeriodId}</if>
            <if test="sourceType != null">AND lp.source_type=#{sourceType}</if>
            <if test="projectId != null">AND le.project_id=#{projectId}</if>
            <if test="costCenterId != null">AND le.cost_center_id=#{costCenterId}</if>
            <if test="teamId != null">AND le.team_id=#{teamId}</if>
            ORDER BY ${sortColumn} ${sortDirection}, le.id ${sortDirection}
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<LedgerEntry> selectEntryPage(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") Long billingPeriodId,
            @Param("sourceType") String sourceType,
            @Param("projectId") Long projectId,
            @Param("costCenterId") Long costCenterId,
            @Param("teamId") Long teamId,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<VisibilityScope> visibleScopes,
            @Param("sortColumn") String sortColumn,
            @Param("sortDirection") String sortDirection,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ledger_entry le
            JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
            WHERE le.org_id=#{organizationId}
            <if test="!organizationWide">
              AND (
                <foreach collection="visibleScopes" item="scope" separator=" OR ">
                  (le.project_id=#{scope.scopeId} AND #{scope.scopeType}='PROJECT')
                  OR (le.team_id=#{scope.scopeId} AND #{scope.scopeType}='TEAM')
                  OR (le.cost_center_id=#{scope.scopeId} AND #{scope.scopeType}='COST_CENTER')
                </foreach>
              )
            </if>
            <if test="billingPeriodId != null">AND lp.billing_period_id=#{billingPeriodId}</if>
            <if test="sourceType != null">AND lp.source_type=#{sourceType}</if>
            <if test="projectId != null">AND le.project_id=#{projectId}</if>
            <if test="costCenterId != null">AND le.cost_center_id=#{costCenterId}</if>
            <if test="teamId != null">AND le.team_id=#{teamId}</if>
            </script>
            """)
    long countEntries(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") Long billingPeriodId,
            @Param("sourceType") String sourceType,
            @Param("projectId") Long projectId,
            @Param("costCenterId") Long costCenterId,
            @Param("teamId") Long teamId,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<VisibilityScope> visibleScopes);

    @Select("""
        SELECT
          ad.id AS allocation_line_id,
              ad.decision_id AS allocation_decision_id,
              adec.status AS allocation_decision_status,
              cf.id AS charge_fact_id,
              cf.provider_code AS charge_provider_code,
              cf.review_status AS charge_review_status,
              rr.id AS raw_provider_record_id,
              ia.id AS import_attempt_id,
              ib.id AS import_batch_id,
              provider_ev.id AS provider_evidence_id,
              ec.id AS expense_claim_id,
              ec.status AS expense_status,
              expense_ev.id AS expense_evidence_id,
              le.correction_group_id AS correction_group_id,
              le.reverses_entry_id AS reverses_entry_id,
              corrected_by.id AS corrected_by_correction_group_id,
              created_by.target_entry_id AS correction_target_entry_id
            FROM ledger_entry le
            LEFT JOIN allocation_line ad
              ON ad.id=le.allocation_line_id AND ad.org_id=le.org_id
            LEFT JOIN allocation_decision adec
              ON adec.id=ad.decision_id AND adec.org_id=ad.org_id
            LEFT JOIN charge_fact cf
              ON cf.id=le.source_charge_fact_id AND cf.org_id=le.org_id
            LEFT JOIN raw_provider_record rr ON rr.id=cf.raw_record_id
            LEFT JOIN import_attempt ia ON ia.id=rr.import_attempt_id
            LEFT JOIN import_batch ib ON ib.id=ia.import_batch_id
            LEFT JOIN evidence provider_ev ON provider_ev.id=ib.evidence_id
            LEFT JOIN expense_claim ec
              ON ec.id=le.source_expense_claim_id AND ec.org_id=le.org_id
            LEFT JOIN evidence expense_ev ON expense_ev.id=ec.evidence_id
            LEFT JOIN correction_group corrected_by
              ON corrected_by.target_entry_id=le.id AND corrected_by.org_id=le.org_id
            LEFT JOIN correction_group created_by
              ON created_by.id=le.correction_group_id AND created_by.org_id=le.org_id
            WHERE le.org_id=#{organizationId} AND le.id=#{entryId}
            """)
    LedgerLineage selectLineage(
            @Param("organizationId") long organizationId,
            @Param("entryId") long entryId);

    @Select("""
            SELECT
            """ + ENTRY_COLUMNS + """
            FROM ledger_entry le
            WHERE le.org_id=#{organizationId} AND le.id=#{entryId}
            """)
    LedgerEntry selectEntryById(
            @Param("organizationId") long organizationId,
            @Param("entryId") long entryId);
}
