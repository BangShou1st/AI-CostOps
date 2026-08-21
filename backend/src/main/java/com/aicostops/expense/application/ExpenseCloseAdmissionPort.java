package com.aicostops.expense.application;

import java.time.Instant;

/** Consumer-owned period fence used by expense approval to serialize with Close. */
public interface ExpenseCloseAdmissionPort {
    void lockOpenAt(long organizationId, Instant effectiveAt);
}
