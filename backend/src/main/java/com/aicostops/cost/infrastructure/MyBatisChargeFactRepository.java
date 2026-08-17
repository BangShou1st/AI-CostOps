package com.aicostops.cost.infrastructure;

import com.aicostops.cost.application.ChargeFactRepository;
import com.aicostops.cost.application.CostReadModels.ChargeCostDetailRow;
import com.aicostops.cost.application.CostReadModels.ChargeCostRow;
import java.util.List;
import org.springframework.stereotype.Repository;

/** MyBatis-backed {@link ChargeFactRepository}. */
@Repository
public class MyBatisChargeFactRepository implements ChargeFactRepository {

    private final CostFactMapper mapper;

    public MyBatisChargeFactRepository(CostFactMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ChargeCostRow> pageCharges(
            long organizationId, String reviewStatus, int limit, int offset) {
        return mapper.pageCharges(organizationId, reviewStatus, limit, offset);
    }

    @Override
    public long countCharges(long organizationId, String reviewStatus) {
        return mapper.countCharges(organizationId, reviewStatus);
    }

    @Override
    public ChargeCostDetailRow selectChargeDetail(long organizationId, long chargeFactId) {
        return mapper.selectChargeDetail(organizationId, chargeFactId);
    }
}
