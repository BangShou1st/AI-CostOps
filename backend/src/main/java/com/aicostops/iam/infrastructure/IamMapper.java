package com.aicostops.iam.infrastructure;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IamMapper {

    @Select("SELECT id FROM organization WHERE slug=#{slug} AND status='ACTIVE'")
    Long findActiveOrganizationIdBySlug(String slug);

    @Select("SELECT COUNT(*) FROM app_user WHERE email_normalized=#{email}")
    int countUsersByNormalizedEmail(String email);

    @Insert("""
            INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
            VALUES (#{email},#{displayName},'ACTIVE',0,#{now},#{now})
            """)
    int insertUser(
            @Param("email") String email,
            @Param("displayName") String displayName,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Insert("""
            INSERT INTO user_credential(user_id,password_hash,password_changed_at,updated_at)
            VALUES (#{userId},#{passwordHash},#{now},#{now})
            """)
    int insertCredential(
            @Param("userId") long userId,
            @Param("passwordHash") String passwordHash,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO organization_member(org_id,user_id,status,joined_at)
            VALUES (#{organizationId},#{userId},'ACTIVE',#{now})
            """)
    int insertOrganizationMember(
            @Param("organizationId") long organizationId,
            @Param("userId") long userId,
            @Param("now") Instant now);

    @Select("SELECT id FROM `role` WHERE code=#{code}")
    Long findRoleIdByCode(String code);

    @Insert("""
            INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
            VALUES (#{memberId},#{roleId},'ORG',#{organizationId},NULL,#{now})
            """)
    int insertOrganizationRoleAssignment(
            @Param("memberId") long memberId,
            @Param("roleId") long roleId,
            @Param("organizationId") long organizationId,
            @Param("now") Instant now);

    @Select("""
            SELECT i.id, i.org_id, i.email_normalized, i.initial_role_code, i.status,
                   i.expires_at, o.status AS organization_status
            FROM invitation i JOIN organization o ON o.id=i.org_id
            WHERE i.token_hash=#{tokenHash}
            FOR UPDATE
            """)
    InvitationRecord findInvitationForUpdate(String tokenHash);

    @org.apache.ibatis.annotations.Update("""
            UPDATE invitation
            SET status='ACCEPTED', accepted_by_user_id=#{userId}, accepted_at=#{now}
            WHERE id=#{invitationId} AND status='PENDING'
            """)
    int markInvitationAccepted(
            @Param("invitationId") long invitationId,
            @Param("userId") long userId,
            @Param("now") Instant now);
}
