package com.aicostops.attribution.application;

import com.aicostops.attribution.domain.AllocationRule;
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
}
