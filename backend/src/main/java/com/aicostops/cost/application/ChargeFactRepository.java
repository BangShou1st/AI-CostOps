package com.aicostops.cost.application;

import com.aicostops.cost.application.CostReadModels.ChargeCostDetailRow;
import com.aicostops.cost.application.CostReadModels.ChargeCostRow;
import java.util.List;

/** Read access to {@code charge_fact} for the cost read API. */
public interface ChargeFactRepository {

    List<ChargeCostRow> pageCharges(
            long organizationId, String reviewStatus, int limit, int offset);

    long countCharges(long organizationId, String reviewStatus);

    ChargeCostDetailRow selectChargeDetail(long organizationId, long chargeFactId);
}
