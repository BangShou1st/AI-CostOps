package com.aicostops.ingestion.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RawProviderRecordMapper {

    @Insert("""
            <script>
            INSERT INTO raw_provider_record(
                import_attempt_id,record_index,record_locator,provider_record_key,
                raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.importAttemptId},#{row.recordIndex},#{row.recordLocator},#{row.providerRecordKey},
                 CAST(#{row.rawPayload} AS JSON),CAST(#{row.normalizedPayload} AS JSON),
                 #{row.usageStart},#{row.usageEnd},#{row.normalizeStatus},#{row.now})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<RawRecordInsert> rows);

    @Select("""
            SELECT id,record_index
            FROM raw_provider_record
            WHERE import_attempt_id=#{attemptId}
              AND record_index BETWEEN #{fromIndex} AND #{toIndex}
            """)
    List<RawRecordIdRow> findIdsByIndexRange(
            @Param("attemptId") long attemptId,
            @Param("fromIndex") long fromIndex,
            @Param("toIndex") long toIndex);

    record RawRecordInsert(
            long importAttemptId,
            long recordIndex,
            String recordLocator,
            String providerRecordKey,
            String rawPayload,
            String normalizedPayload,
            Instant usageStart,
            Instant usageEnd,
            String normalizeStatus,
            Instant now) {
    }

    record RawRecordIdRow(long id, long recordIndex) {
    }
}
