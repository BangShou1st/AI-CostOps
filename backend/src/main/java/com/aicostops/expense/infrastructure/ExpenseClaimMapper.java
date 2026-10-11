package com.aicostops.expense.infrastructure;

import com.aicostops.expense.domain.ExpenseClaim;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Expense row reads, locks and writes, plus evidence/allocation status checks. */
@Mapper
public interface ExpenseClaimMapper {

    String CLAIM_COLUMNS = """
            ec.id,ec.org_id,ec.claimant_member_id,ec.evidence_id,ec.expense_date,ec.amount,ec.currency,
            ec.status,ec.current_allocation_decision_id,ec.approval_case_id,ec.version,ec.created_at,ec.updated_at
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

    /** Atomic M5 transition APPROVED → POSTED. */
    @Update("""
            UPDATE expense_claim
            SET status='POSTED', version=version+1, updated_at=#{now}
            WHERE id=#{expenseId} AND org_id=#{organizationId}
              AND version=#{expectedVersion} AND status='APPROVED'
            """)
    int markPosted(
            @Param("organizationId") long organizationId,
            @Param("expenseId") long expenseId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    // -- referenced evidence and allocation status -----------------------------

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

}
