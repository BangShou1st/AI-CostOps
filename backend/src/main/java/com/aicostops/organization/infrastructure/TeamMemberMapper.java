package com.aicostops.organization.infrastructure;

import com.aicostops.organization.domain.TeamMember;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TeamMemberMapper {

    String MEMBER_PREDICATE = """
            t.id=#{teamId}
              AND t.org_id=#{organizationId}
              AND (#{status} IS NULL OR tm.status=#{status})
            """;

    @Select("""
            SELECT t.id
            FROM team t
            WHERE t.id=#{teamId} AND t.org_id=#{organizationId}
            """)
    Long findCurrentOrganizationTeam(
            @Param("teamId") long teamId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT t.id
            FROM team t
            WHERE t.id=#{teamId} AND t.org_id=#{organizationId} AND t.status='ACTIVE'
            FOR UPDATE
            """)
    Long lockActiveTeam(
            @Param("teamId") long teamId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT COUNT(*)
            FROM team_member tm
            JOIN team t ON t.id=tm.team_id
            JOIN organization_member om ON om.id=tm.org_member_id AND om.org_id=t.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE
            """ + MEMBER_PREDICATE)
    long countPage(
            @Param("teamId") long teamId,
            @Param("organizationId") long organizationId,
            @Param("status") String status);

    @Select("""
            SELECT tm.id,tm.team_id,tm.org_member_id AS organization_member_id,
                   u.id AS user_id,u.email_normalized AS email,u.display_name,u.status AS user_status,
                   tm.status,tm.joined_at
            FROM team_member tm
            JOIN team t ON t.id=tm.team_id
            JOIN organization_member om ON om.id=tm.org_member_id AND om.org_id=t.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE
            """ + MEMBER_PREDICATE + """
            ORDER BY tm.id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<TeamMember> findPage(
            @Param("teamId") long teamId,
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("""
            SELECT om.id AS organization_member_id,om.user_id,u.security_version
            FROM organization_member om
            JOIN app_user u ON u.id=om.user_id AND u.status='ACTIVE'
            WHERE om.id=#{organizationMemberId} AND om.org_id=#{organizationId} AND om.status='ACTIVE'
            FOR UPDATE
            """)
    TargetMember lockActiveTarget(
            @Param("organizationMemberId") long organizationMemberId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT tm.org_member_id
            FROM team_member tm
            JOIN team t ON t.id=tm.team_id
            JOIN organization_member om ON om.id=tm.org_member_id AND om.org_id=t.org_id
            WHERE tm.id=#{teamMemberId} AND tm.team_id=#{teamId} AND t.org_id=#{organizationId}
            """)
    Long findMembershipTarget(
            @Param("teamMemberId") long teamMemberId,
            @Param("teamId") long teamId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT tm.id,tm.team_id,tm.org_member_id AS organization_member_id,
                   u.id AS user_id,u.email_normalized AS email,u.display_name,u.status AS user_status,
                   tm.status,tm.joined_at
            FROM team_member tm
            JOIN team t ON t.id=tm.team_id
            JOIN organization_member om ON om.id=tm.org_member_id AND om.org_id=t.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE tm.team_id=#{teamId} AND tm.org_member_id=#{organizationMemberId}
              AND t.org_id=#{organizationId}
            FOR UPDATE
            """)
    TeamMember lockNaturalMembership(
            @Param("teamId") long teamId,
            @Param("organizationMemberId") long organizationMemberId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT tm.id,tm.team_id,tm.org_member_id AS organization_member_id,
                   u.id AS user_id,u.email_normalized AS email,u.display_name,u.status AS user_status,
                   tm.status,tm.joined_at
            FROM team_member tm
            JOIN team t ON t.id=tm.team_id
            JOIN organization_member om ON om.id=tm.org_member_id AND om.org_id=t.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE tm.id=#{teamMemberId} AND tm.team_id=#{teamId} AND t.org_id=#{organizationId}
            FOR UPDATE
            """)
    TeamMember lockMembershipById(
            @Param("teamMemberId") long teamMemberId,
            @Param("teamId") long teamId,
            @Param("organizationId") long organizationId);

    @Insert("""
            INSERT INTO team_member(team_id,org_member_id,status,joined_at)
            VALUES (#{teamId},#{organizationMemberId},'ACTIVE',#{now})
            """)
    int insert(
            @Param("teamId") long teamId,
            @Param("organizationMemberId") long organizationMemberId,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE team_member
            SET status=#{status}, joined_at=#{joinedAt}
            WHERE id=#{teamMemberId} AND team_id=#{teamId}
            """)
    int transition(
            @Param("teamMemberId") long teamMemberId,
            @Param("teamId") long teamId,
            @Param("status") String status,
            @Param("joinedAt") Instant joinedAt);

    @Select("""
            SELECT tm.id,tm.team_id,tm.org_member_id AS organization_member_id,
                   u.id AS user_id,u.email_normalized AS email,u.display_name,u.status AS user_status,
                   tm.status,tm.joined_at
            FROM team_member tm
            JOIN team t ON t.id=tm.team_id
            JOIN organization_member om ON om.id=tm.org_member_id AND om.org_id=t.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE tm.id=#{teamMemberId} AND tm.team_id=#{teamId} AND t.org_id=#{organizationId}
            """)
    TeamMember findById(
            @Param("teamMemberId") long teamMemberId,
            @Param("teamId") long teamId,
            @Param("organizationId") long organizationId);

    record TargetMember(long organizationMemberId, long userId, long securityVersion) {
    }
}
