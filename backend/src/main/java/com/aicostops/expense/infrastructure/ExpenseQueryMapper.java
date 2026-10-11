package com.aicostops.expense.infrastructure;

import com.aicostops.expense.application.ExpenseReadModels.ExpenseSummary;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** List summaries and totals. SQL lives in mapper/expense/ExpenseQueryMapper.xml. */
@Mapper
public interface ExpenseQueryMapper {

    List<ExpenseSummary> selectSummariesByClaimant(
            @Param("organizationId") long organizationId,
            @Param("claimantMemberId") long claimantMemberId,
            @Param("size") int size,
            @Param("offset") int offset);

    long countByClaimant(
            @Param("organizationId") long organizationId,
            @Param("claimantMemberId") long claimantMemberId);

    List<ExpenseSummary> selectReviewSummaries(
            @Param("organizationId") long organizationId,
            @Param("statusFilter") String statusFilter,
            @Param("size") int size,
            @Param("offset") int offset);

    long countReviewQueue(
            @Param("organizationId") long organizationId,
            @Param("statusFilter") String statusFilter);

}
