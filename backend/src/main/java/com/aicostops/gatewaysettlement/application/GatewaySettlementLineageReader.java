package com.aicostops.gatewaysettlement.application;

import com.aicostops.gatewaysettlement.application.GatewaySettlementCostCalculator.PricingRate;
import com.aicostops.gatewaysettlement.domain.GatewaySettlement;
import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Reads only the persisted Route Attempt pricing lineage for settlement. */
@Service
public final class GatewaySettlementLineageReader {

    private final GatewaySettlementMapper mapper;

    public GatewaySettlementLineageReader(GatewaySettlementMapper mapper) {
        this.mapper = mapper;
    }

    public SettlementLineage read(long organizationId, long settlementId) {
        var settlement = mapper.selectById(organizationId, settlementId);
        if (settlement == null) {
            throw new IllegalArgumentException("Gateway Settlement is not available.");
        }
        var row = mapper.selectLineage(organizationId, settlementId);
        if (row == null) {
            throw new IllegalArgumentException("Gateway Settlement lineage is not available.");
        }
        var dimensions = mapper.selectUsageDimensions(organizationId, settlement.usageFactId()).stream()
                .collect(Collectors.toUnmodifiableMap(
                        GatewaySettlementMapper.UsageDimensionRow::dimensionCode,
                        GatewaySettlementMapper.UsageDimensionRow::quantity,
                        (left, right) -> {
                            throw new IllegalArgumentException("Duplicate usage dimension.");
                        }));
        var rates = mapper.selectPricingRates(organizationId, settlement.pricingVersionId()).stream()
                .map(rate -> new PricingRate(rate.dimensionCode(), rate.unitQuantity(), rate.unitPrice()))
                .toList();
        return new SettlementLineage(settlement, row, dimensions, rates);
    }

    public record SettlementLineage(
            GatewaySettlement settlement,
            GatewaySettlementMapper.LineageRow row,
            Map<String, BigDecimal> quantities,
            List<PricingRate> rates) {
        public SettlementLineage {
            quantities = Map.copyOf(quantities);
            rates = List.copyOf(rates);
        }
    }
}
