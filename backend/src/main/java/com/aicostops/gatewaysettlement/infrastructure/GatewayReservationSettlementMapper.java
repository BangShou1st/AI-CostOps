package com.aicostops.gatewaysettlement.infrastructure;

import com.aicostops.gatewaysettlement.domain.GatewayReservation;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Narrow Backend reservation authority for Settlement. There are deliberately
 * no create, release, resize, retarget or replacement methods here.
 */
@Mapper
public interface GatewayReservationSettlementMapper {

    String RESERVATION_COLUMNS = """
            br.id,br.org_id,br.request_id,br.route_attempt_id,br.billing_period_id,br.budget_id,
            br.financial_scope_type,br.financial_scope_id,br.currency,br.reserved_amount,
            br.commitment_id,br.commitment_backed_amount,br.status,br.version,br.expires_at,
            br.finalized_at
            """;

    @Select("""
            SELECT
            """ + RESERVATION_COLUMNS + """
            FROM budget_reservation br
            WHERE br.org_id=#{organizationId} AND br.id=#{reservationId}
            """)
    GatewayReservation selectById(
            @Param("organizationId") long organizationId,
            @Param("reservationId") long reservationId);

    @Select("""
            SELECT
            """ + RESERVATION_COLUMNS + """
            FROM budget_reservation br
            WHERE br.org_id=#{organizationId} AND br.id=#{reservationId}
            FOR UPDATE
            """)
    GatewayReservation selectByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("reservationId") long reservationId);

    @Update("""
            UPDATE budget_reservation
            SET status='FINALIZED',finalized_at=#{now},version=version+1,updated_at=#{now}
            WHERE org_id=#{organizationId} AND id=#{reservationId}
              AND version=#{expectedVersion}
              AND status IN ('ACTIVE','PENDING_HOLD')
            """)
    int finalizeForSettlement(
            @Param("organizationId") long organizationId,
            @Param("reservationId") long reservationId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);
}
