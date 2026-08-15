package com.aicostops.cost.infrastructure;

import com.aicostops.cost.application.CanonicalCostNormalizer;
import com.aicostops.cost.application.CanonicalCostWritePort;
import com.aicostops.cost.application.CanonicalWriteResult;
import com.aicostops.cost.application.CanonicalizationInput;
import com.aicostops.cost.domain.CanonicalDecimal;
import com.aicostops.cost.domain.CanonicalFactBatch;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Canonical persistence boundary implementing {@link CanonicalCostWritePort}.
 *
 * <p>Runs inside the caller's bounded transaction. Every amount passes the exact
 * DECIMAL representability guard and every currency passes the non-blank,
 * exactly-3-character check before insert; violations fail closed so the whole
 * transaction rolls back. Provider evidence is never rounded or truncated.
 */
@Service
public class CanonicalCostPersistenceService implements CanonicalCostWritePort {

    private final CanonicalCostNormalizer normalizer;
    private final CanonicalFactsMapper factsMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CanonicalCostPersistenceService(
            CanonicalCostNormalizer normalizer,
            CanonicalFactsMapper factsMapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.normalizer = normalizer;
        this.factsMapper = factsMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public CanonicalWriteResult write(CanonicalizationInput input) {
        var batch = normalizer.normalize(input);
        if (batch.documents().isEmpty() && batch.consumptions().isEmpty()
                && batch.pricings().isEmpty() && batch.charges().isEmpty()
                && batch.hints().isEmpty()) {
            return new CanonicalWriteResult(0, 0, 0, 0, 0);
        }
        var now = clock.instant();
        var documents = 0;
        for (var fact : batch.documents()) {
            factsMapper.insertDocument(fact.orgId(), fact.rawRecordId(), fact.factIndex(),
                    fact.documentType().name(),
                    fact.periodStart(), fact.periodEnd(), nullableCurrency(fact.currency()),
                    money(fact.reportedTotalAmount()), money(fact.reportedPayableAmount()),
                    money(fact.reportedPaidAmount()), money(fact.reportedOutstandingAmount()),
                    serialize(fact.metadata()), now);
            documents++;
        }
        var consumptions = 0;
        for (var fact : batch.consumptions()) {
            factsMapper.insertConsumption(fact.orgId(), fact.rawRecordId(), fact.factIndex(),
                    fact.providerCode(), fact.serviceCode(), fact.model(), fact.meterCode(),
                    CanonicalDecimal.usage(fact.quantity()), fact.unit(),
                    fact.usageStart(), fact.usageEnd(), fact.timeGrain(),
                    fact.providerOrgRef(), fact.providerProjectRef(), fact.providerUserRef(),
                    fact.providerApiKeyHash(), fact.providerApiKeyLabel(), now);
            consumptions++;
        }
        var pricings = 0;
        for (var fact : batch.pricings()) {
            factsMapper.insertPricing(fact.orgId(), fact.rawRecordId(), fact.factIndex(),
                    fact.providerCode(), fact.serviceCode(), fact.model(), fact.meterCode(),
                    CanonicalDecimal.money(fact.unitPrice()), requireCurrency(fact.currency()),
                    fact.pricingUnit(), fact.periodStart(), fact.periodEnd(),
                    serialize(fact.metadata()), now);
            pricings++;
        }
        var charges = 0;
        for (var fact : batch.charges()) {
            factsMapper.insertCharge(fact.orgId(), fact.rawRecordId(), fact.factIndex(),
                    fact.providerCode(), fact.chargeCategory().name(),
                    CanonicalDecimal.money(fact.amount()), requireCurrency(fact.currency()),
                    fact.fundingSource(), money(fact.payableAmount()), money(fact.paidAmount()),
                    money(fact.outstandingAmount()),
                    fact.periodStart(), fact.periodEnd(), fact.reviewStatus().name(),
                    fact.duplicateOfChargeId(), serialize(fact.metadata()), now);
            charges++;
        }
        var hints = 0;
        for (var fact : batch.hints()) {
            factsMapper.insertHint(fact.orgId(), fact.rawRecordId(), fact.factIndex(),
                    fact.hintType().name(),
                    fact.candidateScopeType() == null ? null : fact.candidateScopeType().name(),
                    fact.candidateScopeId(), fact.providerValue(), fact.confidence(),
                    serialize(fact.metadata()), now);
            hints++;
        }
        return new CanonicalWriteResult(documents, consumptions, pricings, charges, hints);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : CanonicalDecimal.money(value);
    }

    /** Non-nullable currency columns: missing or malformed fails closed. */
    private static String requireCurrency(String value) {
        if (value == null || value.isBlank() || value.length() != 3) {
            throw new IllegalArgumentException(
                    "currency must be non-blank and exactly 3 characters: " + value);
        }
        return value;
    }

    /** Nullable currency columns: null stays null, any other value must be valid. */
    private static String nullableCurrency(String value) {
        return value == null ? null : requireCurrency(value);
    }

    private String serialize(Object metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to serialize canonical metadata", failure);
        }
    }
}
