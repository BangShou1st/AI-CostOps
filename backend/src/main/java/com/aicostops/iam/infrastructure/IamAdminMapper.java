package com.aicostops.iam.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
