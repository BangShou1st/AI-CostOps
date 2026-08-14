package com.aicostops.ingestion.infrastructure;

import com.aicostops.ingestion.domain.ImportIssue;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ImportIssueMapper {

    @Insert("""
            INSERT INTO import_issue(
                import_attempt_id,raw_provider_record_id,severity,issue_code,record_locator,
                field_name,message,raw_value_masked,created_at)
            VALUES (
                #{importAttemptId},#{rawProviderRecordId},#{severity},#{issueCode},#{recordLocator},
                #{fieldName},#{message},#{rawValueMasked},#{now})
            """)
    int insert(
            @Param("importAttemptId") long importAttemptId,
            @Param("rawProviderRecordId") Long rawProviderRecordId,
            @Param("severity") String severity,
            @Param("issueCode") String issueCode,
            @Param("recordLocator") String recordLocator,
            @Param("fieldName") String fieldName,
            @Param("message") String message,
            @Param("rawValueMasked") String rawValueMasked,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();
}
