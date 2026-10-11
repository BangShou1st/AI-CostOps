package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.expense.application.ExpenseCommandTransactions;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** No enclosing test transaction: verifies actual rollback before a retry. */
@SpringBootTest
@Tag("integration")
class ExpenseCommandTransactionsIntegrationTest extends AuthenticationContainersSupport {
    @Autowired private ExpenseCommandTransactions transactions;
    @Autowired private JdbcTemplate jdbc;
    private final String slug = "expense-transaction-" + UUID.randomUUID();

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM organization WHERE slug=?", slug);
    }

    @Test
    void retryRollsBackTheFailedAttemptBeforeCommittingTheNext() {
        var attempts = new AtomicInteger();
        var result = transactions.execute(() -> {
            insertOrganization();
            if (attempts.incrementAndGet() == 1) {
                throw deadlock();
            }
            return "committed";
        });
        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(2);
        assertThat(countOrganizations()).isEqualTo(1);
    }

    @Test
    void exhaustedRetriesLeaveNoPartialWrites() {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> transactions.execute(() -> {
            attempts.incrementAndGet();
            insertOrganization();
            throw deadlock();
        })).isInstanceOf(DeadlockLoserDataAccessException.class);
        assertThat(attempts).hasValue(3);
        assertThat(countOrganizations()).isZero();
    }

    @Test
    void otherFailuresRollBackWithoutRetrying() {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> transactions.execute(() -> {
            attempts.incrementAndGet();
            insertOrganization();
            throw new IllegalStateException("injected business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts).hasValue(1);
        assertThat(countOrganizations()).isZero();
    }

    private void insertOrganization() {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES ('Expense transaction test',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, slug);
    }

    private int countOrganizations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM organization WHERE slug=?", Integer.class, slug);
    }

    private static DeadlockLoserDataAccessException deadlock() {
        return new DeadlockLoserDataAccessException("injected deadlock", new RuntimeException("test"));
    }
}
