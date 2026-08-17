package com.aicostops.allocation.infrastructure;

import com.aicostops.allocation.application.AllocationReadModels.ChargeLineage;
import com.aicostops.allocation.application.AllocationReadModels.AllocationChargeRow;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Allocation workflow access to {@code charge_fact} (locked reads, lineage,
 * canonical hint evidence) and the current-decision pointer mutation.
 */
@Mapper
public interface AllocationChargeFactMapper {

    String WORKFLOW_CHARGE_COLUMNS = """
            cf.id,cf.org_id,cf.raw_record_id,cf.fact_index,cf.provider_code,cf.amount,cf.currency,
            cf.period_start,cf.period_end,cf.review_status,cf.duplicate_of_charge_id,
            cf.current_allocation_decision_id
            """;

    @Select("""
            SELECT
            """ + WORKFLOW_CHARGE_COLUMNS + """
            FROM charge_fact cf
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            """)
    AllocationChargeRow selectCharge(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT
            """ + WORKFLOW_CHARGE_COLUMNS + """
            FROM charge_fact cf
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            FOR UPDATE
            """)
    AllocationChargeRow selectChargeForUpdate(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    /**
     * Confirmed-import lineage of one charge: the batch must be CONFIRMED and
     * the confirmed attempt must be the attempt that produced the raw record.
     * Also exposes the batch's provider account for rule matching.
     */
    @Select("""
            SELECT
                (ib.status='CONFIRMED' AND ib.confirmed_attempt_id=ia.id) AS confirmed_import,
                ib.provider_account_id AS provider_account_id
            FROM charge_fact cf
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            """)
    ChargeLineage selectLineage(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    /** Canonical attribution hint evidence of one charge (at most one row). */
    @Select("""
            SELECT ah.hint_type AS hint_type, ah.provider_value AS provider_value
            FROM attribution_hint ah
            WHERE ah.org_id=#{organizationId}
              AND ah.raw_record_id=#{rawRecordId} AND ah.fact_index=#{factIndex}
            """)
    HintRow selectHint(
            @Param("organizationId") long organizationId,
            @Param("rawRecordId") long rawRecordId,
            @Param("factIndex") int factIndex);

    @Update("""
            UPDATE charge_fact
            SET current_allocation_decision_id=#{decisionId}
            WHERE org_id=#{organizationId} AND id=#{chargeFactId}
            """)
    int updateCurrentDecisionPointer(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId,
            @Param("decisionId") long decisionId);

    record HintRow(String hintType, String providerValue) {
    }
}
