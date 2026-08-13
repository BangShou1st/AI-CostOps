import { useQueryClient } from '@tanstack/react-query'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Drawer, Select, Table } from 'antd'
import type { TableProps } from 'antd'
import { useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../../api/problem'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { authMeKey, settingsKeys } from '../api/settingsKeys'
import type { MasterDataRecord, OrganizationMemberRecord, User } from '../api/settingsTypes'
import { hasPermission } from '../permissions'
import { useAuthorizationMutation } from '../useAuthorizationMutation'

export function ProjectMembersDrawer({ project, onClose }: { project: MasterDataRecord; onClose: () => void }) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [selectedMemberId, setSelectedMemberId] = useState<string | undefined>()
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const canWriteMembers = hasPermission(auth.user?.permissions, 'PROJECT_MEMBER_MANAGE')

  const membersQuery = useQuery({
    queryKey: settingsKeys.projectMembers(project.id, page, 50),
    queryFn: () => settingsApi.listProjectMembers(project.id, page, 50),
  })

  const usersQuery = useQuery({
    queryKey: settingsKeys.users(0, 200),
    queryFn: () => settingsApi.listUsers(0, 200),
    enabled: canWriteMembers,
  })

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: settingsKeys.projectMembers(project.id, page, 50) })
    void queryClient.invalidateQueries({ queryKey: authMeKey })
  }

  const addMutation = useAuthorizationMutation({
    mutationFn: (organizationMemberId: string) => settingsApi.addProjectMember(project.id, organizationMemberId),
    retry: false,
    onSuccess: () => { setProblem(null); setSelectedMemberId(undefined); refresh() },
    onError: (error) => { setProblem(toProblemDetail(error)); refresh() },
  })

  const removeMutation = useAuthorizationMutation({
    mutationFn: (memberId: string) => settingsApi.removeProjectMember(project.id, memberId),
    retry: false,
    onSuccess: () => { setProblem(null); refresh() },
    onError: (error) => { setProblem(toProblemDetail(error)); refresh() },
  })

  const columns: TableProps<OrganizationMemberRecord>['columns'] = [
    { title: 'Name', dataIndex: 'displayName', key: 'displayName' },
    { title: 'Email', dataIndex: 'email', key: 'email' },
    { title: 'Status', dataIndex: 'status', key: 'status', render: (status: string) => status.toLowerCase() },
    {
      title: '', key: 'remove', width: 100,
      render: (_, member) => canWriteMembers ? (
        <Button size="small" danger loading={removeMutation.isPending} onClick={() => removeMutation.mutate(member.id)}>Remove</Button>
      ) : null,
    },
  ]

  const memberOptions = (usersQuery.data?.items ?? []).map((user: User) => ({
    value: user.organizationMember.id,
    label: `${user.displayName} (${user.email})`,
  }))

  return (
    <Drawer open title={`Members of ${project.name}`} onClose={onClose} width={520}>
      {problem && <Alert type="error" role="alert" message={problem.detail || problem.title} showIcon style={{ marginBottom: 12 }} />}
      {membersQuery.isLoading && <div role="status">Loading members…</div>}
      {membersQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(membersQuery.error).detail || toProblemDetail(membersQuery.error).title} showIcon />
      )}
      {membersQuery.data && membersQuery.data.items.length === 0 && <div className="settings-empty">No members in this project.</div>}
      {membersQuery.data && membersQuery.data.items.length > 0 && (
        <Table<OrganizationMemberRecord>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={membersQuery.data.items}
          pagination={{
            current: membersQuery.data.page + 1,
            pageSize: membersQuery.data.size,
            total: membersQuery.data.totalElements,
            onChange: (current) => setPage(current - 1),
          }}
        />
      )}
      {canWriteMembers && (
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <Select
            aria-label="Organization member"
            placeholder="Select organization member"
            style={{ flex: 1 }}
            value={selectedMemberId}
            options={memberOptions}
            loading={usersQuery.isLoading}
            onChange={setSelectedMemberId}
          />
          <Button
            type="primary"
            loading={addMutation.isPending}
            disabled={!selectedMemberId}
            onClick={() => selectedMemberId && addMutation.mutate(selectedMemberId)}
          >
            Add member
          </Button>
        </div>
      )}
    </Drawer>
  )
}
