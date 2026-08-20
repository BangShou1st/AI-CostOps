package com.aicostops.reconciliation.infrastructure;

import com.aicostops.reconciliation.domain.ReconciliationCase;
import com.aicostops.reconciliation.domain.ReconciliationRun;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReconciliationMapper {

    String RUN_COLUMNS = """
            rr.id,rr.org_id,rr.billing_period_id,rr.status,rr.algorithm_version,
            rr.tolerance_amount,rr.basis_hash,rr.summary_json,rr.created_by_member_id,
            rr.started_at,rr.finished_at,rr.error_code,rr.error_summary,rr.created_at,rr.updated_at
            """;

    String CASE_COLUMNS = """
            rc.id,rc.org_id,rc.reconciliation_run_id,rc.provider_account_id,rc.currency,
            rc.case_type,rc.external_amount,rc.internal_amount,rc.difference_amount,
            rc.external_row_count,rc.internal_row_count,rc.status,rc.reason_code,
            rc.resolution_note,rc.resolved_by_member_id,rc.resolved_at,rc.created_at,rc.updated_at
            """;

    @Insert("""
            INSERT INTO reconciliation_run(
                org_id,billing_period_id,status,algorithm_version,tolerance_amount,basis_hash,
                summary_json,created_by_member_id,started_at,finished_at,error_code,error_summary,
                created_at,updated_at)
            VALUES (#{organizationId},#{billingPeriodId},#{status},#{algorithmVersion},
                #{toleranceAmount},NULL,#{summaryJson},#{createdByMemberId},#{startedAt},
                NULL,NULL,NULL,#{createdAt},#{updatedAt})
            """)
    int insertRun(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("status") String status,
            @Param("algorithmVersion") String algorithmVersion,
            @Param("toleranceAmount") BigDecimal toleranceAmount,
            @Param("summaryJson") String summaryJson,
            @Param("createdByMemberId") long createdByMemberId,
            @Param("startedAt") Instant startedAt,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM reconciliation_run rr
            WHERE rr.org_id=#{organizationId} AND rr.id=#{runId}
            """)
    ReconciliationRun selectRunByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId);

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM reconciliation_run rr
            WHERE rr.org_id=#{organizationId} AND rr.id=#{runId}
            FOR UPDATE
            """)
    ReconciliationRun selectRunByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId);

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM reconciliation_run rr
            WHERE rr.org_id=#{organizationId} AND rr.billing_period_id=#{billingPeriodId}
            ORDER BY rr.started_at DESC,rr.id DESC
            LIMIT 1
            """)
    ReconciliationRun selectLatestRunForPeriod(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM reconciliation_run rr
            WHERE rr.org_id=#{organizationId} AND rr.billing_period_id=#{billingPeriodId}
            ORDER BY rr.started_at DESC,rr.id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<ReconciliationRun> selectRunsByPeriod(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*) FROM reconciliation_run
            WHERE org_id=#{organizationId} AND billing_period_id=#{billingPeriodId}
            """)
    long countRunsByPeriod(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);

    @Update("""
            UPDATE reconciliation_run
            SET status='COMPLETED',basis_hash=#{basisHash},summary_json=#{summaryJson},
                finished_at=#{finishedAt},updated_at=#{updatedAt}
            WHERE org_id=#{organizationId} AND id=#{runId} AND status='RUNNING'
            """)
    int markRunCompleted(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("basisHash") String basisHash,
            @Param("summaryJson") String summaryJson,
            @Param("finishedAt") Instant finishedAt,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE reconciliation_run
            SET status='FAILED',error_code=#{errorCode},error_summary=#{errorSummary},
                finished_at=#{finishedAt},updated_at=#{updatedAt}
            WHERE org_id=#{organizationId} AND id=#{runId} AND status='RUNNING'
            """)
    int markRunFailed(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary,
            @Param("finishedAt") Instant finishedAt,
            @Param("updatedAt") Instant updatedAt);

    @Insert("""
            INSERT INTO reconciliation_case(
                org_id,reconciliation_run_id,provider_account_id,currency,case_type,
                external_amount,internal_amount,difference_amount,external_row_count,
                internal_row_count,status,created_at,updated_at)
            VALUES (#{organizationId},#{runId},#{providerAccountId},#{currency},#{caseType},
                #{externalAmount},#{internalAmount},#{differenceAmount},#{externalRowCount},
                #{internalRowCount},'OPEN',#{createdAt},#{updatedAt})
            """)
    int insertCase(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("providerAccountId") long providerAccountId,
            @Param("currency") String currency,
            @Param("caseType") String caseType,
            @Param("externalAmount") BigDecimal externalAmount,
            @Param("internalAmount") BigDecimal internalAmount,
            @Param("differenceAmount") BigDecimal differenceAmount,
            @Param("externalRowCount") long externalRowCount,
            @Param("internalRowCount") long internalRowCount,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);

    @Select("""
            SELECT
            """ + CASE_COLUMNS + """
            FROM reconciliation_case rc
            WHERE rc.org_id=#{organizationId} AND rc.id=#{caseId}
            """)
    ReconciliationCase selectCaseByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("caseId") long caseId);

    @Select("""
            SELECT
            """ + CASE_COLUMNS + """
            FROM reconciliation_case rc
            WHERE rc.org_id=#{organizationId} AND rc.id=#{caseId}
            FOR UPDATE
            """)
    ReconciliationCase selectCaseByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("caseId") long caseId);

    @Select("""
            <script>
            SELECT
            """ + CASE_COLUMNS + """
            FROM reconciliation_case rc
            WHERE rc.org_id=#{organizationId} AND rc.reconciliation_run_id=#{runId}
            <if test='status != null'>AND rc.status=#{status}</if>
            ORDER BY rc.provider_account_id,rc.currency,rc.id
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<ReconciliationCase> selectCasesByRun(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("status") String status,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM reconciliation_case
            WHERE org_id=#{organizationId} AND reconciliation_run_id=#{runId}
            <if test='status != null'>AND status=#{status}</if>
            </script>
            """)
    long countCasesByRun(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("status") String status);

    @Select("""
            SELECT COUNT(*) FROM reconciliation_case
            WHERE org_id=#{organizationId} AND reconciliation_run_id=#{runId}
              AND status <> 'RESOLVED'
            """)
    long countUnresolvedCases(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId);

    @Update("""
            UPDATE reconciliation_case
            SET status='INVESTIGATING',updated_at=#{updatedAt}
            WHERE org_id=#{organizationId} AND id=#{caseId} AND status='OPEN'
            """)
    int markInvestigating(
            @Param("organizationId") long organizationId,
            @Param("caseId") long caseId,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE reconciliation_case
            SET status='OPEN',updated_at=#{updatedAt}
            WHERE org_id=#{organizationId} AND id=#{caseId} AND status='INVESTIGATING'
            """)
    int returnInvestigatingToOpen(
            @Param("organizationId") long organizationId,
            @Param("caseId") long caseId,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE reconciliation_case
            SET status='RESOLVED',reason_code=#{reasonCode},resolution_note=#{resolutionNote},
                resolved_by_member_id=#{resolvedByMemberId},resolved_at=#{resolvedAt},
                updated_at=#{updatedAt}
            WHERE org_id=#{organizationId} AND id=#{caseId} AND status='INVESTIGATING'
            """)
    int markResolved(
            @Param("organizationId") long organizationId,
            @Param("caseId") long caseId,
            @Param("reasonCode") String reasonCode,
            @Param("resolutionNote") String resolutionNote,
            @Param("resolvedByMemberId") long resolvedByMemberId,
            @Param("resolvedAt") Instant resolvedAt,
            @Param("updatedAt") Instant updatedAt);
}
