package com.aicostops.expense.infrastructure;

import com.aicostops.expense.application.ExpenseCloseBlockerPort;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExpenseCloseBlockerAdapter extends ExpenseCloseBlockerPort {

    String RELEVANT = """
            org_id=#{organizationId}
            AND status='APPROVED'
            AND TIMESTAMP(expense_date) >= #{periodStart}
            AND TIMESTAMP(expense_date) < #{periodEnd}
            """;

    @Override
    @Select("SELECT COUNT(*) FROM expense_claim WHERE " + RELEVANT)
    long countApprovedUnposted(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);

    @Override
    @Select("""
            SELECT id FROM expense_claim
            WHERE
            """ + RELEVANT + """
            ORDER BY expense_date,id
            LIMIT #{limit}
            """)
    List<Long> sampleApprovedUnpostedIds(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("limit") int limit);
}
