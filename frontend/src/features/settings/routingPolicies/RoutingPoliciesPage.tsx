import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Input, InputNumber, Modal, Space, Table, Tag, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { problemDetail, problemTitle, toProblemDetail, type ProblemDetail } from '../../../api/problem'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { settingsKeys } from '../api/settingsKeys'
import { hasPermission } from '../permissions'
import { useAuthorizationMutation } from '../useAuthorizationMutation'
import { RoutingPolicyEditor } from './RoutingPolicyEditor'
import type { RoutingCandidateInput, RoutingPolicy, RoutingPolicyInput } from './types'

const statusLabel: Record<RoutingPolicy['status'], string> = { DRAFT: '草稿', ACTIVE: '已启用', RETIRED: '已退役' }

export function RoutingPoliciesPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const canManage = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_MANAGE')
  const [editor, setEditor] = useState<RoutingPolicy | null>(null)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [newPolicyOpen, setNewPolicyOpen] = useState(false)

  const policiesQuery = useQuery({ queryKey: settingsKeys.routingPolicies(0, 100), queryFn: () => settingsApi.listRoutingPolicies(0, 100) })
  const selectedModelId = editor?.modelId
  const optionsQuery = useQuery({
    queryKey: settingsKeys.routingOptions(selectedModelId ?? ''),
    queryFn: () => settingsApi.listRoutingOptions(selectedModelId!),
    enabled: selectedModelId !== undefined,
  })
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: settingsKeys.routingPoliciesAll() })

  const mutation = useAuthorizationMutation({
    mutationFn: (input: { policy: RoutingPolicy; candidates: RoutingCandidateInput[] }) => settingsApi.updateRoutingPolicy(input.policy.id, { candidates: input.candidates }),
    onSuccess: (policy) => { setProblem(null); setEditor(policy); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const activation = useAuthorizationMutation({
    mutationFn: (policyId: string) => settingsApi.activateRoutingPolicy(policyId),
    onSuccess: (policy) => { setProblem(null); setEditor(policy); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const revision = useAuthorizationMutation({
    mutationFn: (policyId: string) => settingsApi.createRoutingPolicyRevision(policyId),
    onSuccess: (policy) => { setProblem(null); setEditor(policy); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const create = useAuthorizationMutation({
    mutationFn: (input: RoutingPolicyInput) => settingsApi.createRoutingPolicy(input),
    onSuccess: (policy) => { setNewPolicyOpen(false); setEditor(policy); setProblem(null); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })

  const policies = policiesQuery.data?.items ?? []
  const sortedPolicies = useMemo(() => [...policies].sort((left, right) => right.id.localeCompare(left.id)), [policies])

  function errorText(value: ProblemDetail | null) {
    return value ? problemDetail(value) || problemTitle(value) : null
  }

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div><h1>路由策略</h1><Typography.Text type="secondary">管理组织默认策略、项目策略和多服务商候选顺序。</Typography.Text></div>
        {canManage && <Button type="primary" onClick={() => { setProblem(null); setNewPolicyOpen(true) }}>创建策略</Button>}
      </div>
      {policiesQuery.isLoading && <div role="status">正在加载路由策略…</div>}
      {policiesQuery.isError && <Alert type="error" role="alert" message={problemTitle(toProblemDetail(policiesQuery.error))} description={problemDetail(toProblemDetail(policiesQuery.error))} showIcon />}
      {problem && <Alert type="error" role="alert" message={problemTitle(problem)} description={errorText(problem)} showIcon closable onClose={() => setProblem(null)} />}
      {policiesQuery.data && sortedPolicies.length === 0 && <div className="settings-empty">该组织暂无路由策略。</div>}
      {sortedPolicies.length > 0 && <Table<RoutingPolicy>
        rowKey="id"
        dataSource={sortedPolicies}
        scroll={{ x: 900 }}
        columns={[
          { title: '范围', key: 'scope', render: (_: unknown, policy) => policy.projectId ? `项目 ${policy.projectId}` : '组织默认' },
          { title: '模型', dataIndex: 'modelId', key: 'modelId' },
          { title: '版本', dataIndex: 'version', key: 'version', width: 90 },
          { title: '状态', dataIndex: 'status', key: 'status', width: 110, render: (status: RoutingPolicy['status']) => <Tag color={status === 'ACTIVE' ? 'green' : status === 'DRAFT' ? 'blue' : 'default'}>{statusLabel[status]}</Tag> },
          { title: '候选数', key: 'candidates', render: (_: unknown, policy) => policy.candidates.length },
          { title: '操作', key: 'actions', render: (_: unknown, policy) => <Space>
            <Button size="small" onClick={() => { setProblem(null); setEditor(policy) }}>查看</Button>
            {canManage && policy.status === 'ACTIVE' && <Button size="small" onClick={() => revision.mutate(policy.id)} loading={revision.isPending}>创建新版本</Button>}
            {canManage && policy.status === 'DRAFT' && <Button size="small" type="primary" onClick={() => Modal.confirm({ title: '启用路由策略？', content: '启用后将退役同一范围和模型的旧版本。', okText: '启用版本', cancelText: '取消', onOk: () => activation.mutateAsync(policy.id) })} loading={activation.isPending}>启用版本</Button>}
          </Space> },
        ]}
      />}
      {editor && <RoutingPolicyEditor policy={editor} options={optionsQuery.data ?? []} canManage={canManage} saving={mutation.isPending} error={problem ? errorText(problem) : null} onCancel={() => setEditor(null)} onSave={(candidates) => mutation.mutate({ policy: editor, candidates })} />}
      {newPolicyOpen && <NewPolicyModal saving={create.isPending} onCancel={() => setNewPolicyOpen(false)} onCreate={(input) => create.mutate(input)} />}
    </main>
  )
}

function NewPolicyModal({ saving, onCancel, onCreate }: { saving: boolean; onCancel: () => void; onCreate: (input: RoutingPolicyInput) => void }) {
  const [modelId, setModelId] = useState('')
  const [projectId, setProjectId] = useState('')
  const [providerAccountId, setProviderAccountId] = useState('')
  const [providerModelId, setProviderModelId] = useState('')
  const [priority, setPriority] = useState(0)
  return <Modal open title="创建路由策略" okText="创建草稿" cancelText="取消" confirmLoading={saving} okButtonProps={{ disabled: !modelId || !providerAccountId || !providerModelId }} onCancel={onCancel} onOk={() => onCreate({ modelId, projectId: projectId || null, candidates: [{ providerAccountId, providerModelId, priority, status: 'ACTIVE' }] })}>
    <Space direction="vertical" style={{ width: '100%' }}>
      <label>模型 ID<Input aria-label="模型 ID" value={modelId} onChange={(event) => setModelId(event.target.value)} /></label>
      <label>项目 ID（留空表示组织默认）<Input aria-label="项目 ID" value={projectId} onChange={(event) => setProjectId(event.target.value)} /></label>
      <label>服务商账号 ID<Input aria-label="服务商账号 ID" value={providerAccountId} onChange={(event) => setProviderAccountId(event.target.value)} /></label>
      <label>服务商模型 ID<Input aria-label="服务商模型 ID" value={providerModelId} onChange={(event) => setProviderModelId(event.target.value)} /></label>
      <label>候选优先级<InputNumber aria-label="候选优先级" min={0} value={priority} onChange={(value) => setPriority(value ?? 0)} /></label>
    </Space>
  </Modal>
}
