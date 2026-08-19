package com.aicostops.ledger.infrastructure;

import com.aicostops.ledger.domain.CorrectionGroup;
import com.aicostops.ledger.domain.LedgerEntry;
import com.aicostops.ledger.domain.LedgerPosting;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Append-only persistence seam for committed ledger history. */
@Mapper
public interface LedgerPostingMapper {

    String POSTING_COLUMNS = """
            lp.id,lp.org_id,lp.posting_key,lp.source_type,lp.source_id,
            lp.allocation_decision_id,lp.billing_period_id,lp.status,
            lp.posted_by_member_id,lp.posted_at,lp.created_at
            """;

    String ENTRY_COLUMNS = """
            le.id,le.org_id,le.posting_id,le.entry_index,le.entry_type,le.amount,le.currency,
            le.project_id,le.cost_center_id,le.team_id,le.budget_id,
            le.source_charge_fact_id,le.source_expense_claim_id,le.allocation_line_id,
            le.correction_group_id,le.reverses_entry_id,le.created_at
            """;

    String CORRECTION_COLUMNS = """
            cg.id,cg.org_id,cg.correction_key,cg.reason_code,cg.reason_text,
            cg.target_entry_id,cg.target_posting_id,cg.status,cg.created_by_member_id,cg.created_at
            """;

    @Insert("""
            INSERT INTO ledger_posting(
                org_id,posting_key,source_type,source_id,allocation_decision_id,
                billing_period_id,status,posted_by_member_id,posted_at,created_at)
            VALUES (#{organizationId},#{postingKey},#{sourceType},#{sourceId},#{allocationDecisionId},
                    #{billingPeriodId},#{status},#{postedByMemberId},#{postedAt},#{createdAt})
            """)
    int insertPosting(
            @Param("organizationId") long organizationId,
            @Param("postingKey") String postingKey,
            @Param("sourceType") String sourceType,
            @Param("sourceId") long sourceId,
            @Param("allocationDecisionId") Long allocationDecisionId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("status") String status,
            @Param("postedByMemberId") long postedByMemberId,
            @Param("postedAt") Instant postedAt,
            @Param("createdAt") Instant createdAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT
            """ + POSTING_COLUMNS + """
            FROM ledger_posting lp
            WHERE lp.org_id=#{organizationId} AND lp.posting_key=#{postingKey}
            """)
    LedgerPosting selectPostingByKey(
            @Param("organizationId") long organizationId,
            @Param("postingKey") String postingKey);

    @Select("""
            SELECT
            """ + POSTING_COLUMNS + """
            FROM ledger_posting lp
            WHERE lp.org_id=#{organizationId} AND lp.id=#{postingId}
            """)
    LedgerPosting selectPostingByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("postingId") long postingId);

    @Select("""
            SELECT
            """ + POSTING_COLUMNS + """
            FROM ledger_posting lp
            WHERE lp.org_id=#{organizationId} AND lp.id=#{postingId}
            FOR UPDATE
            """)
    LedgerPosting selectPostingByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("postingId") long postingId);

    @Insert("""
            INSERT INTO ledger_entry(
                org_id,posting_id,entry_index,entry_type,amount,currency,
                project_id,cost_center_id,team_id,budget_id,
                source_charge_fact_id,source_expense_claim_id,allocation_line_id,
                correction_group_id,reverses_entry_id,created_at)
            VALUES (#{organizationId},#{postingId},#{entryIndex},#{entryType},#{amount},#{currency},
                    #{projectId},#{costCenterId},#{teamId},#{budgetId},
                    #{sourceChargeFactId},#{sourceExpenseClaimId},#{allocationLineId},
                    #{correctionGroupId},#{reversesEntryId},#{createdAt})
            """)
    int insertEntry(
            @Param("organizationId") long organizationId,
            @Param("postingId") long postingId,
            @Param("entryIndex") int entryIndex,
            @Param("entryType") String entryType,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("projectId") Long projectId,
            @Param("costCenterId") Long costCenterId,
            @Param("teamId") Long teamId,
            @Param("budgetId") Long budgetId,
            @Param("sourceChargeFactId") Long sourceChargeFactId,
            @Param("sourceExpenseClaimId") Long sourceExpenseClaimId,
            @Param("allocationLineId") Long allocationLineId,
            @Param("correctionGroupId") Long correctionGroupId,
            @Param("reversesEntryId") Long reversesEntryId,
            @Param("createdAt") Instant createdAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastEntryId();

    @Select("""
            SELECT
            """ + ENTRY_COLUMNS + """
            FROM ledger_entry le
            WHERE le.org_id=#{organizationId} AND le.id=#{entryId}
            """)
    LedgerEntry selectEntryByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("entryId") long entryId);

    @Select("""
            SELECT
            """ + ENTRY_COLUMNS + """
            FROM ledger_entry le
            WHERE le.org_id=#{organizationId} AND le.id=#{entryId}
            FOR UPDATE
            """)
    LedgerEntry selectEntryByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("entryId") long entryId);

    @Select("""
            SELECT
            """ + ENTRY_COLUMNS + """
            FROM ledger_entry le
            WHERE le.org_id=#{organizationId} AND le.posting_id=#{postingId}
            ORDER BY le.entry_index ASC, le.id ASC
            """)
    List<LedgerEntry> selectEntriesByPostingId(
            @Param("organizationId") long organizationId,
            @Param("postingId") long postingId);

    @Insert("""
            INSERT INTO correction_group(
                org_id,correction_key,reason_code,reason_text,target_entry_id,target_posting_id,
                status,created_by_member_id,created_at)
            VALUES (#{organizationId},#{correctionKey},#{reasonCode},#{reasonText},#{targetEntryId},
                    #{targetPostingId},#{status},#{createdByMemberId},#{createdAt})
            """)
    int insertCorrectionGroup(
            @Param("organizationId") long organizationId,
            @Param("correctionKey") String correctionKey,
            @Param("reasonCode") String reasonCode,
            @Param("reasonText") String reasonText,
            @Param("targetEntryId") long targetEntryId,
            @Param("targetPostingId") long targetPostingId,
            @Param("status") String status,
            @Param("createdByMemberId") long createdByMemberId,
            @Param("createdAt") Instant createdAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastCorrectionGroupId();

    @Select("""
            SELECT
            """ + CORRECTION_COLUMNS + """
            FROM correction_group cg
            WHERE cg.org_id=#{organizationId} AND cg.id=#{correctionGroupId}
            """)
    CorrectionGroup selectCorrectionGroupByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("correctionGroupId") long correctionGroupId);
}
