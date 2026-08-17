package com.aicostops.expense.infrastructure;

import com.aicostops.expense.domain.ApprovalAction;
import com.aicostops.expense.domain.ApprovalCase;
import com.aicostops.expense.domain.ExpenseClaim;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Row access for {@code expense_claim}, {@code approval_case}, {@code approval_action}. */
@Mapper
public interface ExpenseClaimMapper {

    String CLAIM_COLUMNS = """
            ec.id,ec.org_id,ec.claimant_member_id,ec.evidence_id,ec.expense_date,ec.amount,ec.currency,
            ec.status,ec.current_allocation_decision_id,ec.approval_case_id,ec.version,ec.created_at,ec.updated_at
            """;

    String CASE_COLUMNS = """
            ac.id,ac.org_id,ac.expense_claim_id,ac.status,ac.created_at,ac.updated_at
            """;

    @Insert("""
            INSERT INTO expense_claim(
                org_id,claimant_member_id,evidence_id,expense_date,amount,currency,status,
                current_allocation_decision_id,approval_case_id,version,created_at,updated_at)
            VALUES (#{organizationId},#{claimantMemberId},NULL,#{expenseDate},#{amount},#{currency},
                    'DRAFT',NULL,NULL,0,#{now},#{now})
            """)
    int insertClaim(
            @Param("organizationId") long organizationId,
            @Param("claimantMemberId") long claimantMemberId,
            @Param("expenseDate") LocalDate expenseDate,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT
            """ + CLAIM_COLUMNS + """
            FROM expense_claim ec
            WHERE ec.org_id=#{organizationId} AND ec.id=#{expenseId}
            """)
    ExpenseClaim selectByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId);

    @Select("""
            SELECT
            """ + CLAIM_COLUMNS + """
            FROM expense_claim ec
            WHERE ec.org_id=#{organizationId} AND ec.id=#{expenseId}
            FOR UPDATE
            """)
    ExpenseClaim selectByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId);

    /**
     * Optimistic version CAS for owner body edits: only a DRAFT/NEEDS_INFO
     * expense at the expected version is replaced. Evidence is never touched
     * by body edits (attachEvidence owns that column). Affects exactly one row
     * on success; any other result means stale version or non-editable status.
     */
    @Update("""
            UPDATE expense_claim
            SET expense_date=#{expenseDate}, amount=#{amount}, currency=#{currency},
                version=version+1, updated_at=#{now}
            WHERE id=#{expenseId} AND org_id=#{organizationId} AND version=#{expectedVersion}
              AND status IN ('DRAFT','NEEDS_INFO')
            """)
    int updateEditable(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expenseDate") LocalDate expenseDate,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("now") Instant now);

    /**
     * Optimistic version CAS that attaches the primary evidence to an editable
     * expense. Affects exactly one row on success.
     */
    @Update("""
            UPDATE expense_claim
            SET evidence_id=#{evidenceId}, version=version+1, updated_at=#{now}
            WHERE id=#{expenseId} AND org_id=#{organizationId} AND version=#{expectedVersion}
              AND status IN ('DRAFT','NEEDS_INFO')
            """)
    int attachEvidence(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId,
            @Param("expectedVersion") long expectedVersion,
            @Param("evidenceId") long evidenceId,
            @Param("now") Instant now);

    /**
     * Records the confirmed allocation decision pointer (confirm transaction
     * only; version is intentionally not bumped — the pointer is a booking fact
     * written by the finance workflow, not an owner edit).
     */
    @Update("""
            UPDATE expense_claim
            SET current_allocation_decision_id=#{decisionId}, updated_at=#{now}
            WHERE id=#{expenseId} AND org_id=#{organizationId}
            """)
    int updateCurrentAllocationDecisionPointer(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId,
            @Param("decisionId") long decisionId,
            @Param("now") Instant now);

    /**
     * Status mutation guarded by expectedVersion + expected status CAS, used by
     * submit/cancel/review transitions. Also records the approval case pointer
     * on first submit. Affects exactly one row on success.
     */
    @Update("""
            UPDATE expense_claim
            SET status=#{toStatus}, approval_case_id=#{approvalCaseId},
                version=version+1, updated_at=#{now}
            WHERE id=#{expenseId} AND org_id=#{organizationId}
              AND version=#{expectedVersion} AND status=#{fromStatus}
            """)
    int updateStatusVersioned(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId,
            @Param("expectedVersion") long expectedVersion,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("approvalCaseId") Long approvalCaseId,
            @Param("now") Instant now);

    @Select("""
            SELECT
            """ + CLAIM_COLUMNS + """
            FROM expense_claim ec
            WHERE ec.org_id=#{organizationId} AND ec.claimant_member_id=#{claimantMemberId}
            ORDER BY ec.created_at DESC, ec.id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<ExpenseClaim> selectByClaimant(
            @Param("organizationId") long organizationId,
            @Param("claimantMemberId") long claimantMemberId,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM expense_claim ec
            WHERE ec.org_id=#{organizationId} AND ec.claimant_member_id=#{claimantMemberId}
            """)
    long countByClaimant(
            @Param("organizationId") long organizationId,
            @Param("claimantMemberId") long claimantMemberId);

    /**
     * Finance review queue: active reviews (SUBMITTED / NEEDS_INFO) plus
     * APPROVED expenses that are not yet posting-ready (no current allocation
     * decision). The {@code statusFilter} is one of
     * SUBMITTED / NEEDS_INFO / APPROVED / ALL.
     */
    @Select("""
            <script>
            SELECT
            """ + CLAIM_COLUMNS + """
            FROM expense_claim ec
            WHERE ec.org_id=#{organizationId}
            <choose>
              <when test="statusFilter == 'SUBMITTED'">
                AND ec.status='SUBMITTED'
              </when>
              <when test="statusFilter == 'NEEDS_INFO'">
                AND ec.status='NEEDS_INFO'
              </when>
              <when test="statusFilter == 'APPROVED'">
                AND ec.status='APPROVED' AND ec.current_allocation_decision_id IS NULL
              </when>
              <otherwise>
                AND (ec.status IN ('SUBMITTED','NEEDS_INFO')
                     OR (ec.status='APPROVED' AND ec.current_allocation_decision_id IS NULL))
              </otherwise>
            </choose>
            ORDER BY ec.created_at ASC, ec.id ASC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<ExpenseClaim> selectReviewQueue(
            @Param("organizationId") long organizationId,
            @Param("statusFilter") String statusFilter,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM expense_claim ec
            WHERE ec.org_id=#{organizationId}
            <choose>
              <when test="statusFilter == 'SUBMITTED'">
                AND ec.status='SUBMITTED'
              </when>
              <when test="statusFilter == 'NEEDS_INFO'">
                AND ec.status='NEEDS_INFO'
              </when>
              <when test="statusFilter == 'APPROVED'">
                AND ec.status='APPROVED' AND ec.current_allocation_decision_id IS NULL
              </when>
              <otherwise>
                AND (ec.status IN ('SUBMITTED','NEEDS_INFO')
                     OR (ec.status='APPROVED' AND ec.current_allocation_decision_id IS NULL))
              </otherwise>
            </choose>
            </script>
            """)
    long countReviewQueue(
            @Param("organizationId") long organizationId,
            @Param("statusFilter") String statusFilter);

    // -- approval case ---------------------------------------------------------

    /** Storage status of the expense's primary evidence (submit gate). */
    @Select("""
            SELECT ev.storage_status FROM evidence ev
            WHERE ev.org_id=#{organizationId} AND ev.id=#{evidenceId}
            """)
    String selectEvidenceStorageStatus(
            @Param("organizationId") long organizationId,
            @Param("evidenceId") long evidenceId);

    /** The allocation decision status backing the postingReady derivation. */
    @Select("""
            SELECT status FROM allocation_decision
            WHERE org_id=#{organizationId} AND id=#{decisionId}
            """)
    String selectDecisionStatus(
            @Param("organizationId") long organizationId,
            @Param("decisionId") long decisionId);

    @Select("""
            SELECT
            """ + CASE_COLUMNS + """
            FROM approval_case ac
            WHERE ac.org_id=#{organizationId} AND ac.expense_claim_id=#{expenseId}
            """)
    ApprovalCase selectApprovalCaseByExpense(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId);

    @Select("""
            SELECT
            """ + CASE_COLUMNS + """
            FROM approval_case ac
            WHERE ac.org_id=#{organizationId} AND ac.expense_claim_id=#{expenseId}
            FOR UPDATE
            """)
    ApprovalCase selectApprovalCaseByExpenseForUpdate(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId);

    @Insert("""
            INSERT INTO approval_case(org_id,expense_claim_id,status,created_at,updated_at)
            VALUES (#{organizationId},#{expenseId},'PENDING',#{now},#{now})
            """)
    int insertApprovalCase(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId,
            @Param("now") Instant now);

    /** CAS on the expected approval status; affects exactly one row on success. */
    @Update("""
            UPDATE approval_case
            SET status=#{toStatus}, updated_at=#{now}
            WHERE id=#{caseId} AND org_id=#{organizationId} AND status=#{fromStatus}
            """)
    int updateApprovalCaseStatus(
            @Param("organizationId") long organizationId,
            @Param("caseId") long caseId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("now") Instant now);

    // -- approval actions (append-only) ----------------------------------------

    @Insert("""
            INSERT INTO approval_action(
                org_id,approval_case_id,actor_member_id,action_type,from_state,to_state,comment,created_at)
            VALUES (#{organizationId},#{approvalCaseId},#{actorMemberId},#{actionType},
                    #{fromState},#{toState},#{comment},#{now})
            """)
    int insertApprovalAction(
            @Param("organizationId") long organizationId,
            @Param("approvalCaseId") long approvalCaseId,
            @Param("actorMemberId") long actorMemberId,
            @Param("actionType") String actionType,
            @Param("fromState") String fromState,
            @Param("toState") String toState,
            @Param("comment") String comment,
            @Param("now") Instant now);

    @Select("""
            SELECT aa.id,aa.org_id,aa.approval_case_id,aa.actor_member_id,aa.action_type,
                   aa.from_state,aa.to_state,aa.comment,aa.created_at
            FROM approval_action aa
            WHERE aa.org_id=#{organizationId} AND aa.approval_case_id=#{approvalCaseId}
            ORDER BY aa.created_at ASC, aa.id ASC
            """)
    List<ApprovalAction> selectApprovalActionsByCase(
            @Param("organizationId") long organizationId,
            @Param("approvalCaseId") long approvalCaseId);
}
