package com.aicostops.organization.infrastructure;

import com.aicostops.organization.domain.CostCenter;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CostCenterMapper {

    String AUTHORIZED_COST_CENTER_PREDICATE = """
            cc.org_id=#{organizationId}
              AND (
                #{scope.organizationWide}=TRUE
                OR (
                  #{scope.organizationWide}=FALSE
                  AND
                  <choose>
                    <when test="scope.allowedCostCenterIds != null and !scope.allowedCostCenterIds.isEmpty()">
                      cc.id IN
                      <foreach item="costCenterId" collection="scope.allowedCostCenterIds" open="(" separator="," close=")">
                        #{costCenterId}
                      </foreach>
                    </when>
                    <otherwise>1=0</otherwise>
                  </choose>
                )
              )
              AND (#{status} IS NULL OR cc.status=#{status})
            """;

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM cost_center cc
            WHERE
            """ + AUTHORIZED_COST_CENTER_PREDICATE + """
            </script>
            """)
    long countAuthorized(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("scope") ScopeParameters scope);

    @Select("""
            <script>
            SELECT cc.id,cc.org_id,cc.code,cc.name,cc.status,cc.created_at,cc.updated_at
            FROM cost_center cc
            WHERE
            """ + AUTHORIZED_COST_CENTER_PREDICATE + """
            ORDER BY cc.id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<CostCenter> findAuthorizedPage(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("scope") ScopeParameters scope,
            @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT cc.id,cc.org_id,cc.code,cc.name,cc.status,cc.created_at,cc.updated_at
            FROM cost_center cc
            WHERE
            """ + AUTHORIZED_COST_CENTER_PREDICATE + """
              AND cc.id=#{costCenterId}
            FOR UPDATE
            </script>
            """)
    CostCenter findAuthorizedForUpdate(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("scope") ScopeParameters scope,
            @Param("costCenterId") long costCenterId);

    @Insert("""
            INSERT INTO cost_center(org_id,code,name,status,created_at,updated_at)
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
            FROM cost_center WHERE id=#{costCenterId} AND org_id=#{organizationId}
            """)
    CostCenter findCurrentOrganizationCostCenter(
            @Param("costCenterId") long costCenterId,
            @Param("organizationId") long organizationId);

    @Update("""
            <script>
            UPDATE cost_center cc
            SET cc.name=#{name},cc.status=#{newStatus},cc.updated_at=#{now}
            WHERE
            """ + AUTHORIZED_COST_CENTER_PREDICATE + """
              AND cc.id=#{costCenterId}
            </script>
            """)
    int updateAuthorized(
            @Param("costCenterId") long costCenterId,
            @Param("organizationId") long organizationId,
            @Param("status") String currentStatusFilter,
            @Param("scope") ScopeParameters scope,
            @Param("name") String name,
            @Param("newStatus") String newStatus,
            @Param("now") Instant now);

    record ScopeParameters(boolean organizationWide, List<Long> allowedCostCenterIds) {

        public ScopeParameters {
            allowedCostCenterIds = List.copyOf(allowedCostCenterIds);
        }
    }
}
