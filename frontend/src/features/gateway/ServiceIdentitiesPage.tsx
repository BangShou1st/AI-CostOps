import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Input, Modal, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { problemDetail, problemTitle, toProblemDetail, type ProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { useAuthorizationMutation } from '../settings/useAuthorizationMutation'
import { gatewayApi } from './api/gatewayApi'
import { gatewayKeys } from './api/gatewayKeys'
import type { ServiceIdentity } from './api/gatewayTypes'

const statusColor: Record<ServiceIdentity['status'], string> = { ACTIVE: 'green', DISABLED: 'orange', ARCHIVED: 'default' }

export function ServiceIdentitiesPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const canManage = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_MANAGE')
  const [createOpen, setCreateOpen] = useState(false)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const identitiesQuery = useQuery({ queryKey: gatewayKeys.serviceIdentities, queryFn: () => gatewayApi.listServiceIdentities() })
  const create = useAuthorizationMutation({
    mutationFn: (input: { code: string; name: string }) => gatewayApi.createServiceIdentity(input),
    onSuccess: () => { setCreateOpen(false); setProblem(null); void queryClient.invalidateQueries({ queryKey: gatewayKeys.serviceIdentities }) },
    onError: (error) => setProblem(toProblemDetail(error)),
  })

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div><h1>服务身份</h1><Typography.Text type="secondary">组织级服务身份，用于绑定网关凭证的主体验证。</Typography.Text></div>
        {canManage && <Button type="primary" onClick={() => { setProblem(null); setCreateOpen(true) }}>创建服务身份</Button>}
      </div>
      {identitiesQuery.isLoading && <div role="status">正在加载服务身份…</div>}
      {identitiesQuery.isError && <Alert type="error" role="alert" message={problemTitle(toProblemDetail(identitiesQuery.error))} showIcon />}
      {problem && <Alert type="error" role="alert" message={problemTitle(problem)} description={errorText(problem)} showIcon closable onClose={() => setProblem(null)} />}
      {identitiesQuery.data && identitiesQuery.data.length === 0 && <div className="settings-empty">该组织暂无服务身份。</div>}
      {identitiesQuery.data && <Table<ServiceIdentity>
        rowKey="id"
        dataSource={identitiesQuery.data}
        scroll={{ x: 640 }}
        columns={[
          { title: '代号', dataIndex: 'code', key: 'code' },
          { title: '名称', dataIndex: 'name', key: 'name' },
          { title: '状态', dataIndex: 'status', key: 'status', render: (status: ServiceIdentity['status']) => <Tag color={statusColor[status]}>{status}</Tag> },
          { title: 'ID', dataIndex: 'id', key: 'id' },
        ]}
      />}
      {createOpen && <CreateServiceIdentityModal saving={create.isPending} onCancel={() => setCreateOpen(false)} onCreate={(input) => create.mutate(input)} />}
    </main>
  )
}

function CreateServiceIdentityModal({ saving, onCancel, onCreate }: { saving: boolean; onCancel: () => void; onCreate: (input: { code: string; name: string }) => void }) {
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const invalid = !/^[a-z0-9][a-z0-9-]{0,99}$/.test(code.trim()) || name.trim() === ''
  return <Modal
    open
    title="创建服务身份"
    okText="创建"
    cancelText="取消"
    confirmLoading={saving}
    okButtonProps={{ disabled: invalid }}
    onCancel={onCancel}
    onOk={() => onCreate({ code: code.trim(), name: name.trim() })}
  >
    <Space direction="vertical" style={{ width: '100%' }}>
      <label>代号（小写字母/数字/连字符）<Input aria-label="代号" value={code} onChange={(event) => setCode(event.target.value)} /></label>
      <label>名称<Input aria-label="名称" value={name} onChange={(event) => setName(event.target.value)} /></label>
      {invalid && <span role="alert" style={{ color: '#cf1322' }}>请填写合法代号（小写字母、数字或连字符，最多 100 位）与名称。</span>}
    </Space>
  </Modal>
}

function errorText(value: ProblemDetail | null) {
  return value ? problemDetail(value) || problemTitle(value) : null
}