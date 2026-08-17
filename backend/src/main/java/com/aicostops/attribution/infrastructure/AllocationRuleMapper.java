package com.aicostops.attribution.infrastructure;

import com.aicostops.attribution.domain.AllocationRule;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Row access for {@code allocation_rule}; versions are append-only. */
@Mapper
public interface AllocationRuleMapper {

    String RULE_COLUMNS = """
            ar.id,ar.org_id,ar.rule_key,ar.version,ar.name,ar.provider_code,ar.provider_account_id,
            ar.match_hint_type,ar.match_value,ar.priority,ar.target_project_id,ar.target_cost_center_id,
            ar.target_team_id,ar.effective_from,ar.effective_to,ar.status,ar.created_by_member_id,ar.created_at
            """;

    @Insert("""
            INSERT INTO allocation_rule(
                org_id,rule_key,version,name,provider_code,provider_account_id,
                match_hint_type,match_value,priority,
                target_project_id,target_cost_center_id,target_team_id,
                effective_from,effective_to,status,created_by_member_id,created_at)
            VALUES (#{organizationId},#{ruleKey},#{version},#{name},#{providerCode},#{providerAccountId},
                    #{matchHintType},#{matchValue},#{priority},
                    #{targetProjectId},#{targetCostCenterId},#{targetTeamId},
                    #{effectiveFrom},#{effectiveTo},'ACTIVE',#{createdByMemberId},#{createdAt})
            """)
    int insert(
            @Param("organizationId") long organizationId,
            @Param("ruleKey") String ruleKey,
            @Param("version") int version,
            @Param("name") String name,
            @Param("providerCode") String providerCode,
            @Param("providerAccountId") Long providerAccountId,
            @Param("matchHintType") String matchHintType,
            @Param("matchValue") String matchValue,
            @Param("priority") int priority,
            @Param("targetProjectId") Long targetProjectId,
            @Param("targetCostCenterId") Long targetCostCenterId,
            @Param("targetTeamId") Long targetTeamId,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("effectiveTo") Instant effectiveTo,
            @Param("createdByMemberId") long createdByMemberId,
            @Param("createdAt") Instant createdAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT
            """ + RULE_COLUMNS + """
            FROM allocation_rule ar
            WHERE ar.org_id=#{organizationId} AND ar.id=#{ruleId}
            """)
    AllocationRule selectByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("ruleId") long ruleId);

    @Select("""
            SELECT
            """ + RULE_COLUMNS + """
            FROM allocation_rule ar
            WHERE ar.org_id=#{organizationId} AND ar.rule_key=#{ruleKey} AND ar.version=#{version}
            """)
    AllocationRule selectByKeyAndVersion(
            @Param("organizationId") long organizationId,
            @Param("ruleKey") String ruleKey,
            @Param("version") int version);

    @Select("""
            SELECT
            """ + RULE_COLUMNS + """
            FROM allocation_rule ar
            WHERE ar.org_id=#{organizationId} AND ar.rule_key=#{ruleKey}
            ORDER BY ar.version ASC
            """)
    List<AllocationRule> selectVersionsOfKey(
            @Param("organizationId") long organizationId,
            @Param("ruleKey") String ruleKey);

    @Select("""
            SELECT COALESCE(MAX(version),0)
            FROM allocation_rule
            WHERE org_id=#{organizationId} AND rule_key=#{ruleKey}
            """)
    int selectMaxVersion(
            @Param("organizationId") long organizationId,
            @Param("ruleKey") String ruleKey);

    /**
     * Half-open overlap: {@code [from,to)} collides with an ACTIVE version of
     * the same key iff {@code from < existing.to} and {@code existing.from < to};
     * NULL bounds mean an unbounded side, adjacency is not overlap.
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM allocation_rule
                WHERE org_id=#{organizationId} AND rule_key=#{ruleKey} AND status='ACTIVE'
                  AND (#{effectiveTo} IS NULL OR effective_from < #{effectiveTo})
                  AND (effective_to IS NULL OR #{effectiveFrom} < effective_to)
            )
            """)
    boolean existsActiveOverlapSameKey(
            @Param("organizationId") long organizationId,
            @Param("ruleKey") String ruleKey,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("effectiveTo") Instant effectiveTo);

    @Select("""
            SELECT
            """ + RULE_COLUMNS + """
            FROM allocation_rule ar
            WHERE ar.org_id=#{organizationId} AND ar.id=#{ruleId}
            FOR UPDATE
            """)
    AllocationRule selectByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("ruleId") long ruleId);

    /**
     * Exact evaluation match: ACTIVE rules whose provider, optional provider
     * account constraint, hint type, hint value, and half-open effective range
     * all match. The provider code and match value comparisons are BINARY on
     * both sides, so both are case-sensitive and whitespace-sensitive
     * regardless of the column collation. A rule without an account
     * constraint matches every account. The ORDER BY mirrors the RuleEvaluator
     * tie-break exactly.
     */
    @Select("""
            SELECT
            """ + RULE_COLUMNS + """
            FROM allocation_rule ar
            WHERE ar.org_id=#{organizationId}
              AND ar.status='ACTIVE'
              AND BINARY ar.provider_code = BINARY #{providerCode}
              AND (ar.provider_account_id IS NULL OR ar.provider_account_id=#{providerAccountId})
              AND ar.match_hint_type=#{matchHintType}
              AND BINARY ar.match_value = BINARY #{matchValue}
              AND ar.effective_from <= #{effectiveAt}
              AND (ar.effective_to IS NULL OR #{effectiveAt} < ar.effective_to)
            ORDER BY ar.priority ASC, ar.rule_key ASC, ar.version DESC, ar.id ASC
            """)
    List<AllocationRule> selectActiveMatching(
            @Param("organizationId") long organizationId,
            @Param("providerCode") String providerCode,
            @Param("providerAccountId") Long providerAccountId,
            @Param("matchHintType") String matchHintType,
            @Param("matchValue") String matchValue,
            @Param("effectiveAt") Instant effectiveAt);

    @Update("""
            UPDATE allocation_rule
            SET status='ARCHIVED'
            WHERE org_id=#{organizationId} AND id=#{ruleId} AND status='ACTIVE'
            """)
    int updateStatusToArchived(
            @Param("organizationId") long organizationId,
            @Param("ruleId") long ruleId);

    @Select("""
            SELECT
            """ + RULE_COLUMNS + """
            FROM allocation_rule ar
            WHERE ar.org_id=#{organizationId}
            ORDER BY ar.rule_key ASC, ar.version DESC, ar.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AllocationRule> selectPage(
            @Param("organizationId") long organizationId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM allocation_rule
            WHERE org_id=#{organizationId}
            """)
    long countAll(@Param("organizationId") long organizationId);
}
