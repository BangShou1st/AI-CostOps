import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import { formatDateTime, formatMoney } from '../intelligence/format'
import { useMemo, useState } from 'react'
import { problemDetail, problemTitle, toProblemDetail, type ProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { settingsApi } from '../settings/api/settingsApi'
import { hasPermission } from '../settings/permissions'
import { useAuthorizationMutation } from '../settings/useAuthorizationMutation'
import { gatewayApi } from './api/gatewayApi'
import { gatewayKeys } from './api/gatewayKeys'
import type { PricingRate } from './api/gatewayTypes'

const DECIMAL_PATTERN = /^\d+(\.\d{1,8})?$/

export function isValidDecimalRate(value: string): boolean {
  return DECIMAL_PATTERN.test(value)
}

/**
 * Wire-contract builder for pricing version creation: provider account and
 * model are JSON numbers (backend long); rates travel as decimal strings that
 * stay BigDecimal-compatible end to end.
 */
export function buildPricingCreateInput(
  accountId: string,
  providerModelId: string,
  unitQuantity: number,
  inputPrice: string,
  outputPrice: string,
): Parameters<typeof gatewayApi.createPricingVersion>[0] {
  return {
    providerAccountId: Number(accountId),
    providerModelId: Number(providerModelId),
    currency: 'USD',
    effectiveFrom: new Date().toISOString(),
    effectiveTo: null,
    rates: [
      { dimensionCode: 'INPUT_TOKEN', unitQuantity, unitPrice: inputPrice },
      { dimensionCode: 'OUTPUT_TOKEN', unitQuantity, unitPrice: outputPrice },
    ],
  }
}

export function ModelPricingPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const canManage = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_MANAGE')
  const [createOpen, setCreateOpen] = useState(false)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const modelsQuery = useQuery({ queryKey: gatewayKeys.models, queryFn: () => gatewayApi.listModels() })
  const providerModelsQuery = useQuery({ queryKey: gatewayKeys.providerModels, queryFn: () => gatewayApi.listProviderModels() })
  const pricingQuery = useQuery({ queryKey: gatewayKeys.pricingVersions, queryFn: () => gatewayApi.listPricingVersions() })
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: gatewayKeys.pricingVersions })

  const create = useAuthorizationMutation({
    mutationFn: (input: Parameters<typeof gatewayApi.createPricingVersion>[0]) => gatewayApi.createPricingVersion(input),
    onSuccess: () => { setCreateOpen(false); setProblem(null); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const activate = useAuthorizationMutation({
    mutationFn: (id: string) => gatewayApi.activatePricingVersion(id),
    onSuccess: () => { setProblem(null); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const versions = useMemo(() => [...(pricingQuery.data ?? [])].sort((a, b) => b.id.localeCompare(a.id)), [pricingQuery.data])

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div><h1>模型与定价</h1><Typography.Text type="secondary">查看全局模型目录与服务商模型，维护组织定价版本。</Typography.Text></div>
        {canManage && <Button type="primary" onClick={() => { setProblem(null); setCreateOpen(true) }}>创建定价版本</Button>}
      </div>
      <Alert style={{ marginBottom: 16 }} type="info" showIcon message="金融治理口径" description="只有 ACTIVE 定价版本才是 Financial Truth；manifest 的 VERIFIED_FREE 只是价格分类元数据，有 ACTIVE 零价格版本才是零价格。" />
      {problem && <div role="alert"><Typography.Text type="danger">{errorText(problem)}</Typography.Text></div>}
      <section className="settings-card" aria-label="模型目录">
        <Typography.Title level={4} style={{ marginTop: 0 }}>模型目录</Typography.Title>
        <Table
          rowKey="id" pagination={false} size="small" dataSource={modelsQuery.data ?? []} loading={modelsQuery.isLoading}
          columns={[
            { title: '模型键', dataIndex: 'modelKey', key: 'modelKey' },
            { title: '名称', dataIndex: 'name', key: 'name' },
            { title: '状态', dataIndex: 'status', key: 'status', render: (status: string) => <Tag color="green">{status}</Tag> },
          ]}
        />
      </section>
      <section className="settings-card" aria-label="服务商模型">
        <Typography.Title level={4} style={{ marginTop: 0 }}>服务商模型</Typography.Title>
        <Table
          rowKey="id" pagination={false} size="small" dataSource={providerModelsQuery.data ?? []} loading={providerModelsQuery.isLoading}
          columns={[
            { title: '服务商', dataIndex: 'providerCode', key: 'providerCode' },
            { title: '模型名', dataIndex: 'providerModelName', key: 'providerModelName' },
            { title: '可路由', dataIndex: 'routingEligible', key: 'routingEligible', render: (value: boolean) => (value ? '是' : '否') },
          ]}
        />
      </section>
      <section className="settings-card" aria-label="定价版本">
        <Typography.Title level={4} style={{ marginTop: 0 }}>定价版本</Typography.Title>
        <Table
          rowKey="id" pagination={false} size="small" scroll={{ x: 900 }} dataSource={versions} loading={pricingQuery.isLoading}
          locale={{ emptyText: '该组织暂无定价版本。' }}
          columns={[
            { title: '账号 / 模型', key: 'scope', render: (_: unknown, version) => `账号 ${version.providerAccountId} / 模型 ${version.providerModelId}` },
            { title: '版本', dataIndex: 'version', key: 'version', width: 80 },
            { title: '币种', dataIndex: 'currency', key: 'currency', width: 80 },
            { title: '状态', key: 'status', width: 110, render: (_: unknown, version) => <Tag color={version.status === 'ACTIVE' ? 'green' : version.status === 'DRAFT' ? 'blue' : 'default'}>{version.status === 'ACTIVE' ? '已启用' : version.status === 'DRAFT' ? '草稿' : '已退役'}</Tag> },
            { title: '费率', key: 'rates', width: 280, render: (_: unknown, version) => version.rates.map((rate: PricingRate) => `${rate.dimensionCode} ${formatMoney(String(rate.unitPrice), version.currency)}/${rate.unitQuantity}`).join('；') },
            { title: '有效期', key: 'effective', width: 220, render: (_: unknown, version) => `${formatDateTime(version.effectiveFrom)} → ${version.effectiveTo ? formatDateTime(version.effectiveTo) : '长期'}` },
            { title: '操作', key: 'actions', width: 100, render: (_: unknown, version) => canManage && version.status === 'DRAFT'
              ? <Button size="small" type="primary" loading={activate.isPending} onClick={() => activate.mutate(version.id)}>启用版本</Button>
              : null },
          ]}
        />
      </section>
      {createOpen && <CreatePricingVersionModal saving={create.isPending} onCancel={() => setCreateOpen(false)} onCreate={(input) => create.mutate(input)} />}
    </main>
  )
}

function CreatePricingVersionModal({ saving, onCancel, onCreate }: { saving: boolean; onCancel: () => void; onCreate: (input: Parameters<typeof gatewayApi.createPricingVersion>[0]) => void }) {
  const accountsQuery = useQuery({ queryKey: ['settings', 'provider-accounts', 0, 100], queryFn: () => settingsApi.listProviderAccounts(0, 100) })
  const providerModelsQuery = useQuery({ queryKey: gatewayKeys.providerModels, queryFn: () => gatewayApi.listProviderModels() })
  const [accountId, setAccountId] = useState<string | null>(null)
  const [providerModelId, setProviderModelId] = useState<string | null>(null)
  const [inputPrice, setInputPrice] = useState('')
  const [outputPrice, setOutputPrice] = useState('')
  const [unitQuantity, setUnitQuantity] = useState(1000000)

  const account = (accountsQuery.data?.items ?? []).find((item) => item.id === accountId)
  const modelOptions = (providerModelsQuery.data ?? [])
    .filter((model) => model.providerCode === account?.providerCode)
    .map((model) => ({ value: model.id, label: model.providerModelName }))

  const invalid = accountId === null || providerModelId === null
    || !DECIMAL_PATTERN.test(inputPrice) || !DECIMAL_PATTERN.test(outputPrice)
    || unitQuantity <= 0

  return <Modal
    open
    title="创建定价版本（草稿）"
    okText="创建草稿"
    cancelText="取消"
    confirmLoading={saving}
    okButtonProps={{ disabled: invalid }}
    onCancel={onCancel}
    onOk={() => onCreate(buildPricingCreateInput(accountId!, providerModelId!, unitQuantity, inputPrice, outputPrice))}
  >
    <Space direction="vertical" style={{ width: '100%' }}>
      <label>服务商账号<Select aria-label="服务商账号" style={{ width: '100%' }} placeholder="选择服务商账号" value={accountId} onChange={setAccountId} options={(accountsQuery.data?.items ?? []).map((item) => ({ value: item.id, label: `${item.displayName}（${item.providerCode}）` }))} /></label>
      <label>服务商模型（仅显示该账号可用的模型）<Select aria-label="服务商模型" style={{ width: '100%' }} placeholder="选择服务商模型" value={providerModelId} onChange={setProviderModelId} options={modelOptions} /></label>
      <label>单位数量<InputNumber aria-label="单位数量" min={1} value={unitQuantity} onChange={(value) => setUnitQuantity(value ?? 1000000)} style={{ width: '100%' }} /></label>
      <label>输入令牌单价（每单位，USD）<Input aria-label="输入令牌单价" placeholder="如 30.00000000" value={inputPrice} onChange={(event) => setInputPrice(event.target.value)} /></label>
      <label>输出令牌单价（每单位，USD）<Input aria-label="输出令牌单价" placeholder="如 60.00000000" value={outputPrice} onChange={(event) => setOutputPrice(event.target.value)} /></label>
      {invalid && <span role="alert" style={{ color: '#cf1322' }}>请选择账号与模型，并填写合法的小数费率（最多 8 位小数）。</span>}
    </Space>
  </Modal>
}

// rateSummary retired: rates render inline with exact decimal formatting.

function errorText(value: ProblemDetail | null) {
  return value ? problemDetail(value) || problemTitle(value) : null
}
