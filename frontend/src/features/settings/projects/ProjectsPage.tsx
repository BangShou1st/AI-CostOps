import { useQueryClient } from '@tanstack/react-query'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../../api/problem'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { settingsKeys } from '../api/settingsKeys'
import type { MasterDataRecord } from '../api/settingsTypes'
import { hasPermission } from '../permissions'
import { useAuthorizationMutation } from '../useAuthorizationMutation'
import { LifecycleEditorModal, type LifecycleFormValues } from '../shared/LifecycleEditorModal'
import { ProjectMembersDrawer } from './ProjectMembersDrawer'

export function ProjectsPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [editor, setEditor] = useState<{ mode: 'create' } | { mode: 'edit'; record: MasterDataRecord } | null>(null)
  const [membersOf, setMembersOf] = useState<MasterDataRecord | null>(null)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const listQuery = useQuery({
    queryKey: settingsKeys.projects(page, 50),
    queryFn: () => settingsApi.listProjects(page, 50),
  })

  const canManage = hasPermission(auth.user?.permissions, 'PROJECT_MANAGE')
  const canReadMembers = hasPermission(auth.user?.permissions, 'PROJECT_READ')
    || hasPermission(auth.user?.permissions, 'PROJECT_MEMBER_MANAGE')

  const saveMutation = useAuthorizationMutation({
    mutationFn: (values: LifecycleFormValues) => {
      if (editor?.mode === 'edit') {
        return settingsApi.updateProject(editor.record.id, { name: values.name, status: values.status })
      }
      return settingsApi.createProject(values.code, values.name)
    },
    retry: false,
    onSuccess: () => {
      setProblem(null)
      setEditor(null)
      void queryClient.invalidateQueries({ queryKey: settingsKeys.projectsAll() })
    },
    onError: (error) => setProblem(toProblemDetail(error)),
  })

  const columns: TableProps<MasterDataRecord>['columns'] = [
    { title: 'Code', dataIndex: 'code', key: 'code' },
    { title: 'Name', dataIndex: 'name', key: 'name' },
    { title: 'Status', dataIndex: 'status', key: 'status', render: (status: string) => status.toLowerCase() },
    { title: 'Updated', dataIndex: 'updatedAt', key: 'updatedAt', render: (value: string) => new Date(value).toLocaleDateString() },
    {
      title: 'Actions', key: 'actions',
      render: (_, record) => (
        <div style={{ display: 'flex', gap: 8 }}>
          {canManage && <Button size="small" onClick={() => { setProblem(null); setEditor({ mode: 'edit', record }) }}>Edit</Button>}
          {canReadMembers && <Button size="small" onClick={() => setMembersOf(record)}>Members</Button>}
        </div>
      ),
    },
  ]

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div>
          <h1>Projects</h1>
          <Typography.Text type="secondary">Manage projects and their membership.</Typography.Text>
        </div>
        {canManage && <Button type="primary" onClick={() => { setProblem(null); setEditor({ mode: 'create' }) }}>Create project</Button>}
      </div>
      {listQuery.isLoading && <div role="status">Loading projects…</div>}
      {listQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(listQuery.error).detail || toProblemDetail(listQuery.error).title} showIcon />
      )}
      {listQuery.data && listQuery.data.items.length === 0 && <div className="settings-empty">No projects in this organization.</div>}
      {listQuery.data && listQuery.data.items.length > 0 && (
        <Table<MasterDataRecord>
          rowKey="id"
          columns={columns}
          dataSource={listQuery.data.items}
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
          title={editor.mode === 'edit' ? `Edit project ${editor.record.code}` : 'Create project'}
          submitting={saveMutation.isPending}
          error={problem}
          initial={editor.mode === 'edit' ? { code: editor.record.code, name: editor.record.name, status: editor.record.status } : undefined}
          onCancel={() => setEditor(null)}
          onSave={(values) => saveMutation.mutate(values)}
        />
      )}
      {membersOf && <ProjectMembersDrawer project={membersOf} onClose={() => setMembersOf(null)} />}
    </main>
  )
}
