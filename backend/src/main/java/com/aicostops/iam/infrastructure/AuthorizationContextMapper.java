package com.aicostops.iam.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthorizationContextMapper {

    @Select("""
            SELECT u.id AS user_id, u.security_version, m.id AS organization_member_id,
                   m.org_id AS organization_id
            FROM app_user u
            JOIN organization_member m ON m.user_id=u.id AND m.status='ACTIVE'
            JOIN organization o ON o.id=m.org_id AND o.status='ACTIVE'
            WHERE u.id=#{userId} AND u.status='ACTIVE'
              AND (SELECT COUNT(*)
                   FROM organization_member active_member
                   JOIN organization active_organization
                     ON active_organization.id=active_member.org_id
                    AND active_organization.status='ACTIVE'
                   WHERE active_member.user_id=u.id AND active_member.status='ACTIVE') = 1
            """)
    AuthorizationIdentityRecord findIdentity(long userId);

    @Select("""
            SELECT r.code AS role_code, p.code AS permission_code, ra.scope_type, ra.scope_id
            FROM role_assignment ra
            JOIN `role` r ON r.id=ra.role_id
            JOIN role_permission rp ON rp.role_id=r.id
            JOIN permission p ON p.id=rp.permission_id
            WHERE ra.org_member_id=#{organizationMemberId}
            """)
    List<ScopedPermissionGrantRecord> findGrants(long organizationMemberId);
}
