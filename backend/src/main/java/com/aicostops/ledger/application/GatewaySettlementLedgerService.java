package com.aicostops.ledger.application;

import com.aicostops.ledger.domain.LedgerEntry;
import com.aicostops.ledger.domain.LedgerPosting;
import com.aicostops.ledger.domain.LedgerPostingActorType;
import com.aicostops.ledger.domain.LedgerSourceType;
import com.aicostops.ledger.infrastructure.LedgerPostingMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Posts one immutable COST entry for one Settlement. It deliberately does not
 * construct ProviderCharge, Allocation or Expense objects.
 */
@Service
public final class GatewaySettlementLedgerService implements GatewaySettlementLedgerPort {

    private final LedgerPostingMapper ledger;

    public GatewaySettlementLedgerService(LedgerPostingMapper ledger) {
        this.ledger = ledger;
    }

    @Override
    public long post(PostCommand command) {
        validate(command);
        var postingKey = "GATEWAY_SETTLEMENT:" + command.settlementId();
        var existing = ledger.selectPostingByKey(command.organizationId(), postingKey);
        if (existing != null) {
            validateExisting(existing, command, postingKey);
            validateExistingEntries(command, existing);
            return existing.id();
        }

        ledger.insertSystemGatewaySettlementPosting(command.organizationId(), postingKey,
                command.settlementId(), command.billingPeriodId(), command.now(), command.now());
        var postingId = ledger.lastInsertId();
        var target = target(command.financialScopeType(), command.financialScopeId());
        ledger.insertGatewaySettlementEntry(command.organizationId(), postingId, 0, "COST",
                command.amount(), command.currency(), target.projectId(), target.costCenterId(),
                target.teamId(), command.budgetId(), command.settlementId(), command.now());
        return postingId;
    }

    private void validateExistingEntries(PostCommand command, LedgerPosting posting) {
        var entries = ledger.selectEntriesByPostingId(command.organizationId(), posting.id());
        if (entries.size() != 1) {
            throw new GatewaySettlementLedgerConflictException(
                    "A Gateway Settlement posting must have one entry");
        }
        var entry = entries.getFirst();
        if (entry.sourceGatewaySettlementId() == null
                || entry.sourceGatewaySettlementId() != command.settlementId()
                || entry.entryIndex() != 0
                || !"COST".equals(entry.entryType().name())
                || entry.amount().compareTo(command.amount()) != 0
                || !entry.currency().equals(command.currency())
                || !Objects.equals(entry.budgetId(), command.budgetId())
                || !matchesTarget(entry, command.financialScopeType(), command.financialScopeId())) {
            throw new GatewaySettlementLedgerConflictException(
                    "Existing Gateway Settlement Ledger entry conflicts");
        }
    }

    private static boolean matchesTarget(LedgerEntry entry, String type, long id) {
        return switch (type) {
            case "PROJECT" -> Objects.equals(entry.projectId(), id);
            case "TEAM" -> Objects.equals(entry.teamId(), id);
            case "COST_CENTER" -> Objects.equals(entry.costCenterId(), id);
            default -> false;
        };
    }

    private static void validateExisting(LedgerPosting existing, PostCommand command,
            String postingKey) {
        if (!existing.postingKey().equals(postingKey)
                || existing.sourceType() != LedgerSourceType.GATEWAY_SETTLEMENT
                || existing.sourceId() != command.settlementId()
                || existing.billingPeriodId() != command.billingPeriodId()
                || existing.postingActorType() != LedgerPostingActorType.SYSTEM
                || existing.postedByMemberId() != null) {
            throw new GatewaySettlementLedgerConflictException(
                    "Existing Gateway Settlement posting conflicts");
        }
    }

    private static Target target(String type, long id) {
        return switch (type) {
            case "PROJECT" -> new Target(id, null, null);
            case "TEAM" -> new Target(null, null, id);
            case "COST_CENTER" -> new Target(null, id, null);
            default -> throw new IllegalArgumentException("Unsupported financial scope type: " + type);
        };
    }

    private static void validate(PostCommand command) {
        if (command == null || command.organizationId() <= 0 || command.settlementId() <= 0
                || command.billingPeriodId() <= 0 || command.amount() == null
                || command.amount().signum() < 0 || command.currency() == null
                || !command.currency().matches("^[A-Z]{3}$") || command.financialScopeId() <= 0
                || command.now() == null) {
            throw new IllegalArgumentException("Invalid Gateway Settlement Ledger command");
        }
        target(command.financialScopeType(), command.financialScopeId());
    }

    private record Target(Long projectId, Long costCenterId, Long teamId) {
    }
}
