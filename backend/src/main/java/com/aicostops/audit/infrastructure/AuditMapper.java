package com.aicostops.audit.infrastructure;

import com.aicostops.audit.application.AuditEventView;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Row access for {@code audit_event}: append-only inserts and read queries. */
@Mapper
public interface AuditMapper {

    @Insert("""
            INSERT INTO audit_event(org_id,actor_user_id,event_type,subject_type,subject_id,metadata_json,created_at)
            VALUES (#{organizationId},#{actorUserId},#{eventType},#{subjectType},#{subjectId},#{metadataJson},#{createdAt})
            """)
    int insert(
            @Param("organizationId") Long organizationId,
            @Param("actorUserId") Long actorUserId,
            @Param("eventType") String eventType,
            @Param("subjectType") String subjectType,
            @Param("subjectId") Long subjectId,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") Instant createdAt);

    String AUDIT_COLUMNS = """
            a.id,a.org_id,a.actor_user_id,a.event_type,a.subject_type,a.subject_id,
            a.metadata_json,a.created_at
            """;

    /**
     * Stable newest-first page: created_at DESC with id DESC as the
     * tie-breaker, matching the shared list conventions.
     */
    @Select("""
            <script>
            SELECT
            """ + AUDIT_COLUMNS + """
            FROM audit_event a
            WHERE a.org_id=#{organizationId}
            <if test="eventType != null">
              AND a.event_type=#{eventType}
            </if>
            <if test="from != null">
              AND a.created_at &gt;= #{from}
            </if>
            <if test="to != null">
              AND a.created_at &lt;= #{to}
            </if>
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditEventView> selectPage(
            @Param("organizationId") long organizationId,
            @Param("eventType") String eventType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM audit_event a
            WHERE a.org_id=#{organizationId}
            <if test="eventType != null">
              AND a.event_type=#{eventType}
            </if>
            <if test="from != null">
              AND a.created_at &gt;= #{from}
            </if>
            <if test="to != null">
              AND a.created_at &lt;= #{to}
            </if>
            </script>
            """)
    long count(
            @Param("organizationId") long organizationId,
            @Param("eventType") String eventType,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
