package com.aicostops.ledger.infrastructure;

import com.aicostops.ledger.application.ReconciliationAdjustmentLedgerPort;
import com.aicostops.ledger.domain.LedgerEntryType;
import java.math.BigDecimal;
import com.aicostops.ledger.infrastructure.LedgerPostingMapper;
import org.springframework.stereotype.Component;

/** Append-only Ledger persistence for Reconciliation Adjustments. */
@Component
public class ReconciliationAdjustmentLedgerAdapter implements ReconciliationAdjustmentLedgerPort {

    private final LedgerPostingMapper ledger;

    public ReconciliationAdjustmentLedgerAdapter(LedgerPostingMapper ledger) {
        this.ledger = ledger;
    }

    @Override
    public long postAdjustment(AdjustmentPostCommand command) {
        ledger.insertPosting(command.organizationId(),
                "RECONCILIATION_ADJUSTMENT:" + command.adjustmentId(),
                com.aicostops.ledger.domain.LedgerSourceType.RECONCILIATION_ADJUSTMENT.name(),
                command.adjustmentId(), null, command.billingPeriodId(), "POSTED",
                command.postedByMemberId(), command.postedAt(), command.postedAt());
        var postingId = ledger.lastInsertId();
        for (var line : command.lines()) {
            ledger.insertEntry(command.organizationId(), postingId, line.entryIndex(),
                    entryType(line.amount()).name(), line.amount(), line.currency(),
                    line.projectId(), line.costCenterId(), line.teamId(), line.budgetId(),
                    null, null, null, command.adjustmentId(), null, null, null,
                    command.postedAt());
        }
        return postingId;
    }

    private static LedgerEntryType entryType(BigDecimal amount) {
        return amount.signum() < 0 ? LedgerEntryType.CREDIT : LedgerEntryType.ADJUSTMENT;
    }
}
