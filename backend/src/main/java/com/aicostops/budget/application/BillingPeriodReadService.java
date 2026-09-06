package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.infrastructure.BillingPeriodMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class BillingPeriodReadService implements BillingPeriodReadPort {

    private final BillingPeriodMapper mapper;

    public BillingPeriodReadService(BillingPeriodMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public BillingPeriod findById(long organizationId, long billingPeriodId) {
        return mapper.selectByIdAndOrganization(organizationId, billingPeriodId);
    }

    @Override
    public List<BillingPeriod> listByOrganization(long organizationId) {
        return mapper.selectByOrganization(organizationId);
    }
}
