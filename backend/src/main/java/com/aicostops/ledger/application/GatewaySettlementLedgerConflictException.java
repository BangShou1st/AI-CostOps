package com.aicostops.ledger.application;

/** Existing Gateway Settlement Ledger lineage is incompatible with the command. */
public final class GatewaySettlementLedgerConflictException extends RuntimeException {

    public GatewaySettlementLedgerConflictException(String message) {
        super(message);
    }
}
