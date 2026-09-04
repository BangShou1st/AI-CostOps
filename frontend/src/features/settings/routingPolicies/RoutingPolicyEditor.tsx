import { Alert, Button, Input, InputNumber, Select, Space, Table, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import type { RoutingCandidateInput, RoutingOption, RoutingPolicy } from './types'

export function RoutingPolicyEditor({
  policy,
  options,
  canManage,
  saving,
  error,
  onSave,
  onCancel,
}: {
  policy: RoutingPolicy
  options: RoutingOption[]
  canManage: boolean
  saving: boolean
  error: string | null
  onSave: (candidates: RoutingCandidateInput[]) => void
  onCancel: () => void
}) {
  const [candidates, setCandidates] = useState<RoutingCandidateInput[]>([])

  useEffect(() => {
    setCandidates(policy.candidates.map((candidate) => ({
      providerAccountId: candidate.providerAccountId,
      providerModelId: candidate.providerModelId,
      priority: candidate.priority,
      status: candidate.status,
      privacyRegionCode: candidate.privacyRegionCode,
    })))
  }, [policy])

  const optionMap = useMemo(() => new Map(options.map((option) => [option.providerAccountId + ':' + option.providerModelId, option])), [options])
  const readonly = policy.status !== 'DRAFT' || !canManage

  function updateCandidate(index: number, patch: Partial<RoutingCandidateInput>) {
    setCandidates((current) => current.map((candidate, candidateIndex) => candidateIndex === index ? { ...candidate, ...patch } : candidate))
  }

  function removeCandidate(index: number) {
    setCandidates((current) => current.filter((_, candidateIndex) => candidateIndex !== index))
  }

  function addCandidate() {
    const unused = options.find((option) => !candidates.some((candidate) => candidate.providerAccountId === option.providerAccountId && candidate.providerModelId === option.providerModelId))
    if (!unused) return
    setCandidates((current) => [...current, {
      providerAccountId: unused.providerAccountId,
      providerModelId: unused.providerModelId,
      priority: current.length,
      status: 'ACTIVE',
      privacyRegionCode: null,
    }])
  }

  return (
    <section className="settings-card" aria-label="路由策略编辑器">
      <div className="settings-toolbar">
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>
            {policy.projectId ? '项目策略' : '组织默认策略'} · v{policy.version}
          </Typography.Title>
          <Typography.Text type="secondary">候选优先级（数字越小越优先）</Typography.Text>
        </div>
        <Space>
          {!readonly && <Button onClick={addCandidate} disabled={options.length <= candidates.length}>添加候选</Button>}
          <Button onClick={onCancel}>关闭</Button>
          {!readonly && <Button type="primary" loading={saving} onClick={() => onSave(candidates)}>保存草稿</Button>}
        </Space>
      </div>
      {error && <Alert type="error" role="alert" message={error} showIcon />}
      <Table
        rowKey={(_, index) => `${policy.id}-${index}`}
        pagination={false}
        dataSource={candidates}
        locale={{ emptyText: '暂无候选，请添加可用路由。' }}
        columns={[
          {
            title: '服务商账号 / 模型',
            key: 'route',
            render: (_: unknown, candidate: RoutingCandidateInput, index: number) => {
              const selectedKey = candidate.providerAccountId + ':' + candidate.providerModelId
              return <Select
                aria-label={`候选 ${index + 1}`}
                style={{ minWidth: 260 }}
                disabled={readonly}
                value={selectedKey}
                onChange={(value) => {
                  const [providerAccountId, providerModelId] = value.split(':')
                  updateCandidate(index, { providerAccountId, providerModelId })
                }}
                options={options.map((option) => ({
                  value: option.providerAccountId + ':' + option.providerModelId,
                  label: `${option.providerCode} · ${option.displayName} · ${option.providerModelName}`,
                  disabled: candidates.some((other, otherIndex) => otherIndex !== index && other.providerAccountId === option.providerAccountId && other.providerModelId === option.providerModelId),
                }))}
              />
            },
          },
          {
            title: '优先级', key: 'priority', width: 120,
            render: (_: unknown, candidate: RoutingCandidateInput, index: number) => <InputNumber aria-label={`优先级 ${index + 1}`} min={0} value={candidate.priority} disabled={readonly} onChange={(value) => updateCandidate(index, { priority: value ?? 0 })} />,
          },
          {
            title: '状态', key: 'status', width: 130,
            render: (_: unknown, candidate: RoutingCandidateInput, index: number) => <Select aria-label={`状态 ${index + 1}`} style={{ width: 110 }} value={candidate.status} disabled={readonly} onChange={(status) => updateCandidate(index, { status })} options={[{ value: 'ACTIVE', label: '启用' }, { value: 'DISABLED', label: '停用' }]} />,
          },
          {
            title: '隐私区域', key: 'privacyRegionCode', width: 160,
            render: (_: unknown, candidate: RoutingCandidateInput, index: number) => <Input aria-label={`隐私区域 ${index + 1}`} value={candidate.privacyRegionCode ?? ''} disabled={readonly} onChange={(event) => updateCandidate(index, { privacyRegionCode: event.target.value || null })} />,
          },
          {
            title: '就绪状态', key: 'readiness',
            render: (_: unknown, candidate: RoutingCandidateInput) => {
              const option = optionMap.get(candidate.providerAccountId + ':' + candidate.providerModelId)
              if (!option) return <Typography.Text type="danger">候选不可用</Typography.Text>
              if (!option.credentialReady || !option.pricingReady) return <Typography.Text type="warning">凭证或定价未就绪</Typography.Text>
              return <Typography.Text type="success">可路由</Typography.Text>
            },
          },
          {
            title: '操作', key: 'actions', width: 80,
            render: (_: unknown, _candidate: RoutingCandidateInput, index: number) => !readonly && <Button type="link" danger onClick={() => removeCandidate(index)}>移除</Button>,
          },
        ]}
      />
    </section>
  )
}
