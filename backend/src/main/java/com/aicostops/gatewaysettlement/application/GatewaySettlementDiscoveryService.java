package com.aicostops.gatewaysettlement.application;

import com.aicostops.gatewaysettlement.domain.GatewaySettlement;
import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper;
import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper.SettlementCandidate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Bounded, lock-free discovery of current FINAL Gateway usage. It never
 * claims or locks a Settlement row before the financial transaction.
 */
@Service
public final class GatewaySettlementDiscoveryService {

    public static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 500;

    private final GatewaySettlementMapper mapper;
    private final Clock clock;

    public GatewaySettlementDiscoveryService(GatewaySettlementMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public List<GatewaySettlement> discover(long organizationId) {
        return discover(organizationId, DEFAULT_BATCH_SIZE);
    }

    public List<GatewaySettlement> discover(long organizationId, int batchSize) {
        var candidates = mapper.selectEligibleFinalCandidates(
                organizationId, boundedBatchSize(batchSize));
        var discovered = new ArrayList<GatewaySettlement>(candidates.size());
        var now = Instant.now(clock);
        for (var candidate : candidates) {
            var settlementKey = "GATEWAY_REQUEST:" + candidate.publicRequestId();
            try {
                mapper.insertPending(candidate, settlementKey, now);
            } catch (DuplicateKeyException duplicateDiscovery) {
                // Business uniqueness is the convergence authority. The row
                // may have been inserted by another discovery worker.
            }
            var existing = mapper.selectByRequestId(organizationId, candidate.requestId());
            if (existing == null) {
                throw new IllegalStateException("A discovered Gateway Settlement must be readable");
            }
            discovered.add(existing);
        }
        return List.copyOf(discovered);
    }

    /** Returns candidate ids only; no Settlement row is locked or claimed. */
    public List<Long> workIds(long organizationId) {
        return workIds(organizationId, DEFAULT_BATCH_SIZE);
    }

    public List<Long> workIds(long organizationId, int batchSize) {
        return List.copyOf(mapper.selectWorkIds(organizationId, Instant.now(clock),
                boundedBatchSize(batchSize)));
    }

    private static int boundedBatchSize(int requested) {
        return Math.max(1, Math.min(MAX_BATCH_SIZE, requested));
    }
}
