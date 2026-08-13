import { useQueryClient } from '@tanstack/react-query'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Alert, Button, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../../api/problem'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { settingsKeys } from '../api/settingsKeys'
import type { MasterDataRecord } from '../api/settingsTypes'
import { hasPermission } from '../permissions'
import { LifecycleEditorModal, type LifecycleFormValues } from '../shared/LifecycleEditorModal'

export function CostCentersPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [editor, setEditor] = useState<{ mode: 'create' } | { mode: 'edit'; record: MasterDataRecord } | null>(null)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const listQuery = useQuery({
    queryKey: settingsKeys.costCenters(page, 50),
    queryFn: () => settingsApi.listCostCenters(page, 50),
  })

  const canManage = hasPermission(auth.user?.permissions, 'COST_CENTER_MANAGE')

  const saveMutation = useMutation({
    mutationFn: (values: LifecycleFormValues) => {
      if (editor?.mode === 'edit') {
        return settingsApi.updateCostCenter(editor.record.id, { name: values.name, status: values.status })
      }
      return settingsApi.createCostCenter(values.code, values.name)
    },
    retry: false,
    onSuccess: () => {
      setProblem(null)
      setEditor(null)
      void queryClient.invalidateQueries({ queryKey: settingsKeys.costCentersAll() })
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
      render: (_, record) => canManage ? (
        <Button size="small" onClick={() => { setProblem(null); setEditor({ mode: 'edit', record }) }}>Edit</Button>
      ) : null,
    },
  ]

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div>
          <h1>Cost centers</h1>
          <Typography.Text type="secondary">Manage cost centers for the organization.</Typography.Text>
        </div>
        {canManage && <Button type="primary" onClick={() => { setProblem(null); setEditor({ mode: 'create' }) }}>Create cost center</Button>}
      </div>
      {listQuery.isLoading && <div role="status">Loading cost centers…</div>}
      {listQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(listQuery.error).detail || toProblemDetail(listQuery.error).title} showIcon />
      )}
      {listQuery.data && listQuery.data.items.length === 0 && <div className="settings-empty">No cost centers in this organization.</div>}
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
          title={editor.mode === 'edit' ? `Edit cost center ${editor.record.code}` : 'Create cost center'}
          submitting={saveMutation.isPending}
          error={problem}
          initial={editor.mode === 'edit' ? { code: editor.record.code, name: editor.record.name, status: editor.record.status } : undefined}
          onCancel={() => setEditor(null)}
          onSave={(values) => saveMutation.mutate(values)}
        />
      )}
    </main>
  )
}
