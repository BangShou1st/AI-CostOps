package com.aicostops.expense.application;

import java.time.Instant;
import java.util.List;

public interface ExpenseCloseBlockerPort {
    long countApprovedUnposted(long organizationId, Instant periodStart, Instant periodEnd);
    List<Long> sampleApprovedUnpostedIds(long organizationId, Instant periodStart,
            Instant periodEnd, int limit);
}
