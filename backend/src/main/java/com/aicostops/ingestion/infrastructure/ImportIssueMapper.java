package com.aicostops.ingestion.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ImportIssueMapper {

    @Insert("""
            <script>
            INSERT INTO import_issue(
                import_attempt_id,raw_provider_record_id,severity,issue_code,record_locator,
                field_name,message,raw_value_masked,created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.importAttemptId},#{row.rawProviderRecordId},#{row.severity},#{row.issueCode},
                 #{row.recordLocator},#{row.fieldName},#{row.message},#{row.rawValueMasked},#{row.now})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<IssueInsert> rows);

    record IssueInsert(
            long importAttemptId,
            Long rawProviderRecordId,
            String severity,
            String issueCode,
            String recordLocator,
            String fieldName,
            String message,
            String rawValueMasked,
            Instant now) {
    }
}
