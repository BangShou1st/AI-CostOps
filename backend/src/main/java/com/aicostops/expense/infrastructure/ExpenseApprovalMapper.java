package com.aicostops.expense.infrastructure;

import com.aicostops.expense.domain.ApprovalAction;
import com.aicostops.expense.domain.ApprovalCase;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Approval cases and append-only actions; called inside the expense workflow transaction. */
@Mapper
public interface ExpenseApprovalMapper {

    String CASE_COLUMNS = """
            ac.id,ac.org_id,ac.expense_claim_id,ac.status,ac.created_at,ac.updated_at
            """;

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
            ORDER BY aa.id ASC
            """)
    List<ApprovalAction> selectApprovalActionsByCase(
            @Param("organizationId") long organizationId,
            @Param("approvalCaseId") long approvalCaseId);

}
