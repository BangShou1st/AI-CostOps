package com.aicostops.reconciliation.infrastructure;

import com.aicostops.reconciliation.domain.PeriodCloseCheck;
import com.aicostops.reconciliation.domain.PeriodCloseRun;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PeriodCloseMapper {

    String RUN_COLUMNS = """
            pcr.id,pcr.org_id,pcr.billing_period_id,pcr.close_generation,pcr.attempt_no,
            pcr.status,pcr.reconciliation_run_id,pcr.started_by_member_id,pcr.started_at,
            pcr.finished_at,pcr.error_code,pcr.error_summary,pcr.created_at,pcr.updated_at
            """;

    String CHECK_COLUMNS = """
            pcc.id,pcc.org_id,pcc.period_close_run_id,pcc.blocker_code,pcc.result,
            pcc.item_count,pcc.summary_json,pcc.evaluated_at,pcc.created_at
            """;

    @Insert("""
            INSERT INTO period_close_run(
                org_id,billing_period_id,close_generation,attempt_no,status,reconciliation_run_id,
                started_by_member_id,started_at,finished_at,error_code,error_summary,created_at,updated_at)
            VALUES (#{organizationId},#{billingPeriodId},#{closeGeneration},#{attemptNo},#{status},
                #{reconciliationRunId},#{startedByMemberId},#{startedAt},NULL,NULL,NULL,
                #{createdAt},#{updatedAt})
            """)
    int insertRun(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("closeGeneration") long closeGeneration,
            @Param("attemptNo") int attemptNo,
            @Param("status") String status,
            @Param("reconciliationRunId") Long reconciliationRunId,
            @Param("startedByMemberId") long startedByMemberId,
            @Param("startedAt") Instant startedAt,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM period_close_run pcr
            WHERE pcr.org_id=#{organizationId} AND pcr.id=#{runId}
            """)
    PeriodCloseRun selectRunByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId);

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM period_close_run pcr
            WHERE pcr.org_id=#{organizationId} AND pcr.id=#{runId}
            FOR UPDATE
            """)
    PeriodCloseRun selectRunByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId);

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM period_close_run pcr
            WHERE pcr.org_id=#{organizationId} AND pcr.billing_period_id=#{billingPeriodId}
            ORDER BY pcr.close_generation DESC,pcr.attempt_no DESC,pcr.id DESC
            LIMIT 1
            """)
    PeriodCloseRun selectLatestRunForPeriod(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM period_close_run pcr
            WHERE pcr.org_id=#{organizationId}
              AND pcr.billing_period_id=#{billingPeriodId}
              AND pcr.close_generation=#{closeGeneration}
              AND pcr.status='CHECKING'
            ORDER BY pcr.attempt_no DESC,pcr.id DESC
            """)
    List<PeriodCloseRun> selectCheckingRunsForGeneration(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("closeGeneration") long closeGeneration);

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM period_close_run pcr
            WHERE pcr.org_id=#{organizationId}
              AND pcr.billing_period_id=#{billingPeriodId}
              AND pcr.close_generation=#{closeGeneration}
              AND pcr.status='CLOSED'
            ORDER BY pcr.attempt_no DESC,pcr.id DESC
            LIMIT 1
            """)
    PeriodCloseRun selectLatestSuccessfulRunForGeneration(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("closeGeneration") long closeGeneration);

    @Select("""
            SELECT COALESCE(MAX(attempt_no),0)+1
            FROM period_close_run
            WHERE org_id=#{organizationId}
              AND billing_period_id=#{billingPeriodId}
              AND close_generation=#{closeGeneration}
            """)
    int selectNextAttemptNo(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("closeGeneration") long closeGeneration);

    @Select("""
            SELECT
            """ + RUN_COLUMNS + """
            FROM period_close_run pcr
            WHERE pcr.org_id=#{organizationId} AND pcr.billing_period_id=#{billingPeriodId}
            ORDER BY pcr.close_generation DESC,pcr.attempt_no DESC,pcr.id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<PeriodCloseRun> selectRunsByPeriod(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*) FROM period_close_run
            WHERE org_id=#{organizationId} AND billing_period_id=#{billingPeriodId}
            """)
    long countRunsByPeriod(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);

    @Insert("""
            INSERT INTO period_close_check(
                org_id,period_close_run_id,blocker_code,result,item_count,summary_json,
                evaluated_at,created_at)
            VALUES (#{organizationId},#{closeRunId},#{blockerCode},#{result},#{itemCount},
                #{summaryJson},#{evaluatedAt},#{createdAt})
            """)
    int insertCheck(
            @Param("organizationId") long organizationId,
            @Param("closeRunId") long closeRunId,
            @Param("blockerCode") String blockerCode,
            @Param("result") String result,
            @Param("itemCount") long itemCount,
            @Param("summaryJson") String summaryJson,
            @Param("evaluatedAt") Instant evaluatedAt,
            @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT
            """ + CHECK_COLUMNS + """
            FROM period_close_check pcc
            WHERE pcc.org_id=#{organizationId} AND pcc.period_close_run_id=#{closeRunId}
            ORDER BY pcc.id ASC
            """)
    List<PeriodCloseCheck> selectChecksByRun(
            @Param("organizationId") long organizationId,
            @Param("closeRunId") long closeRunId);

    @Update("""
            UPDATE period_close_run
            SET status='BLOCKED',finished_at=#{finishedAt},updated_at=#{updatedAt}
            WHERE org_id=#{organizationId} AND id=#{runId} AND status='CHECKING'
            """)
    int markRunBlocked(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("finishedAt") Instant finishedAt,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE period_close_run
            SET status='FAILED',error_code=#{errorCode},error_summary=#{errorSummary},
                finished_at=#{finishedAt},updated_at=#{updatedAt}
            WHERE org_id=#{organizationId} AND id=#{runId} AND status='CHECKING'
            """)
    int markRunFailed(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary,
            @Param("finishedAt") Instant finishedAt,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE period_close_run
            SET status='CLOSED',reconciliation_run_id=#{reconciliationRunId},
                finished_at=#{finishedAt},updated_at=#{updatedAt}
            WHERE org_id=#{organizationId} AND id=#{runId} AND status='CHECKING'
            """)
    int markRunClosed(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("reconciliationRunId") long reconciliationRunId,
            @Param("finishedAt") Instant finishedAt,
            @Param("updatedAt") Instant updatedAt);
}
