import { useQueryClient } from '@tanstack/react-query'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Drawer, Input, Select, Table, Tag } from 'antd'
import type { TableProps } from 'antd'
import { useState } from 'react'
import { problemDetail as presentProblemDetail, problemTitle, toProblemDetail, type ProblemDetail } from '../../../api/problem'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { authMeKey, settingsKeys } from '../api/settingsKeys'
import type { MasterDataRecord, OrganizationMemberRecord, User } from '../api/settingsTypes'
import { hasPermission } from '../permissions'
import { statusLabel } from '../presentation'
import { useAuthorizationMutation } from '../useAuthorizationMutation'
import { READABLE_SELECT_PROPS, readableOption } from '../../../lib/selectPresentation'

export function TeamMembersDrawer({ team, onClose }: { team: MasterDataRecord; onClose: () => void }) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [selectedMemberId, setSelectedMemberId] = useState<string | undefined>()
  const [manualMemberId, setManualMemberId] = useState('')
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const canWriteMembers = hasPermission(auth.user?.permissions, 'TEAM_MANAGE')
  // GET /users needs USER_READ; a manager without it must still be able to
  // manage membership, so the candidate query only runs when it is allowed.
  const canLoadUsers = canWriteMembers && hasPermission(auth.user?.permissions, 'USER_READ')

  const membersQuery = useQuery({
    queryKey: settingsKeys.teamMembers(team.id, page, 50),
    queryFn: () => settingsApi.listTeamMembers(team.id, page, 50),
  })

  const usersQuery = useQuery({
    queryKey: settingsKeys.users(0, 200),
    queryFn: () => settingsApi.listUsers(0, 200),
    enabled: canLoadUsers,
  })

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: settingsKeys.teamMembers(team.id, page, 50) })
    void queryClient.invalidateQueries({ queryKey: authMeKey })
  }

  const addMutation = useAuthorizationMutation({
    mutationFn: (organizationMemberId: string) => settingsApi.addTeamMember(team.id, organizationMemberId),
    retry: false,
    onSuccess: () => {
      setProblem(null)
      setSelectedMemberId(undefined)
      setManualMemberId('')
      refresh()
    },
    onError: (error) => { setProblem(toProblemDetail(error)); refresh() },
  })

  const removeMutation = useAuthorizationMutation({
    mutationFn: (memberId: string) => settingsApi.removeTeamMember(team.id, memberId),
    retry: false,
    onSuccess: () => { setProblem(null); refresh() },
    onError: (error) => { setProblem(toProblemDetail(error)); refresh() },
  })

  const columns: TableProps<OrganizationMemberRecord>['columns'] = [
    { title: '姓名', dataIndex: 'displayName', key: 'displayName', width: 150 },
    { title: '邮箱', dataIndex: 'email', key: 'email', width: 220 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 110, render: (status: string) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{statusLabel(status as OrganizationMemberRecord['status'])}</Tag> },
    {
      title: '', key: 'remove', width: 100,
      render: (_, member) => canWriteMembers ? (
        <Button size="small" danger loading={removeMutation.isPending} onClick={() => removeMutation.mutate(member.id)}>移除</Button>
      ) : null,
    },
  ]

  const memberOptions = (usersQuery.data?.items ?? []).map((user: User) => ({
    ...readableOption(user.organizationMember.id, `${user.displayName} (${user.email})`),
  }))

  return (
    <Drawer open title={`${team.name} 的成员`} onClose={onClose} size={520}>
      {problem && <Alert type="error" role="alert" title={presentProblemDetail(problem) || problemTitle(problem)} showIcon style={{ marginBottom: 12 }} />}
      {membersQuery.isLoading && <div role="status">正在加载成员…</div>}
      {membersQuery.isError && (
        <Alert type="error" role="alert" title={presentProblemDetail(toProblemDetail(membersQuery.error)) || problemTitle(toProblemDetail(membersQuery.error))} showIcon />
      )}
      {membersQuery.data && membersQuery.data.items.length === 0 && <div className="settings-empty">该团队暂无成员。</div>}
      {membersQuery.data && membersQuery.data.items.length > 0 && (
        <Table<OrganizationMemberRecord>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={membersQuery.data.items}
          scroll={{ x: 560 }}
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
          {canLoadUsers ? (
            <Select
              {...READABLE_SELECT_PROPS}
              aria-label="组织成员"
              placeholder="选择组织成员"
              style={{ flex: '1 1 auto', minWidth: 0 }}
              value={selectedMemberId}
              options={memberOptions}
              loading={usersQuery.isLoading}
              onChange={setSelectedMemberId}
            />
          ) : (
            <Input
              aria-label="组织成员 ID"
              placeholder="组织成员 ID"
              value={manualMemberId}
              onChange={(event) => setManualMemberId(event.target.value)}
            />
          )}
          <Button
            type="primary"
            loading={addMutation.isPending}
            disabled={canLoadUsers ? !selectedMemberId : !manualMemberId.trim()}
            onClick={() => {
              // The submission target always follows the CURRENT permission
              // mode; a stale Select selection must never leak into manual mode.
              const target = canLoadUsers ? selectedMemberId : manualMemberId.trim()
              if (target) addMutation.mutate(target)
            }}
          >
            添加成员
          </Button>
        </div>
      )}
    </Drawer>
  )
}
