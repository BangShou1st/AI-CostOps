package com.aicostops.organization.infrastructure;

import com.aicostops.organization.domain.ProjectMember;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMemberMapper {

    String MEMBER_PREDICATE = """
            p.id=#{projectId}
              AND p.org_id=#{organizationId}
              AND (#{status} IS NULL OR pm.status=#{status})
            """;

    @Select("""
            SELECT p.id
            FROM project p
            WHERE p.id=#{projectId} AND p.org_id=#{organizationId}
            """)
    Long findCurrentOrganizationProject(
            @Param("projectId") long projectId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT p.id
            FROM project p
            WHERE p.id=#{projectId} AND p.org_id=#{organizationId} AND p.status='ACTIVE'
            FOR UPDATE
            """)
    Long lockActiveProject(
            @Param("projectId") long projectId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT COUNT(*)
            FROM project_member pm
            JOIN project p ON p.id=pm.project_id
            JOIN organization_member om ON om.id=pm.org_member_id AND om.org_id=p.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE
            """ + MEMBER_PREDICATE)
    long countPage(
            @Param("projectId") long projectId,
            @Param("organizationId") long organizationId,
            @Param("status") String status);

    @Select("""
            SELECT pm.id,pm.project_id,pm.org_member_id AS organization_member_id,
                   u.id AS user_id,u.email_normalized AS email,u.display_name,u.status AS user_status,
                   pm.status,pm.joined_at
            FROM project_member pm
            JOIN project p ON p.id=pm.project_id
            JOIN organization_member om ON om.id=pm.org_member_id AND om.org_id=p.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE
            """ + MEMBER_PREDICATE + """
            ORDER BY pm.id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ProjectMember> findPage(
            @Param("projectId") long projectId,
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
            SELECT pm.org_member_id
            FROM project_member pm
            JOIN project p ON p.id=pm.project_id
            JOIN organization_member om ON om.id=pm.org_member_id AND om.org_id=p.org_id
            WHERE pm.id=#{projectMemberId} AND pm.project_id=#{projectId} AND p.org_id=#{organizationId}
            """)
    Long findMembershipTarget(
            @Param("projectMemberId") long projectMemberId,
            @Param("projectId") long projectId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT pm.id,pm.project_id,pm.org_member_id AS organization_member_id,
                   u.id AS user_id,u.email_normalized AS email,u.display_name,u.status AS user_status,
                   pm.status,pm.joined_at
            FROM project_member pm
            JOIN project p ON p.id=pm.project_id
            JOIN organization_member om ON om.id=pm.org_member_id AND om.org_id=p.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE pm.project_id=#{projectId} AND pm.org_member_id=#{organizationMemberId}
              AND p.org_id=#{organizationId}
            FOR UPDATE
            """)
    ProjectMember lockNaturalMembership(
            @Param("projectId") long projectId,
            @Param("organizationMemberId") long organizationMemberId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT pm.id,pm.project_id,pm.org_member_id AS organization_member_id,
                   u.id AS user_id,u.email_normalized AS email,u.display_name,u.status AS user_status,
                   pm.status,pm.joined_at
            FROM project_member pm
            JOIN project p ON p.id=pm.project_id
            JOIN organization_member om ON om.id=pm.org_member_id AND om.org_id=p.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE pm.id=#{projectMemberId} AND pm.project_id=#{projectId} AND p.org_id=#{organizationId}
            FOR UPDATE
            """)
    ProjectMember lockMembershipById(
            @Param("projectMemberId") long projectMemberId,
            @Param("projectId") long projectId,
            @Param("organizationId") long organizationId);

    @Insert("""
            INSERT INTO project_member(project_id,org_member_id,status,joined_at)
            VALUES (#{projectId},#{organizationMemberId},'ACTIVE',#{now})
            """)
    int insert(
            @Param("projectId") long projectId,
            @Param("organizationMemberId") long organizationMemberId,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE project_member
            SET status=#{status}, joined_at=#{joinedAt}
            WHERE id=#{projectMemberId} AND project_id=#{projectId}
            """)
    int transition(
            @Param("projectMemberId") long projectMemberId,
            @Param("projectId") long projectId,
            @Param("status") String status,
            @Param("joinedAt") Instant joinedAt);

    @Select("""
            SELECT pm.id,pm.project_id,pm.org_member_id AS organization_member_id,
                   u.id AS user_id,u.email_normalized AS email,u.display_name,u.status AS user_status,
                   pm.status,pm.joined_at
            FROM project_member pm
            JOIN project p ON p.id=pm.project_id
            JOIN organization_member om ON om.id=pm.org_member_id AND om.org_id=p.org_id
            JOIN app_user u ON u.id=om.user_id
            WHERE pm.id=#{projectMemberId} AND pm.project_id=#{projectId} AND p.org_id=#{organizationId}
            """)
    ProjectMember findById(
            @Param("projectMemberId") long projectMemberId,
            @Param("projectId") long projectId,
            @Param("organizationId") long organizationId);

    record TargetMember(long organizationMemberId, long userId, long securityVersion) {
    }
}
