package com.aicostops.cost.review.infrastructure;

import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateDraft;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactLineageRow;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactRow;
import com.aicostops.cost.review.domain.DuplicateCandidate;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Row access for {@code duplicate_candidate} plus the charge-side guards and
 * aggregate reconciliation updates used by the review workflow.
 */
@Mapper
public interface DuplicateCandidateMapper {

    String CANDIDATE_COLUMNS = """
            dc.id,dc.org_id,dc.charge_fact_id,dc.matched_charge_id,dc.candidate_type,dc.fingerprint,
            dc.algorithm_version,dc.match_reason,dc.status,dc.created_at,dc.resolved_at
            """;

    String OPEN_CANDIDATE_EXISTS = """
            EXISTS (
                SELECT 1 FROM duplicate_candidate o
                WHERE o.org_id = cf.org_id
                  AND o.status = 'OPEN'
                  AND (o.charge_fact_id = cf.id OR o.matched_charge_id = cf.id)
            )
            """;

    @Insert("""
            INSERT INTO duplicate_candidate(
                org_id,charge_fact_id,matched_charge_id,candidate_type,fingerprint,algorithm_version,
                match_reason,status,created_at)
            VALUES (#{orgId},#{chargeFactId},#{matchedChargeId},#{candidateType},#{fingerprint},
                    #{algorithmVersion},#{matchReason},'OPEN',#{createdAt})
            """)
    int insert(
            @Param("orgId") long orgId,
            @Param("chargeFactId") long chargeFactId,
            @Param("matchedChargeId") long matchedChargeId,
            @Param("candidateType") String candidateType,
            @Param("fingerprint") String fingerprint,
            @Param("algorithmVersion") String algorithmVersion,
            @Param("matchReason") String matchReason,
            @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT
            """ + CANDIDATE_COLUMNS + """
            FROM duplicate_candidate dc
            WHERE dc.org_id=#{orgId} AND dc.id=#{candidateId}
            FOR UPDATE
            """)
    DuplicateCandidate selectByIdForUpdate(
            @Param("orgId") long orgId,
            @Param("candidateId") long candidateId);

    @Select("""
            SELECT
            """ + CANDIDATE_COLUMNS + """
            FROM duplicate_candidate dc
            WHERE dc.org_id=#{orgId} AND dc.id=#{candidateId}
            """)
    DuplicateCandidate selectById(
            @Param("orgId") long orgId,
            @Param("candidateId") long candidateId);

    @Select("""
            SELECT
            """ + CANDIDATE_COLUMNS + """
            FROM duplicate_candidate dc
            WHERE dc.org_id=#{orgId} AND dc.status='OPEN'
              AND (dc.charge_fact_id=#{chargeId} OR dc.matched_charge_id=#{chargeId})
            ORDER BY dc.id
            FOR UPDATE
            """)
    List<DuplicateCandidate> selectOpenByChargeForUpdate(
            @Param("orgId") long orgId,
            @Param("chargeId") long chargeId);

    @Select("""
            <script>
            SELECT
            """ + CANDIDATE_COLUMNS + """
            FROM duplicate_candidate dc
            WHERE dc.org_id=#{orgId}
              AND (#{status} IS NULL OR dc.status=#{status})
              AND (#{candidateType} IS NULL OR dc.candidate_type=#{candidateType})
            ORDER BY dc.created_at DESC, dc.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<DuplicateCandidate> pageCandidates(
            @Param("orgId") long orgId,
            @Param("status") String status,
            @Param("candidateType") String candidateType,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("""
            SELECT COUNT(*)
            FROM duplicate_candidate dc
            WHERE dc.org_id=#{orgId}
              AND (#{status} IS NULL OR dc.status=#{status})
              AND (#{candidateType} IS NULL OR dc.candidate_type=#{candidateType})
            """)
    long countCandidates(
            @Param("orgId") long orgId,
            @Param("status") String status,
            @Param("candidateType") String candidateType);

    @Select("""
            <script>
            SELECT cf.id,cf.provider_code,cf.charge_category,cf.amount,cf.currency,
                   cf.period_start,cf.period_end,cf.review_status,cf.duplicate_of_charge_id
            FROM charge_fact cf
            WHERE cf.org_id=#{orgId}
              AND cf.id IN
              <foreach collection="chargeFactIds" item="chargeFactId" open="(" separator="," close=")">
                  #{chargeFactId}
              </foreach>
            ORDER BY cf.id
            </script>
            """)
    List<com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeSummary> selectChargeSummaries(
            @Param("orgId") long orgId,
            @Param("chargeFactIds") List<Long> chargeFactIds);

    @Select("""
            SELECT
                cf.id,cf.org_id,ib.provider_account_id,cf.provider_code,cf.charge_category,
                cf.amount,cf.currency,cf.period_start,cf.period_end,cf.review_status
            FROM charge_fact cf
            JOIN raw_provider_record rpr ON rpr.id = cf.raw_record_id
            JOIN import_attempt ia ON ia.id = rpr.import_attempt_id
            JOIN import_batch ib ON ib.id = ia.import_batch_id
            WHERE cf.org_id=#{orgId}
              AND ib.org_id = cf.org_id
              AND ib.status = 'CONFIRMED'
              AND ib.confirmed_attempt_id = ia.id
              AND cf.review_status IN ('CLEAN','SUSPECTED_DUPLICATE')
            ORDER BY cf.id
            """)
    List<ChargeFactLineageRow> selectEligibleLineage(@Param("orgId") long orgId);

    @Select("""
            <script>
            SELECT cf.id,cf.org_id,cf.review_status,cf.duplicate_of_charge_id
            FROM charge_fact cf
            WHERE cf.org_id=#{orgId}
              AND cf.id IN
              <foreach collection="chargeFactIds" item="chargeFactId" open="(" separator="," close=")">
                  #{chargeFactId}
              </foreach>
            ORDER BY cf.id
            FOR UPDATE
            </script>
            """)
    List<ChargeFactRow> selectChargesForUpdate(
            @Param("orgId") long orgId,
            @Param("chargeFactIds") List<Long> chargeFactIds);

    @Update("""
            UPDATE duplicate_candidate
            SET status='KEPT_CLEAN', resolved_at=#{resolvedAt}
            WHERE org_id=#{orgId} AND id=#{candidateId} AND status='OPEN'
            """)
    int markKeptClean(
            @Param("orgId") long orgId,
            @Param("candidateId") long candidateId,
            @Param("resolvedAt") Instant resolvedAt);

    @Update("""
            UPDATE duplicate_candidate
            SET status='CONFIRMED_DUPLICATE', resolved_at=#{resolvedAt}
            WHERE org_id=#{orgId} AND id=#{candidateId} AND status='OPEN'
            """)
    int markConfirmedDuplicate(
            @Param("orgId") long orgId,
            @Param("candidateId") long candidateId,
            @Param("resolvedAt") Instant resolvedAt);

    @Update("""
            UPDATE duplicate_candidate
            SET status='SUPERSEDED', resolved_at=#{resolvedAt}
            WHERE org_id=#{orgId} AND status='OPEN'
              AND id <> #{currentCandidateId}
              AND (charge_fact_id=#{chargeId} OR matched_charge_id=#{chargeId})
            """)
    int supersedeOtherOpenByCharge(
            @Param("orgId") long orgId,
            @Param("chargeId") long chargeId,
            @Param("currentCandidateId") long currentCandidateId,
            @Param("resolvedAt") Instant resolvedAt);

    @Update("""
            UPDATE charge_fact
            SET review_status='EXCLUDED_DUPLICATE', duplicate_of_charge_id=#{keeperChargeFactId}
            WHERE org_id=#{orgId} AND id=#{excludedChargeFactId}
              AND review_status IN ('CLEAN','SUSPECTED_DUPLICATE')
              AND duplicate_of_charge_id IS NULL
            """)
    int markChargeExcluded(
            @Param("orgId") long orgId,
            @Param("excludedChargeFactId") long excludedChargeFactId,
            @Param("keeperChargeFactId") long keeperChargeFactId);

    @Select("""
            SELECT COUNT(*)
            FROM duplicate_candidate dc
            WHERE dc.org_id=#{orgId} AND dc.status='OPEN'
              AND (dc.charge_fact_id=#{chargeId} OR dc.matched_charge_id=#{chargeId})
            """)
    int countOpenByCharge(
            @Param("orgId") long orgId,
            @Param("chargeId") long chargeId);

    @Select("""
            SELECT COUNT(*)
            FROM charge_fact cf
            WHERE cf.org_id=#{orgId} AND cf.duplicate_of_charge_id=#{keeperChargeFactId}
            """)
    int countInboundDuplicateReferences(
            @Param("orgId") long orgId,
            @Param("keeperChargeFactId") long keeperChargeFactId);

    @Update("""
            UPDATE charge_fact cf
            SET cf.review_status='CLEAN'
            WHERE cf.org_id=#{orgId} AND cf.id=#{chargeId}
              AND cf.review_status='SUSPECTED_DUPLICATE'
              AND NOT
            """ + OPEN_CANDIDATE_EXISTS)
    int restoreCleanIfNoOpen(
            @Param("orgId") long orgId,
            @Param("chargeId") long chargeId);

    @Update("""
            UPDATE charge_fact cf
            SET cf.review_status='SUSPECTED_DUPLICATE'
            WHERE cf.org_id=#{orgId} AND cf.id=#{chargeId}
              AND cf.review_status='CLEAN'
              AND
            """ + OPEN_CANDIDATE_EXISTS)
    int markSuspectedIfOpenExists(
            @Param("orgId") long orgId,
            @Param("chargeId") long chargeId);
}
