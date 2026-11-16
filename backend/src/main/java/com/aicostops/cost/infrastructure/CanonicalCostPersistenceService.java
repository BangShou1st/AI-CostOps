package com.aicostops.cost.infrastructure;

import com.aicostops.cost.application.CanonicalCostNormalizer;
import com.aicostops.cost.application.CanonicalCostWritePort;
import com.aicostops.cost.application.CanonicalWriteResult;
import com.aicostops.cost.application.CanonicalizationInput;
import com.aicostops.cost.domain.CanonicalDecimal;
import com.aicostops.cost.domain.CanonicalFactBatch;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Canonical persistence boundary implementing {@link CanonicalCostWritePort}.
 *
 * <p>Runs inside the caller's bounded transaction. Every amount passes the exact
 * DECIMAL representability guard and every currency passes the non-blank,
 * exactly-3-character check before insert; violations fail closed so the whole
 * transaction rolls back. Provider evidence is never rounded or truncated.
 *
 * <p>One import batch is normalized and validated as a unit, then inserted with
 * bounded multi-row statements. The SQL chunk size stays below MySQL's
 * prepared-statement parameter limit even for OpenAI usage records that emit
 * several consumption rows per raw record.
 */
@Service
public class CanonicalCostPersistenceService implements CanonicalCostWritePort {

    private static final int MAX_ROWS_PER_INSERT = 500;

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
        return writeAll(List.of(input));
    }

    @Override
    public CanonicalWriteResult writeAll(List<CanonicalizationInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return CanonicalWriteResult.empty();
        }
        var now = clock.instant();
        var documents = new ArrayList<CanonicalFactsMapper.DocumentRow>();
        var consumptions = new ArrayList<CanonicalFactsMapper.ConsumptionRow>();
        var pricings = new ArrayList<CanonicalFactsMapper.PricingRow>();
        var charges = new ArrayList<CanonicalFactsMapper.ChargeRow>();
        var hints = new ArrayList<CanonicalFactsMapper.HintRow>();

        for (var input : inputs) {
            collect(normalizer.normalize(input), documents, consumptions, pricings, charges, hints);
        }

        insertChunked(documents, chunk -> factsMapper.insertDocuments(chunk, now));
        insertChunked(consumptions, chunk -> factsMapper.insertConsumptions(chunk, now));
        insertChunked(pricings, chunk -> factsMapper.insertPricings(chunk, now));
        insertChunked(charges, chunk -> factsMapper.insertCharges(chunk, now));
        insertChunked(hints, chunk -> factsMapper.insertHints(chunk, now));
        return new CanonicalWriteResult(
                documents.size(), consumptions.size(), pricings.size(), charges.size(), hints.size());
    }

    private void collect(
            CanonicalFactBatch batch,
            List<CanonicalFactsMapper.DocumentRow> documents,
            List<CanonicalFactsMapper.ConsumptionRow> consumptions,
            List<CanonicalFactsMapper.PricingRow> pricings,
            List<CanonicalFactsMapper.ChargeRow> charges,
            List<CanonicalFactsMapper.HintRow> hints) {
        for (var fact : batch.documents()) {
            documents.add(new CanonicalFactsMapper.DocumentRow(
                    fact.orgId(), fact.rawRecordId(), fact.factIndex(), fact.documentType().name(),
                    fact.periodStart(), fact.periodEnd(), nullableCurrency(fact.currency()),
                    money(fact.reportedTotalAmount()), money(fact.reportedPayableAmount()),
                    money(fact.reportedPaidAmount()), money(fact.reportedOutstandingAmount()),
                    serialize(fact.metadata())));
        }
        for (var fact : batch.consumptions()) {
            consumptions.add(new CanonicalFactsMapper.ConsumptionRow(
                    fact.orgId(), fact.rawRecordId(), fact.factIndex(), fact.providerCode(),
                    fact.serviceCode(), fact.model(), fact.meterCode(),
                    CanonicalDecimal.usage(fact.quantity()), fact.unit(),
                    fact.usageStart(), fact.usageEnd(), fact.timeGrain(),
                    fact.providerOrgRef(), fact.providerProjectRef(), fact.providerUserRef(),
                    fact.providerApiKeyHash(), fact.providerApiKeyLabel()));
        }
        for (var fact : batch.pricings()) {
            pricings.add(new CanonicalFactsMapper.PricingRow(
                    fact.orgId(), fact.rawRecordId(), fact.factIndex(), fact.providerCode(),
                    fact.serviceCode(), fact.model(), fact.meterCode(),
                    CanonicalDecimal.money(fact.unitPrice()), requireCurrency(fact.currency()),
                    fact.pricingUnit(), fact.periodStart(), fact.periodEnd(),
                    serialize(fact.metadata())));
        }
        for (var fact : batch.charges()) {
            charges.add(new CanonicalFactsMapper.ChargeRow(
                    fact.orgId(), fact.rawRecordId(), fact.factIndex(), fact.providerCode(),
                    fact.chargeCategory().name(), CanonicalDecimal.money(fact.amount()),
                    requireCurrency(fact.currency()), fact.fundingSource(),
                    money(fact.payableAmount()), money(fact.paidAmount()),
                    money(fact.outstandingAmount()), fact.periodStart(), fact.periodEnd(),
                    fact.reviewStatus().name(), fact.duplicateOfChargeId(),
                    serialize(fact.metadata())));
        }
        for (var fact : batch.hints()) {
            hints.add(new CanonicalFactsMapper.HintRow(
                    fact.orgId(), fact.rawRecordId(), fact.factIndex(), fact.hintType().name(),
                    fact.candidateScopeType() == null ? null : fact.candidateScopeType().name(),
                    fact.candidateScopeId(), fact.providerValue(), fact.confidence(),
                    serialize(fact.metadata())));
        }
    }

    private static <T> void insertChunked(List<T> rows, ToIntFunction<List<T>> insert) {
        for (var start = 0; start < rows.size(); start += MAX_ROWS_PER_INSERT) {
            var end = Math.min(rows.size(), start + MAX_ROWS_PER_INSERT);
            var chunk = rows.subList(start, end);
            if (insert.applyAsInt(chunk) != chunk.size()) {
                throw new IllegalStateException("Canonical batch insert affected an unexpected row count");
            }
        }
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
