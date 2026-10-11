package com.aicostops.expense.application;

import java.util.function.Supplier;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Retries the entire expense database transaction, never an external storage upload. */
@Component
public class ExpenseCommandTransactions {
    private static final int MAX_ATTEMPTS = 3;
    private final TransactionTemplate transactions;

    public ExpenseCommandTransactions(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public <T> T execute(Supplier<T> command) {
        for (var attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> command.get());
            } catch (DeadlockLoserDataAccessException deadlock) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw deadlock;
                }
            }
        }
    }
}
