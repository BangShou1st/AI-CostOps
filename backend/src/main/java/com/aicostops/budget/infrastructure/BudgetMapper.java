package com.aicostops.budget.infrastructure;

import com.aicostops.budget.domain.Budget;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Row access for {@code budget}. */
@Mapper
public interface BudgetMapper {

    String BUDGET_COLUMNS = """
            b.id,b.org_id,b.billing_period_id,b.scope_type,b.scope_id,b.currency,
            b.total_amount,b.actual_amount,b.committed_amount,b.status,b.version,
            b.created_at,b.updated_at
            """;

    /**
     * A single visibility constraint for scoped budget lists: the budget's
     * own {@code scope_type}/{@code scope_id} must match one of the caller's
     * grants. ORG-granted callers are organization-wide and skip constraints.
     */
    record ScopeConstraint(String scopeType, long scopeId) {
    }

    @Insert("""
            INSERT INTO budget(
                org_id,billing_period_id,scope_type,scope_id,currency,
                total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
            VALUES (#{organizationId},#{billingPeriodId},#{scopeType},#{scopeId},#{currency},
                    #{totalAmount},0,0,'ACTIVE',0,#{now},#{now})
            """)
    int insert(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") long scopeId,
            @Param("currency") String currency,
            @Param("totalAmount") BigDecimal totalAmount,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM billing_period
                WHERE id=#{periodId} AND org_id=#{organizationId}
            )
            """)
    boolean existsBillingPeriod(
            @Param("organizationId") long organizationId,
            @Param("periodId") long periodId);

    @Select("""
            SELECT
            """ + BUDGET_COLUMNS + """
            FROM budget b
            WHERE b.org_id=#{organizationId} AND b.id=#{budgetId}
            """)
    Budget selectByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("budgetId") long budgetId);

    @Select("""
            SELECT
            """ + BUDGET_COLUMNS + """
            FROM budget b
            WHERE b.org_id=#{organizationId} AND b.id=#{budgetId}
            FOR UPDATE
            """)
    Budget selectByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("budgetId") long budgetId);

    /**
     * Atomic activation increment (AIC-044): the committed counter grows
     * only when the budget is ACTIVE and the available headroom
     * (total - actual - committed) covers the amount — one MySQL statement,
     * never a Java check-then-act. Zero rows means the caller must classify
     * the loser by re-reading: missing/invisible budget, non-ACTIVE status,
     * insufficient available, or a concurrent loser.
     */
    @Update("""
            UPDATE budget
            SET committed_amount=committed_amount+#{amount},
                version=version+1, updated_at=#{now}
            WHERE id=#{budgetId} AND org_id=#{organizationId}
              AND status='ACTIVE'
              AND total_amount - actual_amount - committed_amount >= #{amount}
            """)
    int incrementCommitted(
            @Param("organizationId") long organizationId,
            @Param("budgetId") long budgetId,
            @Param("amount") BigDecimal amount,
            @Param("now") Instant now);

    /**
     * Atomic decrement of the committed counter (AIC-045 release/consume).
     * The committed floor is re-checked inside MySQL, so the counter can
     * never go negative even under concurrency.
     */
    @Update("""
            UPDATE budget
            SET committed_amount=committed_amount-#{amount},
                version=version+1, updated_at=#{now}
            WHERE id=#{budgetId} AND org_id=#{organizationId}
              AND committed_amount >= #{amount}
            """)
    int decrementCommitted(
            @Param("organizationId") long organizationId,
            @Param("budgetId") long budgetId,
            @Param("amount") BigDecimal amount,
            @Param("now") Instant now);

    /**
     * Optimistic version CAS for the manageable total. Affects exactly one
     * row on success; financial counters are never touched here. Any other
     * result means a stale version.
     */
    @Update("""
            UPDATE budget
            SET total_amount=#{totalAmount}, version=version+1, updated_at=#{now}
            WHERE id=#{budgetId} AND org_id=#{organizationId} AND version=#{expectedVersion}
            """)
    int updateTotalAmount(
            @Param("organizationId") long organizationId,
            @Param("budgetId") long budgetId,
            @Param("expectedVersion") long expectedVersion,
            @Param("totalAmount") BigDecimal totalAmount,
            @Param("now") Instant now);

    @Select("""
            <script>
            SELECT
            """ + BUDGET_COLUMNS + """
            FROM budget b
            WHERE b.org_id=#{organizationId}
            <if test="billingPeriodId != null">
              AND b.billing_period_id=#{billingPeriodId}
            </if>
            <if test="scopeType != null">
              AND b.scope_type=#{scopeType} AND b.scope_id=#{scopeId}
            </if>
            <if test="!organizationWide">
              AND (
                <foreach collection="visibleScopes" item="scope" separator=" OR ">
                  (b.scope_type=#{scope.scopeType} AND b.scope_id=#{scope.scopeId})
                </foreach>
              )
            </if>
            ORDER BY b.created_at DESC, b.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Budget> selectPage(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") Long billingPeriodId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<ScopeConstraint> visibleScopes,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM budget b
            WHERE b.org_id=#{organizationId}
            <if test="billingPeriodId != null">
              AND b.billing_period_id=#{billingPeriodId}
            </if>
            <if test="scopeType != null">
              AND b.scope_type=#{scopeType} AND b.scope_id=#{scopeId}
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
            @Param("billingPeriodId") Long billingPeriodId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("organizationWide") boolean organizationWide,
            @Param("visibleScopes") List<ScopeConstraint> visibleScopes);
}