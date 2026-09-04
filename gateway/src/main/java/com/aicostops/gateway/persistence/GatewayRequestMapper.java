package com.aicostops.gateway.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Gateway-owned durable request/route writes and dispatch-fence state
 * transitions. All methods execute off the Reactor Netty event loops on the
 * dedicated blocking-DB scheduler.
 */
@Mapper
public interface GatewayRequestMapper {

    @Select("""
            SELECT id, org_id, public_request_id, request_fingerprint, state, billing_period_id
            FROM gateway_request
            WHERE org_id=#{orgId} AND credential_id=#{credentialId} AND idempotency_key_digest=#{digest}
            """)
    ExistingRequestRow findByIdentity(
            @Param("orgId") long orgId,
            @Param("credentialId") long credentialId,
            @Param("digest") byte[] digest);

    @Select("""
            SELECT id FROM gateway_request WHERE public_request_id=#{publicRequestId} AND org_id=#{orgId}
            """)
    Long findByPublicRequestId(@Param("publicRequestId") String publicRequestId, @Param("orgId") long orgId);

    @Select("""
            SELECT id, org_id, public_request_id, request_fingerprint, state, billing_period_id
            FROM gateway_request WHERE id=#{requestId} AND org_id=#{orgId}
            """)
    ExistingRequestRow findById(@Param("requestId") long requestId, @Param("orgId") long orgId);

    @Select("""
            SELECT credential_id, public_request_id, state,
                   CONCAT(DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s'), 'Z') AS created_at,
                   CONCAT(DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s'), 'Z') AS updated_at
            FROM gateway_request
            WHERE public_request_id=#{publicRequestId} AND org_id=#{orgId}
            """)
    RequestStatusRow findRequestStatus(
            @Param("publicRequestId") String publicRequestId, @Param("orgId") long orgId);

    @Select("""
            SELECT status FROM billing_period WHERE id=#{periodId} AND org_id=#{orgId} FOR UPDATE
            """)
    String lockBillingPeriod(@Param("periodId") long periodId, @Param("orgId") long orgId);

    @Insert("""
            INSERT INTO gateway_request(
              org_id,public_request_id,credential_id,principal_type,organization_member_id,
              service_identity_id,project_id,financial_scope_type,financial_scope_id,
              logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,
              request_hmac_version,state,billing_period_id,current_route_attempt_id,
              current_usage_fact_id,created_at,validated_at,dispatch_intent_at,terminal_at,updated_at)
            VALUES (#{orgId},#{publicRequestId},#{credentialId},#{principalType},#{organizationMemberId},
              #{serviceIdentityId},#{projectId},#{financialScopeType},#{financialScopeId},
              #{logicalModelId},'CHAT_COMPLETIONS',#{idempotencyKeyDigest},#{requestFingerprint},
              1,'VALIDATED',NULL,NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL,UTC_TIMESTAMP(6))
            """)
    int insertRequest(GatewayRequestInsert insert);

    @Insert("""
            INSERT INTO gateway_route_attempt(
              org_id,request_id,attempt_no,route_decision_id,routing_policy_id,provider_account_id,
              provider_model_id,pricing_version_id,status,safety_reason_code,provider_request_id,
              created_at,dispatch_intent_at,completed_at)
            VALUES (#{orgId},#{requestId},1,#{routeDecisionId},NULL,#{providerAccountId},
              #{providerModelId},#{pricingVersionId},'PLANNED',NULL,NULL,
              UTC_TIMESTAMP(6),NULL,NULL)
            """)
    int insertRouteAttempt(RouteAttemptInsert insert);

    @Select("""
            SELECT id, route_decision_id, status FROM gateway_route_attempt
            WHERE org_id=#{orgId} AND request_id=#{requestId} AND attempt_no=1
            """)
    RouteAttemptRow findFirstAttempt(@Param("orgId") long orgId, @Param("requestId") long requestId);

    @Select("""
            SELECT id, route_decision_id, status FROM gateway_route_attempt
            WHERE org_id=#{orgId} AND id=#{attemptId}
            """)
    RouteAttemptRow findAttemptById(@Param("orgId") long orgId, @Param("attemptId") long attemptId);

    @Update("""
            UPDATE gateway_request
            SET state='DISPATCH_INTENT', billing_period_id=#{periodId},
                dispatch_intent_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId}
              AND state IN ('VALIDATED','RESERVED')
            """)
    int markRequestDispatchIntent(
            @Param("requestId") long requestId,
            @Param("orgId") long orgId,
            @Param("periodId") long periodId);

    @Update("""
            UPDATE gateway_route_attempt
            SET status='DISPATCH_INTENT', dispatch_intent_at=UTC_TIMESTAMP(6)
            WHERE id=#{attemptId} AND org_id=#{orgId} AND status='PLANNED'
            """)
    int markRouteAttemptDispatchIntent(
            @Param("attemptId") long attemptId, @Param("orgId") long orgId);

    @Update("""
            UPDATE gateway_request
            SET state='UPSTREAM_ACTIVE', updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId} AND state='DISPATCH_INTENT'
            """)
    int markRequestUpstreamActive(@Param("requestId") long requestId, @Param("orgId") long orgId);

    @Update("""
            UPDATE gateway_request
            SET state='TRANSPORT_COMPLETED', terminal_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId} AND state='UPSTREAM_ACTIVE'
            """)
    int markRequestTransportCompleted(@Param("requestId") long requestId, @Param("orgId") long orgId);

    @Update("""
            UPDATE gateway_request
            SET state='FAILED_AFTER_DISPATCH', terminal_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId}
              AND state IN ('DISPATCH_INTENT','UPSTREAM_ACTIVE')
            """)
    int markRequestFailedAfterDispatch(@Param("requestId") long requestId, @Param("orgId") long orgId);

    @Update("""
            UPDATE gateway_request
            SET state='CANCELED_AFTER_DISPATCH', terminal_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId}
              AND state IN ('DISPATCH_INTENT','UPSTREAM_ACTIVE')
            """)
    int markRequestCanceledAfterDispatch(@Param("requestId") long requestId, @Param("orgId") long orgId);

    @Update("""
            UPDATE gateway_request
            SET state='TIMED_OUT_AFTER_DISPATCH', terminal_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId}
              AND state IN ('DISPATCH_INTENT','UPSTREAM_ACTIVE')
            """)
    int markRequestTimedOutAfterDispatch(@Param("requestId") long requestId, @Param("orgId") long orgId);

    @Update("""
            UPDATE gateway_route_attempt
            SET status='BILLABLE_POSSIBLE'
            WHERE id=#{attemptId} AND org_id=#{orgId} AND status='DISPATCH_INTENT'
            """)
    int markAttemptBillablePossible(@Param("attemptId") long attemptId, @Param("orgId") long orgId);

    @Update("""
            UPDATE gateway_route_attempt
            SET status='COMPLETED', completed_at=UTC_TIMESTAMP(6)
            WHERE id=#{attemptId} AND org_id=#{orgId}
              AND status IN ('BILLABLE_POSSIBLE','DISPATCH_INTENT')
            """)
    int markAttemptCompleted(@Param("attemptId") long attemptId, @Param("orgId") long orgId);

    @Update("""
            UPDATE gateway_request
            SET current_route_attempt_id=#{attemptId}, updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{requestId} AND org_id=#{orgId}
            """)
    int updateCurrentRouteAttempt(
            @Param("requestId") long requestId,
            @Param("orgId") long orgId,
            @Param("attemptId") long attemptId);

    record ExistingRequestRow(
            long id,
            long orgId,
            String publicRequestId,
            byte[] requestFingerprint,
            String state,
            Long billingPeriodId) {
    }

    record RequestStatusRow(
            long credentialId,
            String publicRequestId,
            String state,
            String createdAt,
            String updatedAt) {
    }

    record RouteAttemptRow(long id, String routeDecisionId, String status) {
    }

    record GatewayRequestInsert(
            long orgId,
            String publicRequestId,
            long credentialId,
            String principalType,
            Long organizationMemberId,
            Long serviceIdentityId,
            long projectId,
            String financialScopeType,
            long financialScopeId,
            long logicalModelId,
            byte[] idempotencyKeyDigest,
            byte[] requestFingerprint) {
    }

    record RouteAttemptInsert(
            long orgId,
            long requestId,
            String routeDecisionId,
            long providerAccountId,
            long providerModelId,
            long pricingVersionId) {
    }
}