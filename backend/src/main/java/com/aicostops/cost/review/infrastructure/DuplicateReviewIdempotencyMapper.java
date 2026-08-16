package com.aicostops.cost.review.infrastructure;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Persistence for duplicate review command idempotency against the existing
 * {@code api_idempotency} table. The natural key stores the 64-char SHA-256
 * fingerprint of the exact raw Idempotency-Key header.
 */
@Mapper
public interface DuplicateReviewIdempotencyMapper {

    @Insert("""
            INSERT INTO api_idempotency(
                org_id,actor_member_id,operation,idempotency_key,request_hash,
                response_status,response_body,created_at,expires_at)
            VALUES (
                #{orgId},#{actorMemberId},#{operation},#{keyFingerprint},#{requestHash},
                0,JSON_OBJECT('state','PROVISIONAL'),#{now},NULL)
            """)
    int insertProvisional(
            @Param("orgId") long orgId,
            @Param("actorMemberId") long actorMemberId,
            @Param("operation") String operation,
            @Param("keyFingerprint") String keyFingerprint,
            @Param("requestHash") String requestHash,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    /** Locking current read for the concurrent duplicate-key path only. */
    @Select("""
            SELECT id,org_id,actor_member_id,operation,idempotency_key,request_hash,
                   response_status,response_body,created_at,expires_at
            FROM api_idempotency
            WHERE org_id=#{orgId} AND actor_member_id=#{actorMemberId}
              AND operation=#{operation} AND idempotency_key=#{keyFingerprint}
            FOR UPDATE
            """)
    IdempotencyRow findByNaturalKeyForUpdate(
            @Param("orgId") long orgId,
            @Param("actorMemberId") long actorMemberId,
            @Param("operation") String operation,
            @Param("keyFingerprint") String keyFingerprint);

    @Update("""
            UPDATE api_idempotency
            SET response_status=#{responseStatus}, response_body=CAST(#{responseBody} AS JSON)
            WHERE id=#{id}
            """)
    int finalize(
            @Param("id") long id,
            @Param("responseStatus") int responseStatus,
            @Param("responseBody") String responseBody);

    record IdempotencyRow(
            long id,
            long orgId,
            long actorMemberId,
            String operation,
            String idempotencyKey,
            String requestHash,
            int responseStatus,
            String responseBody,
            Instant createdAt,
            Instant expiresAt) {
    }
}
