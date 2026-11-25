package com.aicostops.gateway.budget;

import com.aicostops.gateway.observability.GatewayMetrics;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.BudgetReservationMapper.ReservedAmountDriftRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Read-only periodic drift detector for the HC-04 invariant
 * {@code budget.reserved_amount = SUM(budget_reservation.reserved_amount WHERE status IN ('ACTIVE', 'PENDING_HOLD'))}.
 *
 * <p>Logs and increments bounded Micrometer counters when drift is observed,
 * and deliberately NEVER auto-repairs {@code budget.reserved_amount}.
 */
@Service
public class BudgetReservedDriftCheckService {

    private static final Logger log = LoggerFactory.getLogger(BudgetReservedDriftCheckService.class);
    private static final int DEFAULT_BATCH_LIMIT = 100;

    private final BudgetReservationMapper reservationMapper;
    private final GatewayMetrics metrics;

    public BudgetReservedDriftCheckService(
            BudgetReservationMapper reservationMapper, GatewayMetrics metrics) {
        this.reservationMapper = reservationMapper;
        this.metrics = metrics;
    }

    /** Runs one bounded read-only drift inspection and returns observed drifted rows. */
    public List<ReservedAmountDriftRow> inspectReservedDrift() {
        final List<ReservedAmountDriftRow> drifts;
        try {
            drifts = reservationMapper.findReservedAmountDrifts(DEFAULT_BATCH_LIMIT);
        } catch (RuntimeException ex) {
            log.warn("Budget reserved_amount drift check failed", ex);
            metrics.recordBudgetReservedDriftCheck("FAILED");
            return List.of();
        }
        if (drifts.isEmpty()) {
            metrics.recordBudgetReservedDriftCheck("OK");
            return drifts;
        }
        metrics.recordBudgetReservedDriftCheck("DRIFT");
        metrics.recordBudgetReservedDrift(drifts.size());
        for (var drift : drifts) {
            log.warn(
                    "Detected budget.reserved_amount drift (read-only check, no auto-repair): "
                            + "orgId={} budgetId={} budgetReserved={} expectedReserved={}",
                    drift.orgId(), drift.budgetId(),
                    drift.budgetReservedAmount(), drift.expectedReservedAmount());
        }
        return drifts;
    }
}
