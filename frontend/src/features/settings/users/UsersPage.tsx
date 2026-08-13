import { useQueryClient } from '@tanstack/react-query'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Drawer, Input, InputNumber, Modal, Select, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../../api/problem'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { authMeKey, settingsKeys } from '../api/settingsKeys'
import type { Role, ScopeType, User } from '../api/settingsTypes'
import { hasPermission, ROLE_SCOPE_APPLICABILITY } from '../permissions'
import { useAuthorizationMutation } from '../useAuthorizationMutation'

function refreshUsersAndMe(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: settingsKeys.usersAll() })
  void queryClient.invalidateQueries({ queryKey: authMeKey })
}

export function UsersPage() {
  const auth = useAuth()
  const [page, setPage] = useState(0)
  const [inviteOpen, setInviteOpen] = useState(false)
  const [inviteProblem, setInviteProblem] = useState<ProblemDetail | null>(null)

  const usersQuery = useQuery({
    queryKey: settingsKeys.users(page, 50),
    queryFn: () => settingsApi.listUsers(page, 50),
  })

  const canManage = hasPermission(auth.user?.permissions, 'USER_MANAGE')
  const canInvite = hasPermission(auth.user?.permissions, 'USER_INVITE')
  const canAssign = hasPermission(auth.user?.permissions, 'ROLE_ASSIGN')

  const columns: TableProps<User>['columns'] = [
    { title: 'Name', dataIndex: 'displayName', key: 'displayName' },
    { title: 'Email', dataIndex: 'email', key: 'email' },
    { title: 'Status', dataIndex: 'status', key: 'status', render: (status: string) => status.toLowerCase() },
    { title: 'Roles', key: 'roles', render: (_, row) => row.roleAssignments.map((assignment) => assignment.role.code).join(', ') || '—' },
    ...(canManage || canAssign
      ? [{ title: 'Actions', key: 'actions', render: (_: unknown, row: User) => <UserActions user={row} /> }]
      : []),
  ]

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div>
          <h1>Users</h1>
          <Typography.Text type="secondary">Manage organization members and their access.</Typography.Text>
        </div>
        {canInvite && <Button type="primary" onClick={() => setInviteOpen(true)}>Invite member</Button>}
      </div>
      {inviteProblem && (
        <Alert type="error" role="alert" message={inviteProblem.detail || inviteProblem.title} showIcon style={{ marginBottom: 16 }} />
      )}
      {usersQuery.isLoading && <div role="status">Loading users…</div>}
      {usersQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(usersQuery.error).detail || toProblemDetail(usersQuery.error).title} showIcon />
      )}
      {usersQuery.data && usersQuery.data.items.length === 0 && <div className="settings-empty">No users in this organization.</div>}
      {usersQuery.data && usersQuery.data.items.length > 0 && (
        <Table<User>
          rowKey="id"
          columns={columns}
          dataSource={usersQuery.data.items}
          pagination={{
            current: usersQuery.data.page + 1,
            pageSize: usersQuery.data.size,
            total: usersQuery.data.totalElements,
            onChange: (current) => setPage(current - 1),
          }}
        />
      )}
      {inviteOpen && <InviteMemberModal onClose={() => { setInviteOpen(false); setInviteProblem(null) }} onError={setInviteProblem} />}
    </main>
  )
}

function InviteMemberModal({ onClose, onError }: { onClose: () => void; onError: (problem: ProblemDetail) => void }) {
  const queryClient = useQueryClient()
  const [email, setEmail] = useState('')
  const [initialRoleCode, setInitialRoleCode] = useState<string | undefined>()
  const [expiresInHours, setExpiresInHours] = useState<number | null>(72)

  const rolesQuery = useQuery({ queryKey: settingsKeys.roles(), queryFn: () => settingsApi.listRoles() })

  const inviteMutation = useAuthorizationMutation({
    mutationFn: () => settingsApi.createInvitation(email, initialRoleCode!, expiresInHours ?? undefined),
    retry: false,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: settingsKeys.invitations() })
      onClose()
    },
    onError: (error) => onError(toProblemDetail(error)),
  })

  const roleOptions = (rolesQuery.data ?? []).map((role: Role) => ({ value: role.code, label: `${role.code} — ${role.name}` }))

  return (
    <Modal
      open
      title="Invite member"
      okText={inviteMutation.isPending ? 'Inviting…' : 'Send invitation'}
      okButtonProps={{ disabled: !email || !initialRoleCode || inviteMutation.isPending }}
      onOk={() => inviteMutation.mutate()}
      onCancel={onClose}
    >
      <div style={{ display: 'grid', gap: 12 }}>
        <label>
          Email
          <Input type="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="member@example.com" />
        </label>
        <label>
          Initial role
          <Select
            value={initialRoleCode}
            placeholder="Select role"
            options={roleOptions}
            loading={rolesQuery.isLoading}
            onChange={setInitialRoleCode}
          />
        </label>
        <label>
          Expires in hours (1–168)
          <InputNumber min={1} max={168} value={expiresInHours} onChange={setExpiresInHours} style={{ width: '100%' }} />
        </label>
      </div>
    </Modal>
  )
}

function UserActions({ user }: { user: User }) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)

  const canManage = hasPermission(auth.user?.permissions, 'USER_MANAGE')
  const canAssign = hasPermission(auth.user?.permissions, 'ROLE_ASSIGN')

  const statusMutation = useAuthorizationMutation({
    mutationFn: (status: User['status']) => settingsApi.updateUserStatus(user.id, status, user.securityVersion),
    retry: false,
    onSuccess: () => { setProblem(null); refreshUsersAndMe(queryClient) },
    onError: (error) => { setProblem(toProblemDetail(error)); refreshUsersAndMe(queryClient) },
  })

  if (!canManage && !canAssign) return null

  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
      {canManage && (
        <Button
          size="small"
          loading={statusMutation.isPending}
          onClick={() => statusMutation.mutate(user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')}
        >
          {user.status === 'ACTIVE' ? 'Disable' : 'Enable'}
        </Button>
      )}
      {canAssign && <Button size="small" onClick={() => setDrawerOpen(true)}>Assign roles</Button>}
      {problem && <Alert type="error" role="alert" message={problem.detail || problem.title} showIcon />}
      {drawerOpen && <RoleAssignmentDrawer user={user} onClose={() => setDrawerOpen(false)} />}
    </div>
  )
}

function RoleAssignmentDrawer({ user, onClose }: { user: User; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [assignOpen, setAssignOpen] = useState(false)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const refresh = () => refreshUsersAndMe(queryClient)

  const revokeMutation = useAuthorizationMutation({
    mutationFn: (assignmentId: string) => settingsApi.revokeRoleAssignment(assignmentId),
    retry: false,
    onSuccess: () => { setProblem(null); refresh() },
    onError: (error) => { setProblem(toProblemDetail(error)); refresh() },
  })

  return (
    <Drawer open title={`Roles for ${user.displayName}`} onClose={onClose} width={460}>
      {problem && <Alert type="error" role="alert" message={problem.detail || problem.title} showIcon style={{ marginBottom: 12 }} />}
      <Table<User['roleAssignments'][number]>
        rowKey="id"
        size="small"
        dataSource={user.roleAssignments}
        columns={[
          { title: 'Role', dataIndex: ['role', 'code'], key: 'role' },
          { title: 'Scope', key: 'scope', render: (_, assignment) => `${assignment.scopeType} ${assignment.scopeId}` },
          {
            title: '', key: 'revoke', width: 90,
            render: (_, assignment) => (
              <Button size="small" danger loading={revokeMutation.isPending} onClick={() => revokeMutation.mutate(assignment.id)}>
                Revoke
              </Button>
            ),
          },
        ]}
        pagination={false}
      />
      <Button type="primary" block style={{ marginTop: 12 }} onClick={() => setAssignOpen(true)}>Add assignment</Button>
      {assignOpen && <AssignRoleModal memberId={user.organizationMember.id} onClose={() => setAssignOpen(false)} onDone={refresh} />}
    </Drawer>
  )
}

function AssignRoleModal({ memberId, onClose, onDone }: { memberId: string; onClose: () => void; onDone: () => void }) {
  const [roleId, setRoleId] = useState<string | undefined>()
  const [scopeType, setScopeType] = useState<ScopeType | undefined>()
  const [scopeId, setScopeId] = useState('')
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const rolesQuery = useQuery({ queryKey: settingsKeys.roles(), queryFn: () => settingsApi.listRoles() })

  const createMutation = useAuthorizationMutation({
    mutationFn: () => settingsApi.createRoleAssignment(memberId, roleId!, scopeType!, scopeId),
    retry: false,
    onSuccess: () => { onDone(); onClose() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })

  const selectedRole = rolesQuery.data?.find((role: Role) => role.id === roleId)
  const validScopes = selectedRole ? ROLE_SCOPE_APPLICABILITY[selectedRole.code] ?? [] : []

  return (
    <Modal
      open
      title="Add role assignment"
      okText={createMutation.isPending ? 'Assigning…' : 'Assign'}
      okButtonProps={{ disabled: !roleId || !scopeType || !scopeId || createMutation.isPending }}
      onOk={() => createMutation.mutate()}
      onCancel={onClose}
    >
      <div style={{ display: 'grid', gap: 12 }}>
        {problem && <Alert type="error" role="alert" message={problem.detail || problem.title} showIcon />}
        <label>
          Role
          <Select
            value={roleId}
            placeholder="Select role"
            options={(rolesQuery.data ?? []).map((role: Role) => ({ value: role.id, label: `${role.code} — ${role.name}` }))}
            loading={rolesQuery.isLoading}
            onChange={(value) => { setRoleId(value); setScopeType(undefined) }}
          />
        </label>
        <label>
          Scope type
          <Select
            value={scopeType}
            placeholder="Select scope"
            options={validScopes.map((scope) => ({ value: scope, label: scope }))}
            onChange={setScopeType}
          />
        </label>
        <label>
          Scope ID
          <Input value={scopeId} onChange={(event) => setScopeId(event.target.value)} placeholder="Organization, project, team or cost-center ID" />
        </label>
      </div>
    </Modal>
  )
}
