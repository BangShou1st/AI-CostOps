package com.aicostops.attribution.application;

import com.aicostops.attribution.domain.AllocationRule;
import com.aicostops.attribution.domain.AllocationRuleMatchType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only persistence of allocation rule versions. There is deliberately
 * no definition update, archive command, or evaluator here — those belong to
 * the Group 3 rule workflow (#50).
 */
public interface AllocationRuleRepository {

    long insertVersion(NewAllocationRuleVersion draft);

    Optional<AllocationRule> findByIdAndOrganization(
            long organizationId, long ruleId);

    Optional<AllocationRule> findByKeyAndVersion(
            long organizationId, String ruleKey, int version);

    List<AllocationRule> versionsOfKey(
            long organizationId, String ruleKey);

    int maxVersion(
            long organizationId, String ruleKey);

    /** Half-open overlap test against ACTIVE versions of the same key. */
    boolean existsActiveOverlapSameKey(
            long organizationId,
            String ruleKey,
            Instant effectiveFrom,
            Instant effectiveTo);

    /** Locking read of one immutable rule version, org-scoped. */
    Optional<AllocationRule> findByIdForUpdate(long organizationId, long ruleId);

    /**
     * Every ACTIVE rule matching the exact evaluation inputs, ordered by the
     * canonical tie-break {@code priority ASC, ruleKey ASC, version DESC, id
     * ASC}. The provider account constraint applies only when the rule
     * specifies one; the effective range is the half-open {@code
     * [effectiveFrom, effectiveTo)} around {@code effectiveAt}.
     */
    List<AllocationRule> findActiveMatching(long organizationId, String providerCode,
            Long providerAccountId, AllocationRuleMatchType matchHintType, String matchValue,
            Instant effectiveAt);

    /** ACTIVE -> ARCHIVED; must affect exactly one row. */
    void archiveRule(long organizationId, long ruleId);

    /** Page of every rule version, newest version first per rule key. */
    List<AllocationRule> pageVersions(long organizationId, int limit, int offset);

    long countVersions(long organizationId);
}
