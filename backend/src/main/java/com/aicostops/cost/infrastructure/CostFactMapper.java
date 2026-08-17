package com.aicostops.cost.infrastructure;

import com.aicostops.cost.application.CostReadModels.ChargeCostDetailRow;
import com.aicostops.cost.application.CostReadModels.ChargeCostRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read access to {@code charge_fact} for the cost read API. */
@Mapper
public interface CostFactMapper {

    String CHARGE_COLUMNS = """
            cf.id,cf.org_id,cf.provider_code,cf.charge_category,cf.amount,cf.currency,
            cf.period_start,cf.period_end,cf.review_status,cf.current_allocation_decision_id
            """;

    @Select("""
            <script>
            SELECT
            """ + CHARGE_COLUMNS + """
            FROM charge_fact cf
            WHERE cf.org_id=#{organizationId}
              <if test="reviewStatus != null">
              AND cf.review_status=#{reviewStatus}
              </if>
            ORDER BY cf.created_at DESC, cf.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ChargeCostRow> pageCharges(
            @Param("organizationId") long organizationId,
            @Param("reviewStatus") String reviewStatus,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM charge_fact cf
            WHERE cf.org_id=#{organizationId}
              <if test="reviewStatus != null">
              AND cf.review_status=#{reviewStatus}
              </if>
            </script>
            """)
    long countCharges(
            @Param("organizationId") long organizationId,
            @Param("reviewStatus") String reviewStatus);

    @Select("""
            SELECT
            """ + CHARGE_COLUMNS + """
            ,cf.duplicate_of_charge_id,
                   CASE WHEN ib.status='CONFIRMED' AND ib.confirmed_attempt_id=ia.id
                        THEN TRUE ELSE FALSE END AS confirmed_import
            FROM charge_fact cf
            LEFT JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            LEFT JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            LEFT JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            """)
    ChargeCostDetailRow selectChargeDetail(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);
}
