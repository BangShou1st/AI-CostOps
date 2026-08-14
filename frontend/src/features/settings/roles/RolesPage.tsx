import { useQuery } from '@tanstack/react-query'
import { Alert, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { toProblemDetail } from '../../../api/problem'
import { settingsApi } from '../api/settingsApi'
import { settingsKeys } from '../api/settingsKeys'
import type { Permission, Role } from '../api/settingsTypes'
import { ROLE_SCOPE_APPLICABILITY } from '../permissions'
import { roleLabel } from '../presentation'

const SCOPE_NAMES: Record<string, string> = {
  ORG: '组织',
  PROJECT: '项目',
  TEAM: '团队',
  COST_CENTER: '成本中心',
}

export function RolesPage() {
  const rolesQuery = useQuery({ queryKey: settingsKeys.roles(), queryFn: () => settingsApi.listRoles() })
  const permissionsQuery = useQuery({ queryKey: settingsKeys.permissions(), queryFn: () => settingsApi.listPermissions() })

  const roleColumns: TableProps<Role>['columns'] = [
    { title: '编码', dataIndex: 'code', key: 'code', width: 180 },
    { title: '名称', key: 'name', render: (_, role) => roleLabel(role.code) },
    {
      title: '有效范围', key: 'scopes',
      render: (_, role) => (ROLE_SCOPE_APPLICABILITY[role.code] ?? []).map((scope) => SCOPE_NAMES[scope] ?? scope).join('、') || '—',
    },
    {
      title: '权限', key: 'permissions',
      render: (_, role) => role.permissions.map((permission) => permission.code).join('、') || '—',
    },
  ]

  const permissionColumns: TableProps<Permission>['columns'] = [
    { title: '编码', dataIndex: 'code', key: 'code', width: 240 },
    { title: '名称', dataIndex: 'name', key: 'name' },
  ]

  return (
    <main className="settings-page">
      <div>
        <h1>角色与权限</h1>
        <Typography.Text type="secondary">组织的只读角色与权限目录。</Typography.Text>
      </div>

      <Typography.Title level={4} style={{ marginTop: 24 }}>角色</Typography.Title>
      {rolesQuery.isLoading && <div role="status">正在加载角色…</div>}
      {rolesQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(rolesQuery.error).detail || toProblemDetail(rolesQuery.error).title} showIcon />
      )}
      {rolesQuery.data && rolesQuery.data.length === 0 && <div className="settings-empty">尚未定义角色。</div>}
      {rolesQuery.data && rolesQuery.data.length > 0 && (
        <Table<Role> rowKey="id" columns={roleColumns} dataSource={rolesQuery.data} pagination={false} scroll={{ x: 720 }} />
      )}

      <Typography.Title level={4} style={{ marginTop: 24 }}>权限</Typography.Title>
      {permissionsQuery.isLoading && <div role="status">正在加载权限…</div>}
      {permissionsQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(permissionsQuery.error).detail || toProblemDetail(permissionsQuery.error).title} showIcon />
      )}
      {permissionsQuery.data && permissionsQuery.data.length === 0 && <div className="settings-empty">尚未定义权限。</div>}
      {permissionsQuery.data && permissionsQuery.data.length > 0 && (
        <Table<Permission> rowKey="id" columns={permissionColumns} dataSource={permissionsQuery.data} pagination={false} scroll={{ x: 480 }} />
      )}
    </main>
  )
}
