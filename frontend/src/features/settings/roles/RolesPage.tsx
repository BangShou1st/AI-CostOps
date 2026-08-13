import { useQuery } from '@tanstack/react-query'
import { Alert, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { toProblemDetail } from '../../../api/problem'
import { settingsApi } from '../api/settingsApi'
import { settingsKeys } from '../api/settingsKeys'
import type { Permission, Role } from '../api/settingsTypes'
import { ROLE_SCOPE_APPLICABILITY } from '../permissions'

export function RolesPage() {
  const rolesQuery = useQuery({ queryKey: settingsKeys.roles(), queryFn: () => settingsApi.listRoles() })
  const permissionsQuery = useQuery({ queryKey: settingsKeys.permissions(), queryFn: () => settingsApi.listPermissions() })

  const roleColumns: TableProps<Role>['columns'] = [
    { title: 'Code', dataIndex: 'code', key: 'code' },
    { title: 'Name', dataIndex: 'name', key: 'name' },
    {
      title: 'Valid scopes', key: 'scopes',
      render: (_, role) => ROLE_SCOPE_APPLICABILITY[role.code]?.join(', ') ?? '—',
    },
    {
      title: 'Permissions', key: 'permissions',
      render: (_, role) => role.permissions.map((permission) => permission.code).join(', ') || '—',
    },
  ]

  const permissionColumns: TableProps<Permission>['columns'] = [
    { title: 'Code', dataIndex: 'code', key: 'code' },
    { title: 'Name', dataIndex: 'name', key: 'name' },
  ]

  return (
    <main className="settings-page">
      <div>
        <h1>Roles</h1>
        <Typography.Text type="secondary">Read-only Role and Permission catalog for the organization.</Typography.Text>
      </div>

      <Typography.Title level={4} style={{ marginTop: 24 }}>Roles</Typography.Title>
      {rolesQuery.isLoading && <div role="status">Loading roles…</div>}
      {rolesQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(rolesQuery.error).detail || toProblemDetail(rolesQuery.error).title} showIcon />
      )}
      {rolesQuery.data && rolesQuery.data.length === 0 && <div className="settings-empty">No roles are defined.</div>}
      {rolesQuery.data && rolesQuery.data.length > 0 && (
        <Table<Role> rowKey="id" columns={roleColumns} dataSource={rolesQuery.data} pagination={false} />
      )}

      <Typography.Title level={4} style={{ marginTop: 24 }}>Permissions</Typography.Title>
      {permissionsQuery.isLoading && <div role="status">Loading permissions…</div>}
      {permissionsQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(permissionsQuery.error).detail || toProblemDetail(permissionsQuery.error).title} showIcon />
      )}
      {permissionsQuery.data && permissionsQuery.data.length === 0 && <div className="settings-empty">No permissions are defined.</div>}
      {permissionsQuery.data && permissionsQuery.data.length > 0 && (
        <Table<Permission> rowKey="id" columns={permissionColumns} dataSource={permissionsQuery.data} pagination={false} />
      )}
    </main>
  )
}
