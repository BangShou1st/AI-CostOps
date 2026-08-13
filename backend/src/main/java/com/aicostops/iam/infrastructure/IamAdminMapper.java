package com.aicostops.iam.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IamAdminMapper {

    @Select("""
            SELECT u.id
            FROM organization_member m
            JOIN app_user u ON u.id=m.user_id
            WHERE m.org_id=#{organizationId}
            ORDER BY u.id
            LIMIT #{size} OFFSET #{offset}
            """)
    List<Long> findUserPageIds(
            @Param("organizationId") long organizationId,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("""
            SELECT COUNT(*)
            FROM organization_member m
            JOIN app_user u ON u.id=m.user_id
            WHERE m.org_id=#{organizationId}
            """)
    long countUsers(@Param("organizationId") long organizationId);

    @Select("""
            <script>
            SELECT u.id, u.email_normalized AS email, u.display_name, u.status, u.security_version,
                   m.id AS organization_member_id, m.status AS organization_member_status,
                   m.employee_no, m.default_cost_center_id
            FROM organization_member m
            JOIN app_user u ON u.id=m.user_id
            WHERE m.org_id=#{organizationId}
              AND u.id IN
              <foreach item="userId" collection="userIds" open="(" separator="," close=")">
                #{userId}
              </foreach>
            ORDER BY u.id
            </script>
            """)
    List<UserRow> findUsers(
            @Param("organizationId") long organizationId,
            @Param("userIds") List<Long> userIds);

    @Select("""
            <script>
            SELECT m.user_id, ra.id, r.id AS role_id, r.code AS role_code, r.name AS role_name,
                   ra.scope_type, ra.scope_id, ra.created_at
            FROM organization_member m
            JOIN role_assignment ra ON ra.org_member_id=m.id
            JOIN `role` r ON r.id=ra.role_id
            WHERE m.org_id=#{organizationId}
              AND m.user_id IN
              <foreach item="userId" collection="userIds" open="(" separator="," close=")">
                #{userId}
              </foreach>
            ORDER BY m.user_id, ra.id
            </script>
            """)
    List<RoleAssignmentRow> findRoleAssignments(
            @Param("organizationId") long organizationId,
            @Param("userIds") List<Long> userIds);

    @Select("SELECT id, code, name FROM `role` ORDER BY id")
    List<RoleRow> findRoles();

    @Select("""
            SELECT rp.role_id, p.id AS permission_id, p.code AS permission_code, p.name AS permission_name
            FROM role_permission rp
            JOIN permission p ON p.id=rp.permission_id
            ORDER BY rp.role_id, p.id
            """)
    List<RolePermissionRow> findRolePermissions();

    @Select("SELECT id, code, name FROM permission ORDER BY id")
    List<PermissionRow> findPermissions();

    @Select("""
            SELECT u.id, u.email_normalized AS email, u.display_name, u.status, u.security_version,
                   m.id AS organization_member_id, m.status AS organization_member_status,
                   m.employee_no, m.default_cost_center_id
            FROM organization_member m
            JOIN app_user u ON u.id=m.user_id
            WHERE m.org_id=#{organizationId} AND u.id=#{userId}
            FOR UPDATE
            """)
    UserRow findUserForUpdate(
            @Param("organizationId") long organizationId,
            @Param("userId") long userId);

    @Select("""
            SELECT u.id
            FROM app_user u
            JOIN organization_member m ON m.user_id=u.id
            JOIN organization o ON o.id=m.org_id
            WHERE u.id=#{userId} AND u.security_version=#{securityVersion} AND u.status='ACTIVE'
              AND m.id=#{organizationMemberId} AND m.org_id=#{organizationId} AND m.status='ACTIVE'
              AND o.status='ACTIVE'
            FOR UPDATE
            """)
    Long lockActiveActor(
            @Param("userId") long userId,
            @Param("securityVersion") long securityVersion,
            @Param("organizationMemberId") long organizationMemberId,
            @Param("organizationId") long organizationId);

    @Update("UPDATE app_user SET status=#{status}, updated_at=#{now} WHERE id=#{userId}")
    int updateUserStatus(
            @Param("userId") long userId,
            @Param("status") String status,
            @Param("now") Instant now);

    @Select("""
            SELECT m.id AS organization_member_id, m.user_id, u.security_version
            FROM organization_member m
            JOIN app_user u ON u.id=m.user_id
            JOIN organization o ON o.id=m.org_id AND o.status='ACTIVE'
            WHERE m.id=#{organizationMemberId} AND m.org_id=#{organizationId} AND m.status='ACTIVE'
            FOR UPDATE
            """)
    TargetMemberRow findActiveTargetMemberForUpdate(
            @Param("organizationMemberId") long organizationMemberId,
            @Param("organizationId") long organizationId);

    @Select("SELECT id,code,name FROM `role` WHERE id=#{roleId}")
    RoleRow findRole(long roleId);

    @Select("""
            SELECT id FROM role_assignment
            WHERE org_member_id=#{organizationMemberId} AND role_id=#{roleId}
              AND scope_type=#{scopeType} AND scope_id=#{scopeId}
            """)
    Long findRoleAssignmentId(
            @Param("organizationMemberId") long organizationMemberId,
            @Param("roleId") long roleId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") long scopeId);

    @Insert("""
            INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
            VALUES (#{organizationMemberId},#{roleId},#{scopeType},#{scopeId},#{assignedBy},#{createdAt})
            """)
    int insertRoleAssignment(
            @Param("organizationMemberId") long organizationMemberId,
            @Param("roleId") long roleId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") long scopeId,
            @Param("assignedBy") long assignedBy,
            @Param("createdAt") Instant createdAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT ra.id, ra.org_member_id AS organization_member_id, m.user_id, u.security_version,
                   r.code AS role_code, r.name AS role_name, ra.role_id, ra.scope_type, ra.scope_id, ra.created_at
            FROM role_assignment ra
            JOIN organization_member m ON m.id=ra.org_member_id
            JOIN app_user u ON u.id=m.user_id
            JOIN `role` r ON r.id=ra.role_id
            WHERE ra.id=#{assignmentId} AND m.org_id=#{organizationId}
            FOR UPDATE
            """)
    LockedRoleAssignmentRow findRoleAssignmentForUpdate(
            @Param("assignmentId") long assignmentId,
            @Param("organizationId") long organizationId);

    @Delete("DELETE FROM role_assignment WHERE id=#{assignmentId}")
    int deleteRoleAssignment(long assignmentId);

    record UserRow(
            long id,
            String email,
            String displayName,
            String status,
            long securityVersion,
            long organizationMemberId,
            String organizationMemberStatus,
            String employeeNo,
            Long defaultCostCenterId) {
    }

    record RoleAssignmentRow(
            long userId,
            long id,
            long roleId,
            String roleCode,
            String roleName,
            String scopeType,
            long scopeId,
            Instant createdAt) {
    }

    record RoleRow(long id, String code, String name) {
    }

    record RolePermissionRow(
            long roleId,
            long permissionId,
            String permissionCode,
            String permissionName) {
    }

    record PermissionRow(long id, String code, String name) {
    }

    record TargetMemberRow(long organizationMemberId, long userId, long securityVersion) {
    }

    record LockedRoleAssignmentRow(
            long id,
            long organizationMemberId,
            long userId,
            long securityVersion,
            String roleCode,
            String roleName,
            long roleId,
            String scopeType,
            long scopeId,
            Instant createdAt) {
    }
}
