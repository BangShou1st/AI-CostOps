package com.aicostops.attribution.infrastructure;

import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Row access for {@code allocation_decision} and {@code allocation_line}. */
@Mapper
public interface AllocationDecisionMapper {

    String DECISION_COLUMNS = """
            ad.id,ad.org_id,ad.subject_type,ad.charge_fact_id,ad.expense_claim_id,ad.decision_source,
            ad.allocation_rule_id,ad.status,ad.created_by_member_id,ad.created_at
            """;

    @Insert("""
            INSERT INTO allocation_decision(
                org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,allocation_rule_id,
                status,created_by_member_id,created_at)
            VALUES (#{organizationId},#{subjectType},#{chargeFactId},#{expenseClaimId},
                    #{decisionSource},#{allocationRuleId},'DRAFT',#{createdByMemberId},#{createdAt})
            """)
    int insertDecision(
            @Param("organizationId") long organizationId,
            @Param("subjectType") String subjectType,
            @Param("chargeFactId") Long chargeFactId,
            @Param("expenseClaimId") Long expenseClaimId,
            @Param("decisionSource") String decisionSource,
            @Param("allocationRuleId") Long allocationRuleId,
            @Param("createdByMemberId") Long createdByMemberId,
            @Param("createdAt") Instant createdAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Insert("""
            INSERT INTO allocation_line(
                org_id,decision_id,line_index,allocated_amount,currency,
                project_id,cost_center_id,team_id,created_at)
            VALUES (#{organizationId},#{decisionId},#{lineIndex},#{allocatedAmount},#{currency},
                    #{projectId},#{costCenterId},#{teamId},#{createdAt})
            """)
    int insertLine(
            @Param("organizationId") long organizationId,
            @Param("decisionId") long decisionId,
            @Param("lineIndex") int lineIndex,
            @Param("allocatedAmount") BigDecimal allocatedAmount,
            @Param("currency") String currency,
            @Param("projectId") Long projectId,
            @Param("costCenterId") Long costCenterId,
            @Param("teamId") Long teamId,
            @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT
            """ + DECISION_COLUMNS + """
            FROM allocation_decision ad
            WHERE ad.org_id=#{organizationId} AND ad.id=#{decisionId}
            """)
    AllocationDecision selectByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("decisionId") long decisionId);

    @Select("""
            SELECT al.id,al.org_id,al.decision_id,al.line_index,al.allocated_amount,al.currency,
                   al.project_id,al.cost_center_id,al.team_id,al.created_at
            FROM allocation_line al
            WHERE al.org_id=#{organizationId} AND al.decision_id=#{decisionId}
            ORDER BY al.line_index ASC
            """)
    List<AllocationLine> selectLinesOfDecision(
            @Param("organizationId") long organizationId,
            @Param("decisionId") long decisionId);

    @Select("""
            SELECT
            """ + DECISION_COLUMNS + """
            FROM allocation_decision ad
            WHERE ad.org_id=#{organizationId} AND ad.id=#{decisionId}
            FOR UPDATE
            """)
    AllocationDecision selectByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("decisionId") long decisionId);

    @Select("""
            SELECT COUNT(*)
            FROM allocation_decision
            WHERE org_id=#{organizationId} AND charge_fact_id=#{chargeFactId}
              AND status='CONFIRMED'
            """)
    int countConfirmedForCharge(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT COUNT(*)
            FROM allocation_decision
            WHERE org_id=#{organizationId} AND expense_claim_id=#{expenseClaimId}
              AND status='CONFIRMED'
            """)
    int countConfirmedForExpense(
            @Param("organizationId") long organizationId,
            @Param("expenseClaimId") long expenseClaimId);

    @Select("""
            SELECT
            """ + DECISION_COLUMNS + """
            FROM allocation_decision ad
            WHERE ad.org_id=#{organizationId} AND ad.charge_fact_id=#{chargeFactId}
              AND ad.status='DRAFT'
            ORDER BY ad.id ASC
            FOR UPDATE
            """)
    List<AllocationDecision> selectDraftDecisionsByChargeForUpdate(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT
            """ + DECISION_COLUMNS + """
            FROM allocation_decision ad
            WHERE ad.org_id=#{organizationId} AND ad.expense_claim_id=#{expenseClaimId}
              AND ad.status='DRAFT'
            ORDER BY ad.id ASC
            FOR UPDATE
            """)
    List<AllocationDecision> selectDraftDecisionsByExpenseForUpdate(
            @Param("organizationId") long organizationId,
            @Param("expenseClaimId") long expenseClaimId);

    @Select("""
            SELECT
            """ + DECISION_COLUMNS + """
            FROM allocation_decision ad
            WHERE ad.org_id=#{organizationId} AND ad.charge_fact_id=#{chargeFactId}
            ORDER BY ad.id ASC
            """)
    List<AllocationDecision> selectDecisionsByCharge(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT
            """ + DECISION_COLUMNS + """
            FROM allocation_decision ad
            WHERE ad.org_id=#{organizationId} AND ad.expense_claim_id=#{expenseClaimId}
            ORDER BY ad.id ASC
            """)
    List<AllocationDecision> selectDecisionsByExpense(
            @Param("organizationId") long organizationId,
            @Param("expenseClaimId") long expenseClaimId);

    @Select("""
            SELECT al.id,al.org_id,al.decision_id,al.line_index,al.allocated_amount,al.currency,
                   al.project_id,al.cost_center_id,al.team_id,al.created_at
            FROM allocation_line al
            WHERE al.org_id=#{organizationId} AND al.decision_id=#{decisionId}
            ORDER BY al.line_index ASC
            FOR UPDATE
            """)
    List<AllocationLine> selectLinesOfDecisionForUpdate(
            @Param("organizationId") long organizationId,
            @Param("decisionId") long decisionId);

    @Delete("""
            DELETE FROM allocation_line
            WHERE org_id=#{organizationId} AND decision_id=#{decisionId}
            """)
    int deleteLinesOfDecision(
            @Param("organizationId") long organizationId,
            @Param("decisionId") long decisionId);

    @Update("""
            UPDATE allocation_decision
            SET status=#{toStatus}
            WHERE org_id=#{organizationId} AND id=#{decisionId} AND status=#{fromStatus}
            """)
    int updateStatus(
            @Param("organizationId") long organizationId,
            @Param("decisionId") long decisionId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);
}
