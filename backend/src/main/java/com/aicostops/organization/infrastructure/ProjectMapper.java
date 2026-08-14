package com.aicostops.organization.infrastructure;

import com.aicostops.organization.domain.Project;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMapper {

    String AUTHORIZED_PROJECT_PREDICATE = """
            p.org_id=#{organizationId}
              AND (
                #{organizationWide}=TRUE
                OR (
                  #{organizationWide}=FALSE
                  AND
                  <choose>
                    <when test="allowedProjectIds != null and !allowedProjectIds.isEmpty()">
                      p.id IN
                      <foreach item="projectId" collection="allowedProjectIds" open="(" separator="," close=")">
                        #{projectId}
                      </foreach>
                    </when>
                    <otherwise>1=0</otherwise>
                  </choose>
                )
              )
              AND (#{status} IS NULL OR p.status=#{status})
            """;

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM project p
            WHERE
            """ + AUTHORIZED_PROJECT_PREDICATE + """
            </script>
            """)
    long countAuthorized(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("organizationWide") boolean organizationWide,
            @Param("allowedProjectIds") List<Long> allowedProjectIds);

    @Select("""
            <script>
            SELECT p.id,p.org_id,p.code,p.name,p.status,p.created_at,p.updated_at
            FROM project p
            WHERE
            """ + AUTHORIZED_PROJECT_PREDICATE + """
            ORDER BY p.id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Project> findAuthorizedPage(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("organizationWide") boolean organizationWide,
            @Param("allowedProjectIds") List<Long> allowedProjectIds,
            @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT p.id,p.org_id,p.code,p.name,p.status,p.created_at,p.updated_at
            FROM project p
            WHERE
            """ + AUTHORIZED_PROJECT_PREDICATE + """
              AND p.id=#{projectId}
            FOR UPDATE
            </script>
            """)
    Project findAuthorizedForUpdate(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("organizationWide") boolean organizationWide,
            @Param("allowedProjectIds") List<Long> allowedProjectIds,
            @Param("projectId") long projectId);

    @Insert("""
            INSERT INTO project(org_id,code,name,status,created_at,updated_at)
            VALUES (#{organizationId},#{code},#{name},'ACTIVE',#{now},#{now})
            """)
    int insert(
            @Param("organizationId") long organizationId,
            @Param("code") String code,
            @Param("name") String name,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT id,org_id,code,name,status,created_at,updated_at
            FROM project WHERE id=#{projectId} AND org_id=#{organizationId}
            """)
    Project findCurrentOrganizationProject(
            @Param("projectId") long projectId,
            @Param("organizationId") long organizationId);

    @Update("""
            UPDATE project
            SET name=#{name},status=#{status},updated_at=#{now}
            WHERE id=#{projectId} AND org_id=#{organizationId}
            """)
    int update(
            @Param("projectId") long projectId,
            @Param("organizationId") long organizationId,
            @Param("name") String name,
            @Param("status") String status,
            @Param("now") Instant now);
}
