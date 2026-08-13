package com.aicostops.audit.infrastructure;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
