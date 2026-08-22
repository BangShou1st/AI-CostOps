package com.aicostops.budget.infrastructure;

import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.budget.domain.CommitmentApprovalAction;
import com.aicostops.budget.domain.CommitmentApprovalCase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Row access for {@code budget_commitment}, the commitment subject of
 * {@code approval_case} (V12), its append-only {@code approval_action}
 * history, and the append-only {@code budget_commitment_usage} lineage.
 * Every status mutation is a CAS on the expected status so a concurrent
 * loser can never double-apply a transition.
 */
@Mapper
public interface BudgetCommitmentMapper {

    String COMMITMENT_COLUMNS = """
            bc.id,bc.org_id,bc.budget_id,bc.status,bc.requested_amount,
            bc.approved_amount,bc.remaining_amount,bc.version,bc.created_at,bc.updated_at
            """;

    String CASE_COLUMNS = """
            ac.id,ac.org_id,ac.budget_commitment_id,ac.status,ac.created_at,ac.updated_at
            """;

    // -- commitment rows ------------------------------------------------------

    @Insert("""
            INSERT INTO budget_commitment(
                org_id,budget_id,status,requested_amount,
                approved_amount,remaining_amount,version,created_at,updated_at)
            VALUES (#{organizationId},#{budgetId},'REQUESTED',#{requestedAmount},
                    NULL,NULL,0,#{now},#{now})
            """)
    int insert(
            @Param("organizationId") long organizationId,
            @Param("budgetId") long budgetId,
            @Param("requestedAmount") BigDecimal requestedAmount,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT
            """ + COMMITMENT_COLUMNS + """
            FROM budget_commitment bc
            WHERE bc.org_id=#{organizationId} AND bc.id=#{commitmentId}
            """)
    BudgetCommitment selectByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId);

    @Select("""
            SELECT
            """ + COMMITMENT_COLUMNS + """
            FROM budget_commitment bc
            WHERE bc.org_id=#{organizationId} AND bc.id=#{commitmentId}
            FOR UPDATE
            """)
    BudgetCommitment selectByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId);

    /**
     * Atomic activation (AIC-044): REQUESTED → ACTIVE with approved and
     * remaining set exactly once. The status CAS makes a concurrent second
     * activation hit zero rows instead of double-writing amounts.
     */
    @Update("""
            UPDATE budget_commitment
            SET status='ACTIVE',
                approved_amount=#{approvedAmount},
                remaining_amount=#{remainingAmount},
                version=version+1, updated_at=#{now}
            WHERE id=#{commitmentId} AND org_id=#{organizationId} AND status='REQUESTED'
            """)
    int updateActivate(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId,
            @Param("approvedAmount") BigDecimal approvedAmount,
            @Param("remainingAmount") BigDecimal remainingAmount,
            @Param("now") Instant now);

    /** REQUESTED → REJECTED (no budget counter involvement). */
    @Update("""
            UPDATE budget_commitment
            SET status='REJECTED', version=version+1, updated_at=#{now}
            WHERE id=#{commitmentId} AND org_id=#{organizationId} AND status='REQUESTED'
            """)
    int updateReject(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId,
            @Param("now") Instant now);

    /** REQUESTED → CANCELED (no budget counter involvement). */
    @Update("""
            UPDATE budget_commitment
            SET status='CANCELED', version=version+1, updated_at=#{now}
            WHERE id=#{commitmentId} AND org_id=#{organizationId} AND status='REQUESTED'
            """)
    int updateCancel(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId,
            @Param("now") Instant now);

    /**
     * Release (AIC-045): ACTIVE / PARTIALLY_CONSUMED → RELEASED with the
     * remainder zeroed. The caller has already decremented the budget
     * committed counter by the same remainder in the same transaction.
     */
    @Update("""
            UPDATE budget_commitment
            SET status='RELEASED', remaining_amount=0, version=version+1, updated_at=#{now}
            WHERE id=#{commitmentId} AND org_id=#{organizationId}
              AND status IN ('ACTIVE','PARTIALLY_CONSUMED')
            """)
    int updateRelease(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId,
            @Param("now") Instant now);

    /**
     * Consume primitive (AIC-045): subtract the consumed amount and move to
     * the target status. The CAS re-checks the status and the remaining
     * floor inside MySQL, so remaining_amount can never go below zero even
     * under concurrency.
     */
    @Update("""
            UPDATE budget_commitment
            SET remaining_amount=remaining_amount-#{consumedAmount},
                status=#{toStatus}, version=version+1, updated_at=#{now}
            WHERE id=#{commitmentId} AND org_id=#{organizationId}
              AND status IN ('ACTIVE','PARTIALLY_CONSUMED')
              AND remaining_amount >= #{consumedAmount}
            """)
    int updateConsume(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId,
            @Param("consumedAmount") BigDecimal consumedAmount,
            @Param("toStatus") String toStatus,
            @Param("now") Instant now);

    @Select("""
            <script>
            SELECT
            """ + COMMITMENT_COLUMNS + """
            FROM budget_commitment bc
            JOIN budget b ON b.id = bc.budget_id AND b.org_id = bc.org_id
            WHERE bc.org_id=#{organizationId}
            <if test="budgetId != null">
              AND bc.budget_id=#{budgetId}
            </if>
            <if test="status != null">
              AND bc.status=#{status}
            </if>
            <if test="!organizationWide">
              AND (
                <foreach collection="visibleScopes" item="scope" separator=" OR ">
                  (b.scope_type=#{scope.scopeType} AND b.scope_id=#{scope.scopeId})
                </foreach>
              )
            </if>
            ORDER BY bc.created_at DESC, bc.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<BudgetCommitment> selectPage(
            @Param("organizationId") long organizationId,
            @Param("budgetId") Long budgetId,
            @Param("status") String status,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<BudgetMapper.ScopeConstraint> visibleScopes,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM budget_commitment bc
            JOIN budget b ON b.id = bc.budget_id AND b.org_id = bc.org_id
            WHERE bc.org_id=#{organizationId}
            <if test="budgetId != null">
              AND bc.budget_id=#{budgetId}
            </if>
            <if test="status != null">
              AND bc.status=#{status}
            </if>
            <if test="!organizationWide">
              AND (
                <foreach collection="visibleScopes" item="scope" separator=" OR ">
                  (b.scope_type=#{scope.scopeType} AND b.scope_id=#{scope.scopeId})
                </foreach>
              )
            </if>
            </script>
            """)
    long count(
            @Param("organizationId") long organizationId,
            @Param("budgetId") Long budgetId,
            @Param("status") String status,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<BudgetMapper.ScopeConstraint> visibleScopes);

    // -- commitment approval case (V12 shared shell, commitment subject) ------

    @Insert("""
            INSERT INTO approval_case(org_id,expense_claim_id,budget_commitment_id,
                status,created_at,updated_at)
            VALUES (#{organizationId},NULL,#{commitmentId},'PENDING',#{now},#{now})
            """)
    int insertApprovalCaseForCommitment(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId,
            @Param("now") Instant now);

    @Select("""
            SELECT
            """ + CASE_COLUMNS + """
            FROM approval_case ac
            WHERE ac.org_id=#{organizationId} AND ac.budget_commitment_id=#{commitmentId}
            """)
    CommitmentApprovalCase selectApprovalCaseByCommitment(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId);

    @Select("""
            SELECT
            """ + CASE_COLUMNS + """
            FROM approval_case ac
            WHERE ac.org_id=#{organizationId} AND ac.budget_commitment_id=#{commitmentId}
            FOR UPDATE
            """)
    CommitmentApprovalCase selectApprovalCaseByCommitmentForUpdate(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId);

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
                org_id,approval_case_id,actor_member_id,action_type,
                from_state,to_state,comment,created_at)
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
            ORDER BY aa.id ASC
            """)
    List<CommitmentApprovalAction> selectApprovalActionsByCase(
            @Param("organizationId") long organizationId,
            @Param("approvalCaseId") long approvalCaseId);

    /** The member who submitted the commitment (cancel ownership check). */
    @Select("""
            SELECT actor_member_id FROM approval_action
            WHERE org_id=#{organizationId} AND approval_case_id=#{approvalCaseId}
              AND action_type='SUBMIT'
            ORDER BY id ASC
            LIMIT 1
            """)
    Long selectSubmitActor(
            @Param("organizationId") long organizationId,
            @Param("approvalCaseId") long approvalCaseId);

    // -- usage lineage (append-only; ledger_entry FK arrives with AIC-047) -----

    @Insert("""
            INSERT INTO budget_commitment_usage(
                org_id,budget_commitment_id,ledger_entry_id,consumed_amount,created_at)
            VALUES (#{organizationId},#{commitmentId},#{ledgerEntryId},#{consumedAmount},#{now})
            """)
    int insertUsage(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId,
            @Param("ledgerEntryId") long ledgerEntryId,
            @Param("consumedAmount") BigDecimal consumedAmount,
            @Param("now") Instant now);

    @Select("""
            SELECT consumed_amount FROM budget_commitment_usage
            WHERE org_id=#{organizationId} AND budget_commitment_id=#{commitmentId}
              AND ledger_entry_id=#{ledgerEntryId}
            """)
    BigDecimal selectUsageAmountByLedgerEntry(
            @Param("organizationId") long organizationId,
            @Param("commitmentId") long commitmentId,
            @Param("ledgerEntryId") long ledgerEntryId);
}
