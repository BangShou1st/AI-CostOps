package com.aicostops.cost.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Batched row inserts for the five canonical fact tables. */
@Mapper
public interface CanonicalFactsMapper {

    @Insert("""
            <script>
            INSERT INTO external_document(
                org_id,raw_record_id,fact_index,document_type,period_start,period_end,currency,
                reported_total_amount,reported_payable_amount,reported_paid_amount,reported_outstanding_amount,
                metadata_json,created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.orgId},#{row.rawRecordId},#{row.factIndex},#{row.documentType},
                 #{row.periodStart},#{row.periodEnd},#{row.currency},
                 #{row.reportedTotalAmount},#{row.reportedPayableAmount},#{row.reportedPaidAmount},
                 #{row.reportedOutstandingAmount},CAST(#{row.metadataJson} AS JSON),#{now})
            </foreach>
            </script>
            """)
    int insertDocuments(@Param("rows") List<DocumentRow> rows, @Param("now") Instant now);

    @Insert("""
            <script>
            INSERT INTO consumption_fact(
                org_id,raw_record_id,fact_index,provider_code,service_code,model,meter_code,
                quantity,unit,usage_start,usage_end,time_grain,
                provider_org_ref,provider_project_ref,provider_user_ref,
                provider_api_key_hash,provider_api_key_label,created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.orgId},#{row.rawRecordId},#{row.factIndex},#{row.providerCode},
                 #{row.serviceCode},#{row.model},#{row.meterCode},#{row.quantity},#{row.unit},
                 #{row.usageStart},#{row.usageEnd},#{row.timeGrain},#{row.providerOrgRef},
                 #{row.providerProjectRef},#{row.providerUserRef},#{row.providerApiKeyHash},
                 #{row.providerApiKeyLabel},#{now})
            </foreach>
            </script>
            """)
    int insertConsumptions(@Param("rows") List<ConsumptionRow> rows, @Param("now") Instant now);

    @Insert("""
            <script>
            INSERT INTO pricing_fact(
                org_id,raw_record_id,fact_index,provider_code,service_code,model,meter_code,
                unit_price,currency,pricing_unit,period_start,period_end,metadata_json,created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.orgId},#{row.rawRecordId},#{row.factIndex},#{row.providerCode},
                 #{row.serviceCode},#{row.model},#{row.meterCode},#{row.unitPrice},#{row.currency},
                 #{row.pricingUnit},#{row.periodStart},#{row.periodEnd},
                 CAST(#{row.metadataJson} AS JSON),#{now})
            </foreach>
            </script>
            """)
    int insertPricings(@Param("rows") List<PricingRow> rows, @Param("now") Instant now);

    @Insert("""
            <script>
            INSERT INTO charge_fact(
                org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                funding_source,payable_amount,paid_amount,outstanding_amount,
                period_start,period_end,review_status,duplicate_of_charge_id,metadata_json,created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.orgId},#{row.rawRecordId},#{row.factIndex},#{row.providerCode},
                 #{row.chargeCategory},#{row.amount},#{row.currency},#{row.fundingSource},
                 #{row.payableAmount},#{row.paidAmount},#{row.outstandingAmount},
                 #{row.periodStart},#{row.periodEnd},#{row.reviewStatus},
                 #{row.duplicateOfChargeId},CAST(#{row.metadataJson} AS JSON),#{now})
            </foreach>
            </script>
            """)
    int insertCharges(@Param("rows") List<ChargeRow> rows, @Param("now") Instant now);

    @Insert("""
            <script>
            INSERT INTO attribution_hint(
                org_id,raw_record_id,fact_index,hint_type,candidate_scope_type,candidate_scope_id,
                provider_value,confidence,metadata_json,created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.orgId},#{row.rawRecordId},#{row.factIndex},#{row.hintType},
                 #{row.candidateScopeType},#{row.candidateScopeId},#{row.providerValue},
                 #{row.confidence},CAST(#{row.metadataJson} AS JSON),#{now})
            </foreach>
            </script>
            """)
    int insertHints(@Param("rows") List<HintRow> rows, @Param("now") Instant now);

    record DocumentRow(
            long orgId,
            long rawRecordId,
            int factIndex,
            String documentType,
            Instant periodStart,
            Instant periodEnd,
            String currency,
            BigDecimal reportedTotalAmount,
            BigDecimal reportedPayableAmount,
            BigDecimal reportedPaidAmount,
            BigDecimal reportedOutstandingAmount,
            String metadataJson) {
    }

    record ConsumptionRow(
            long orgId,
            long rawRecordId,
            int factIndex,
            String providerCode,
            String serviceCode,
            String model,
            String meterCode,
            BigDecimal quantity,
            String unit,
            Instant usageStart,
            Instant usageEnd,
            String timeGrain,
            String providerOrgRef,
            String providerProjectRef,
            String providerUserRef,
            String providerApiKeyHash,
            String providerApiKeyLabel) {
    }

    record PricingRow(
            long orgId,
            long rawRecordId,
            int factIndex,
            String providerCode,
            String serviceCode,
            String model,
            String meterCode,
            BigDecimal unitPrice,
            String currency,
            String pricingUnit,
            Instant periodStart,
            Instant periodEnd,
            String metadataJson) {
    }

    record ChargeRow(
            long orgId,
            long rawRecordId,
            int factIndex,
            String providerCode,
            String chargeCategory,
            BigDecimal amount,
            String currency,
            String fundingSource,
            BigDecimal payableAmount,
            BigDecimal paidAmount,
            BigDecimal outstandingAmount,
            Instant periodStart,
            Instant periodEnd,
            String reviewStatus,
            Long duplicateOfChargeId,
            String metadataJson) {
    }

    record HintRow(
            long orgId,
            long rawRecordId,
            int factIndex,
            String hintType,
            String candidateScopeType,
            Long candidateScopeId,
            String providerValue,
            BigDecimal confidence,
            String metadataJson) {
    }
}
