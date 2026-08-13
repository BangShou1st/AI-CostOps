package com.aicostops.organization.infrastructure;

import com.aicostops.organization.domain.Team;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TeamMapper {

    String AUTHORIZED_TEAM_PREDICATE = """
            t.org_id=#{organizationId}
              AND (
                #{organizationWide}=TRUE
                OR (
                  #{organizationWide}=FALSE
                  AND
                  <choose>
                    <when test="allowedTeamIds != null and !allowedTeamIds.isEmpty()">
                      t.id IN
                      <foreach item="teamId" collection="allowedTeamIds" open="(" separator="," close=")">
                        #{teamId}
                      </foreach>
                    </when>
                    <otherwise>1=0</otherwise>
                  </choose>
                )
              )
              AND (#{status} IS NULL OR t.status=#{status})
            """;

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM team t
            WHERE
            """ + AUTHORIZED_TEAM_PREDICATE + """
            </script>
            """)
    long countAuthorized(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("organizationWide") boolean organizationWide,
            @Param("allowedTeamIds") List<Long> allowedTeamIds);

    @Select("""
            <script>
            SELECT t.id,t.org_id,t.code,t.name,t.status,t.created_at,t.updated_at
            FROM team t
            WHERE
            """ + AUTHORIZED_TEAM_PREDICATE + """
            ORDER BY t.id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Team> findAuthorizedPage(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("organizationWide") boolean organizationWide,
            @Param("allowedTeamIds") List<Long> allowedTeamIds,
            @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT t.id,t.org_id,t.code,t.name,t.status,t.created_at,t.updated_at
            FROM team t
            WHERE
            """ + AUTHORIZED_TEAM_PREDICATE + """
              AND t.id=#{teamId}
            FOR UPDATE
            </script>
            """)
    Team findAuthorizedForUpdate(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("organizationWide") boolean organizationWide,
            @Param("allowedTeamIds") List<Long> allowedTeamIds,
            @Param("teamId") long teamId);

    @Insert("""
            INSERT INTO team(org_id,code,name,status,created_at,updated_at)
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
            FROM team WHERE id=#{teamId} AND org_id=#{organizationId}
            """)
    Team findCurrentOrganizationTeam(
            @Param("teamId") long teamId,
            @Param("organizationId") long organizationId);

    @Update("""
            UPDATE team
            SET name=#{name},status=#{status},updated_at=#{now}
            WHERE id=#{teamId} AND org_id=#{organizationId}
            """)
    int update(
            @Param("teamId") long teamId,
            @Param("organizationId") long organizationId,
            @Param("name") String name,
            @Param("status") String status,
            @Param("now") Instant now);
}
