package com.aicostops.attribution.infrastructure;

import com.aicostops.attribution.application.AllocationDecisionRepository;
import com.aicostops.attribution.application.NewAllocationDecisionDraft;
import com.aicostops.attribution.application.NewAllocationLine;
import com.aicostops.attribution.domain.AllocationDecimal;
import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationLine;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisAllocationDecisionRepository implements AllocationDecisionRepository {

    private final AllocationDecisionMapper mapper;
    private final Clock clock;

    public MyBatisAllocationDecisionRepository(AllocationDecisionMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public long insertDraft(NewAllocationDecisionDraft draft) {
        mapper.insertDecision(
                draft.organizationId(),
                draft.subjectType().name(),
                draft.chargeFactId(),
                draft.expenseClaimId(),
                draft.decisionSource().name(),
                draft.allocationRuleId(),
                draft.createdByMemberId(),
                clock.instant());
        return mapper.lastInsertId();
    }

    @Override
    public void insertLine(NewAllocationLine line) {
        var amount = AllocationDecimal.money(line.allocatedAmount());
        var currency = line.currency();
        if (currency == null || currency.isBlank() || currency.length() != 3) {
            throw new IllegalArgumentException(
                    "Allocation currency must be a nonblank 3-character code");
        }
        mapper.insertLine(
                line.organizationId(),
                line.decisionId(),
                line.lineIndex(),
                amount,
                currency,
                line.projectId(),
                line.costCenterId(),
                line.teamId(),
                clock.instant());
    }

    @Override
    public Optional<AllocationDecision> findByIdAndOrganization(long organizationId, long decisionId) {
        return Optional.ofNullable(mapper.selectByIdAndOrganization(organizationId, decisionId));
    }

    @Override
    public List<AllocationLine> linesOfDecision(long organizationId, long decisionId) {
        return mapper.selectLinesOfDecision(organizationId, decisionId);
    }

    @Override
    public int countConfirmedForCharge(long organizationId, long chargeFactId) {
        return mapper.countConfirmedForCharge(organizationId, chargeFactId);
    }
}
