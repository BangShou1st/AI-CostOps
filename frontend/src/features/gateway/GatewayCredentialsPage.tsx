import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { problemDetail, problemTitle, toProblemDetail, type ProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { settingsApi } from '../settings/api/settingsApi'
import { hasPermission } from '../settings/permissions'
import { useAuthorizationMutation } from '../settings/useAuthorizationMutation'
import { gatewayApi } from './api/gatewayApi'
import { gatewayKeys } from './api/gatewayKeys'
import type { GatewayCredential, GatewayCredentialCreated } from './api/gatewayTypes'

const statusColor: Record<GatewayCredential['status'], string> = { ACTIVE: 'green', REVOKED: 'red', DISABLED: 'orange' }

/**
 * Wire-contract builder for credential creation: identity fields become JSON
 * numbers (backend uses long), the financial scope mirrors the project, and
 * model ids stay numeric — matching the governed backend contract.
 */
export function buildCredentialCreateInput(
  serviceIdentityId: string,
  projectId: string,
  budgetEnforcementMode: 'REQUIRED' | 'OPTIONAL',
  modelIds: string[],
): Parameters<typeof gatewayApi.createGatewayCredential>[0] {
  return {
    principalType: 'SERVICE',
    serviceIdentityId: Number(serviceIdentityId),
    projectId: Number(projectId),
    financialScopeType: 'PROJECT',
    financialScopeId: Number(projectId),
    budgetEnforcementMode,
    expiresAt: null,
    modelIds: modelIds.map(Number),
  }
}

export function GatewayCredentialsPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const canManage = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_MANAGE')
  const [createOpen, setCreateOpen] = useState(false)
  const [created, setCreated] = useState<GatewayCredentialCreated | null>(null)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [revokingId, setRevokingId] = useState<string | null>(null)

  const credentialsQuery = useQuery({ queryKey: gatewayKeys.gatewayCredentials, queryFn: () => gatewayApi.listGatewayCredentials() })
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: gatewayKeys.gatewayCredentials })

  const create = useAuthorizationMutation({
    mutationFn: (input: Parameters<typeof gatewayApi.createGatewayCredential>[0]) => gatewayApi.createGatewayCredential(input),
    onSuccess: (credential) => { setCreateOpen(false); setProblem(null); setCreated(credential); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const revoke = useAuthorizationMutation({
    mutationFn: (id: string) => gatewayApi.revokeGatewayCredential(id),
    onSuccess: () => { setProblem(null); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })

  function confirmRevoke(credential: GatewayCredential) {
    Modal.confirm({
      title: '吊销网关凭证？',
      content: '吊销后该凭证将无法发起新的网关请求；已产生的计费工作不受影响。',
      okText: '吊销',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => { setRevokingId(credential.id); try { await revoke.mutateAsync(credential.id) } finally { setRevokingId(null) } },
    })
  }

  const rows = useMemo(() => [...(credentialsQuery.data ?? [])].sort((a, b) => b.id.localeCompare(a.id)), [credentialsQuery.data])

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div><h1>网关凭证</h1><Typography.Text type="secondary">管理服务身份对应的网关凭证；原始密钥仅在创建时展示一次。</Typography.Text></div>
        {canManage && <Button type="primary" onClick={() => { setProblem(null); setCreateOpen(true) }}>创建凭证</Button>}
      </div>
      {credentialsQuery.isLoading && <div role="status">正在加载网关凭证…</div>}
      {credentialsQuery.isError && <Alert type="error" role="alert" message={problemTitle(toProblemDetail(credentialsQuery.error))} showIcon />}
      {problem && <Alert type="error" role="alert" message={problemTitle(problem)} description={errorText(problem)} showIcon closable onClose={() => setProblem(null)} />}
      {credentialsQuery.data && rows.length === 0 && <div className="settings-empty">该组织暂无网关凭证。</div>}
      {rows.length > 0 && <Table<GatewayCredential>
        rowKey="id"
        dataSource={rows}
        scroll={{ x: 900 }}
        columns={[
          { title: '前缀', dataIndex: 'prefix', key: 'prefix' },
          { title: '主体', key: 'principal', render: (_: unknown, credential) => credential.principalType === 'SERVICE' ? `服务身份 ${credential.serviceIdentityId ?? ''}` : `成员 ${credential.organizationMemberId ?? ''}` },
          { title: '财务范围', key: 'scope', render: (_: unknown, credential) => `${credential.financialScopeType} ${credential.financialScopeId}` },
          { title: '预算模式', dataIndex: 'budgetEnforcementMode', key: 'budgetEnforcementMode' },
          { title: '状态', dataIndex: 'status', key: 'status', render: (status: GatewayCredential['status']) => <Tag color={statusColor[status]}>{status}</Tag> },
          { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt' },
          { title: '操作', key: 'actions', render: (_: unknown, credential) => <Space>
            {canManage && credential.status === 'ACTIVE' && <Button type="link" danger onClick={() => confirmRevoke(credential)} loading={revokingId === credential.id}>吊销</Button>}
            {credential.status === 'REVOKED' && <Typography.Text type="secondary">已吊销</Typography.Text>}
          </Space> },
        ]}
      />}
      {createOpen && <CreateGatewayCredentialModal saving={create.isPending} onCancel={() => setCreateOpen(false)} onCreate={(input) => create.mutate(input)} />}
      {created && <RawKeyModal credential={created} onClose={() => setCreated(null)} />}
    </main>
  )
}

function CreateGatewayCredentialModal({ saving, onCancel, onCreate }: { saving: boolean; onCancel: () => void; onCreate: (input: Parameters<typeof gatewayApi.createGatewayCredential>[0]) => void }) {
  const identitiesQuery = useQuery({ queryKey: gatewayKeys.serviceIdentities, queryFn: () => gatewayApi.listServiceIdentities() })
  const projectsQuery = useQuery({ queryKey: ['settings', 'projects', 0, 100], queryFn: () => settingsApi.listProjects(0, 100) })
  const modelsQuery = useQuery({ queryKey: gatewayKeys.models, queryFn: () => gatewayApi.listModels() })

  const [serviceIdentityId, setServiceIdentityId] = useState<string | null>(null)
  const [projectId, setProjectId] = useState<string | null>(null)
  const [budgetMode, setBudgetMode] = useState<'REQUIRED' | 'OPTIONAL'>('OPTIONAL')
  const [modelIds, setModelIds] = useState<string[]>([])

  const invalid = serviceIdentityId === null || projectId === null || modelIds.length === 0
  return <Modal
    open
    title="创建网关凭证"
    okText="创建并获取密钥"
    cancelText="取消"
    confirmLoading={saving}
    okButtonProps={{ disabled: invalid }}
    onCancel={onCancel}
    onOk={() => onCreate(buildCredentialCreateInput(serviceIdentityId!, projectId!, budgetMode, modelIds))}
  >
    <Space direction="vertical" style={{ width: '100%' }}>
      <label>服务身份<Select aria-label="服务身份" style={{ width: '100%' }} placeholder="选择服务身份" value={serviceIdentityId} onChange={setServiceIdentityId} options={(identitiesQuery.data ?? []).map((identity) => ({ value: identity.id, label: `${identity.code}（${identity.name}）` }))} /></label>
      <label>项目（财务范围）<Select aria-label="项目" style={{ width: '100%' }} placeholder="选择项目" value={projectId} onChange={setProjectId} options={(projectsQuery.data?.items ?? []).map((project) => ({ value: project.id, label: `${project.code}（${project.name}）` }))} /></label>
      <label>预算模式<Select aria-label="预算模式" style={{ width: '100%' }} value={budgetMode} onChange={setBudgetMode} options={[{ value: 'OPTIONAL', label: '可选（OPTIONAL）' }, { value: 'REQUIRED', label: '必需（REQUIRED）' }]} /></label>
      <label>可用模型（明示范围）<Select aria-label="可用模型" style={{ width: '100%' }} mode="multiple" placeholder="选择允许的模型" value={modelIds} onChange={setModelIds} options={(modelsQuery.data ?? []).map((model) => ({ value: model.id, label: `${model.modelKey}（${model.name}）` }))} /></label>
      {invalid && <span role="alert" style={{ color: '#cf1322' }}>请选择服务身份、项目与至少一个可用模型。</span>}
    </Space>
  </Modal>
}

function RawKeyModal({ credential, onClose }: { credential: GatewayCredentialCreated; onClose: () => void }) {
  return <Modal open title="凭证已创建 —— 请立即保存原始密钥" okText="我已保存" cancelButtonProps={{ style: { display: 'none' } }} onOk={onClose} onCancel={onClose} footer={<Button type="primary" onClick={onClose}>我已保存</Button>}>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Alert type="warning" showIcon message="原始密钥仅在本次创建时显示一次，刷新页面或再次查看凭证详情都不会恢复它。" />
      <Typography.Paragraph copyable={{ text: credential.rawKey }} role="note">
        <code data-testid="raw-key">{credential.rawKey}</code>
      </Typography.Paragraph>
      <Typography.Text type="secondary">凭证前缀：{credential.prefix}（用于在列表中标识该凭证）</Typography.Text>
    </Space>
  </Modal>
}

function errorText(value: ProblemDetail | null) {
  return value ? problemDetail(value) || problemTitle(value) : null
}