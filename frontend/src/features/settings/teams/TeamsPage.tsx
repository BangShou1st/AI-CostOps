import { useQueryClient } from '@tanstack/react-query'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../../api/problem'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { settingsKeys } from '../api/settingsKeys'
import type { MasterDataRecord } from '../api/settingsTypes'
import { hasPermission } from '../permissions'
import { statusLabel } from '../presentation'
import { useAuthorizationMutation } from '../useAuthorizationMutation'
import { LifecycleEditorModal, type LifecycleFormValues } from '../shared/LifecycleEditorModal'
import { TeamMembersDrawer } from './TeamMembersDrawer'

export function TeamsPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [editor, setEditor] = useState<{ mode: 'create' } | { mode: 'edit'; record: MasterDataRecord } | null>(null)
  const [membersOf, setMembersOf] = useState<MasterDataRecord | null>(null)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const listQuery = useQuery({
    queryKey: settingsKeys.teams(page, 50),
    queryFn: () => settingsApi.listTeams(page, 50),
  })

  const canManage = hasPermission(auth.user?.permissions, 'TEAM_MANAGE')

  const saveMutation = useAuthorizationMutation({
    mutationFn: (values: LifecycleFormValues) => {
      if (editor?.mode === 'edit') {
        return settingsApi.updateTeam(editor.record.id, { name: values.name, status: values.status })
      }
      return settingsApi.createTeam(values.code, values.name)
    },
    retry: false,
    onSuccess: () => {
      setProblem(null)
      setEditor(null)
      void queryClient.invalidateQueries({ queryKey: settingsKeys.teamsAll() })
    },
    onError: (error) => setProblem(toProblemDetail(error)),
  })

  const columns: TableProps<MasterDataRecord>['columns'] = [
    { title: '编码', dataIndex: 'code', key: 'code', width: 180 },
    { title: '名称', dataIndex: 'name', key: 'name' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 110,
      render: (status: string) => <Tag color={status === 'ACTIVE' ? 'green' : status === 'ARCHIVED' ? 'orange' : 'default'}>{statusLabel(status as MasterDataRecord['status'])}</Tag>,
    },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 130, render: (value: string) => new Date(value).toLocaleDateString() },
    {
      title: '操作', key: 'actions', width: 170,
      render: (_, record) => (
        <div className="settings-actions">
          {canManage && <Button size="small" onClick={() => { setProblem(null); setEditor({ mode: 'edit', record }) }}>编辑</Button>}
          <Button size="small" onClick={() => setMembersOf(record)}>成员</Button>
        </div>
      ),
    },
  ]

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div>
          <h1>团队管理</h1>
          <Typography.Text type="secondary">管理团队及其成员。</Typography.Text>
        </div>
        {canManage && <Button type="primary" onClick={() => { setProblem(null); setEditor({ mode: 'create' }) }}>创建团队</Button>}
      </div>
      {listQuery.isLoading && <div role="status">正在加载团队…</div>}
      {listQuery.isError && (
        <Alert type="error" role="alert" title={toProblemDetail(listQuery.error).detail || toProblemDetail(listQuery.error).title} showIcon />
      )}
      {listQuery.data && listQuery.data.items.length === 0 && <div className="settings-empty">该组织暂无团队。</div>}
      {listQuery.data && listQuery.data.items.length > 0 && (
        <Table<MasterDataRecord>
          rowKey="id"
          columns={columns}
          dataSource={listQuery.data.items}
          scroll={{ x: 760 }}
          pagination={{
            current: listQuery.data.page + 1,
            pageSize: listQuery.data.size,
            total: listQuery.data.totalElements,
            onChange: (current) => setPage(current - 1),
          }}
        />
      )}
      {editor && (
        <LifecycleEditorModal
          title={editor.mode === 'edit' ? `编辑团队 ${editor.record.code}` : '创建团队'}
          submitting={saveMutation.isPending}
          error={problem}
          initial={editor.mode === 'edit' ? { code: editor.record.code, name: editor.record.name, status: editor.record.status } : undefined}
          onCancel={() => setEditor(null)}
          onSave={(values) => saveMutation.mutate(values)}
        />
      )}
      {membersOf && <TeamMembersDrawer team={membersOf} onClose={() => setMembersOf(null)} />}
    </main>
  )
}
