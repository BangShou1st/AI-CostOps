package com.aicostops.ingestion.infrastructure;

import com.aicostops.ingestion.domain.RawProviderRecord;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RawProviderRecordMapper {

    @Insert("""
            INSERT INTO raw_provider_record(
                import_attempt_id,record_index,record_locator,provider_record_key,
                raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
            VALUES (
                #{importAttemptId},#{recordIndex},#{recordLocator},#{providerRecordKey},
                CAST(#{rawPayload} AS JSON),
                CAST(#{normalizedPayload} AS JSON),
                #{usageStart},#{usageEnd},#{normalizeStatus},#{now})
            """)
    int insert(
            @Param("importAttemptId") long importAttemptId,
            @Param("recordIndex") long recordIndex,
            @Param("recordLocator") String recordLocator,
            @Param("providerRecordKey") String providerRecordKey,
            @Param("rawPayload") String rawPayload,
            @Param("normalizedPayload") String normalizedPayload,
            @Param("usageStart") Instant usageStart,
            @Param("usageEnd") Instant usageEnd,
            @Param("normalizeStatus") String normalizeStatus,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();
}
