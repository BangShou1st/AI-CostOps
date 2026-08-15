package com.aicostops.cost.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Row inserts for the five canonical fact tables; one record's facts share one transaction. */
@Mapper
public interface CanonicalFactsMapper {

    @Insert("""
            INSERT INTO external_document(
                org_id,raw_record_id,fact_index,document_type,period_start,period_end,currency,
                reported_total_amount,reported_payable_amount,reported_paid_amount,reported_outstanding_amount,
                metadata_json,created_at)
            VALUES (
                #{orgId},#{rawRecordId},#{factIndex},#{documentType},
                #{periodStart},#{periodEnd},#{currency},
                #{reportedTotalAmount},#{reportedPayableAmount},#{reportedPaidAmount},#{reportedOutstandingAmount},
                CAST(#{metadataJson} AS JSON),#{now})
            """)
    int insertDocument(
            @Param("orgId") long orgId,
            @Param("rawRecordId") long rawRecordId,
            @Param("factIndex") int factIndex,
            @Param("documentType") String documentType,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("currency") String currency,
            @Param("reportedTotalAmount") BigDecimal reportedTotalAmount,
            @Param("reportedPayableAmount") BigDecimal reportedPayableAmount,
            @Param("reportedPaidAmount") BigDecimal reportedPaidAmount,
            @Param("reportedOutstandingAmount") BigDecimal reportedOutstandingAmount,
            @Param("metadataJson") String metadataJson,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO consumption_fact(
                org_id,raw_record_id,fact_index,provider_code,service_code,model,meter_code,
                quantity,unit,usage_start,usage_end,time_grain,
                provider_org_ref,provider_project_ref,provider_user_ref,
                provider_api_key_hash,provider_api_key_label,created_at)
            VALUES (
                #{orgId},#{rawRecordId},#{factIndex},#{providerCode},#{serviceCode},#{model},#{meterCode},
                #{quantity},#{unit},#{usageStart},#{usageEnd},#{timeGrain},
                #{providerOrgRef},#{providerProjectRef},#{providerUserRef},
                #{providerApiKeyHash},#{providerApiKeyLabel},#{now})
            """)
    int insertConsumption(
            @Param("orgId") long orgId,
            @Param("rawRecordId") long rawRecordId,
            @Param("factIndex") int factIndex,
            @Param("providerCode") String providerCode,
            @Param("serviceCode") String serviceCode,
            @Param("model") String model,
            @Param("meterCode") String meterCode,
            @Param("quantity") BigDecimal quantity,
            @Param("unit") String unit,
            @Param("usageStart") Instant usageStart,
            @Param("usageEnd") Instant usageEnd,
            @Param("timeGrain") String timeGrain,
            @Param("providerOrgRef") String providerOrgRef,
            @Param("providerProjectRef") String providerProjectRef,
            @Param("providerUserRef") String providerUserRef,
            @Param("providerApiKeyHash") String providerApiKeyHash,
            @Param("providerApiKeyLabel") String providerApiKeyLabel,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO pricing_fact(
                org_id,raw_record_id,fact_index,provider_code,service_code,model,meter_code,
                unit_price,currency,pricing_unit,period_start,period_end,metadata_json,created_at)
            VALUES (
                #{orgId},#{rawRecordId},#{factIndex},#{providerCode},#{serviceCode},#{model},#{meterCode},
                #{unitPrice},#{currency},#{pricingUnit},#{periodStart},#{periodEnd},
                CAST(#{metadataJson} AS JSON),#{now})
            """)
    int insertPricing(
            @Param("orgId") long orgId,
            @Param("rawRecordId") long rawRecordId,
            @Param("factIndex") int factIndex,
            @Param("providerCode") String providerCode,
            @Param("serviceCode") String serviceCode,
            @Param("model") String model,
            @Param("meterCode") String meterCode,
            @Param("unitPrice") BigDecimal unitPrice,
            @Param("currency") String currency,
            @Param("pricingUnit") String pricingUnit,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("metadataJson") String metadataJson,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO charge_fact(
                org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                funding_source,payable_amount,paid_amount,outstanding_amount,
                period_start,period_end,review_status,duplicate_of_charge_id,metadata_json,created_at)
            VALUES (
                #{orgId},#{rawRecordId},#{factIndex},#{providerCode},#{chargeCategory},#{amount},#{currency},
                #{fundingSource},#{payableAmount},#{paidAmount},#{outstandingAmount},
                #{periodStart},#{periodEnd},#{reviewStatus},#{duplicateOfChargeId},
                CAST(#{metadataJson} AS JSON),#{now})
            """)
    int insertCharge(
            @Param("orgId") long orgId,
            @Param("rawRecordId") long rawRecordId,
            @Param("factIndex") int factIndex,
            @Param("providerCode") String providerCode,
            @Param("chargeCategory") String chargeCategory,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("fundingSource") String fundingSource,
            @Param("payableAmount") BigDecimal payableAmount,
            @Param("paidAmount") BigDecimal paidAmount,
            @Param("outstandingAmount") BigDecimal outstandingAmount,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("reviewStatus") String reviewStatus,
            @Param("duplicateOfChargeId") Long duplicateOfChargeId,
            @Param("metadataJson") String metadataJson,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO attribution_hint(
                org_id,raw_record_id,fact_index,hint_type,candidate_scope_type,candidate_scope_id,
                provider_value,confidence,metadata_json,created_at)
            VALUES (
                #{orgId},#{rawRecordId},#{factIndex},#{hintType},
                #{candidateScopeType},#{candidateScopeId},
                #{providerValue},#{confidence},CAST(#{metadataJson} AS JSON),#{now})
            """)
    int insertHint(
            @Param("orgId") long orgId,
            @Param("rawRecordId") long rawRecordId,
            @Param("factIndex") int factIndex,
            @Param("hintType") String hintType,
            @Param("candidateScopeType") String candidateScopeType,
            @Param("candidateScopeId") Long candidateScopeId,
            @Param("providerValue") String providerValue,
            @Param("confidence") BigDecimal confidence,
            @Param("metadataJson") String metadataJson,
            @Param("now") Instant now);
}
