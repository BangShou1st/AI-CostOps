package com.aicostops.attribution.infrastructure;

import com.aicostops.attribution.application.AllocationRuleRepository;
import com.aicostops.attribution.application.NewAllocationRuleVersion;
import com.aicostops.attribution.domain.AllocationRule;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisAllocationRuleRepository implements AllocationRuleRepository {

    private final AllocationRuleMapper mapper;
    private final Clock clock;

    public MyBatisAllocationRuleRepository(AllocationRuleMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public long insertVersion(NewAllocationRuleVersion draft) {
        mapper.insert(
                draft.organizationId(),
                draft.ruleKey(),
                draft.version(),
                draft.name(),
                draft.providerCode(),
                draft.providerAccountId(),
                draft.matchHintType().name(),
                draft.matchValue(),
                draft.priority(),
                draft.targetProjectId(),
                draft.targetCostCenterId(),
                draft.targetTeamId(),
                draft.effectiveFrom(),
                draft.effectiveTo(),
                draft.createdByMemberId(),
                clock.instant());
        return mapper.lastInsertId();
    }

    @Override
    public Optional<AllocationRule> findByIdAndOrganization(long organizationId, long ruleId) {
        return Optional.ofNullable(mapper.selectByIdAndOrganization(organizationId, ruleId));
    }

    @Override
    public Optional<AllocationRule> findByKeyAndVersion(long organizationId, String ruleKey,
            int version) {
        return Optional.ofNullable(mapper.selectByKeyAndVersion(organizationId, ruleKey, version));
    }

    @Override
    public List<AllocationRule> versionsOfKey(long organizationId, String ruleKey) {
        return mapper.selectVersionsOfKey(organizationId, ruleKey);
    }

    @Override
    public int maxVersion(long organizationId, String ruleKey) {
        return mapper.selectMaxVersion(organizationId, ruleKey);
    }

    @Override
    public boolean existsActiveOverlapSameKey(long organizationId, String ruleKey,
            Instant effectiveFrom, Instant effectiveTo) {
        return mapper.existsActiveOverlapSameKey(organizationId, ruleKey, effectiveFrom, effectiveTo);
    }
}
