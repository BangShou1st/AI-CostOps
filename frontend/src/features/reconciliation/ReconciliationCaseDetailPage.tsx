import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Col, Descriptions, Divider, Empty, Form, Input, Modal, Row, Skeleton, Space, Statistic, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { problemDetail, problemTitle, toProblemDetail } from '../../api/problem'
import { formatEventDateTime } from '../../lib/dateTime'
import { formatMoney } from '../../lib/money'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { periodCloseApi } from '../period-close/api/periodCloseApi'
import { reconciliationApi } from './api/reconciliationApi'
import { reconciliationKeys } from './api/reconciliationKeys'
import { createIdempotencyKey, formatReconciliationCaseStatus, formatReconciliationCaseType, reconciliationCaseTagColor } from './presentation'
import type { ReconciliationCaseResponse, ReconciliationEvidenceResponse } from './types'

const MATCH_KIND_LABEL: Record<string, string> = {
  EXACT_PROVIDER_REQUEST: '精确请求关联',
  AGGREGATE_SCOPE: '聚合范围',
  GATEWAY_UNRESOLVED: '网关未决',
  MANUAL_BINDING: '人工绑定',
  RESOLUTION_ACTION: '处理动作',
}

const DIFFERENCE_KIND_LABEL: Record<string, string> = {
  PRICING_DRIFT: '定价漂移',
  DISCOUNT: '折扣',
  ROUNDING: '舍入',
  PROVIDER_CORRECTION: '供应商更正',
  LATE_CHARGE: '迟到的费用',
  BILLING_PERIOD_MISMATCH: '账期不一致',
  MISSING_GATEWAY_USAGE: '缺少网关用量',
  UNKNOWN_PROVIDER_CHARGE: '未知供应商费用',
  DUPLICATE_EXTERNAL_CHARGE: '重复外部费用',
  UNCLASSIFIED: '未分类',
}

function CaseError({ error }: { error: unknown }) {
  const problem = toProblemDetail(error)
  return <Alert type="error" showIcon title="案例操作未完成" description={problemDetail(problem) ?? problemTitle(problem)} />
}

export function ReconciliationCaseDetailPage() {
  const { caseId = '' } = useParams<{ caseId: string }>()
  const navigate = useNavigate()
  const auth = useAuth()
  const queryClient = useQueryClient()
  const canResolve = hasPermission(auth.user?.permissions, 'RECONCILIATION_RESOLVE')
  const [resolveOpen, setResolveOpen] = useState(false)
  const [reasonCode, setReasonCode] = useState('')
  const [resolutionNote, setResolutionNote] = useState('')

  const detail = useQuery({ queryKey: reconciliationKeys.case(caseId), queryFn: () => reconciliationApi.getCase(caseId), enabled: caseId.length > 0 })
  const evidence = useQuery({ queryKey: reconciliationKeys.caseEvidence(caseId), queryFn: () => reconciliationApi.listCaseEvidence(caseId), enabled: caseId.length > 0, retry: false })
  const periods = useQuery({ queryKey: ['period-close', 'periods'], queryFn: () => periodCloseApi.listBillingPeriods(), retry: false })
  const refresh = (updated: ReconciliationCaseResponse) => {
    void queryClient.invalidateQueries({ queryKey: reconciliationKeys.case(updated.id) })
    void queryClient.invalidateQueries({ queryKey: ['reconciliation', 'cases'] })
  }
  const investigate = useMutation({ mutationFn: () => reconciliationApi.investigateCase(caseId, createIdempotencyKey()), retry: false, onSuccess: refresh })
  const returnOpen = useMutation({ mutationFn: () => reconciliationApi.returnCaseToOpen(caseId, createIdempotencyKey()), retry: false, onSuccess: refresh })
  const resolve = useMutation({
    mutationFn: () => reconciliationApi.resolveCase(caseId, { reasonCode: reasonCode.trim(), resolutionNote: resolutionNote.trim() }, createIdempotencyKey()),
    retry: false,
    onSuccess: (updated) => { refresh(updated); setResolveOpen(false); setReasonCode(''); setResolutionNote('') },
  })

  if (detail.isLoading) return <main className="settings-page m6-page"><Skeleton active paragraph={{ rows: 8 }} /></main>
  if (detail.error || !detail.data) return <main className="settings-page m6-page"><CaseError error={detail.error ?? new Error('missing case')} /></main>
  const data = detail.data
  const runDetail = useQuery({
    queryKey: reconciliationKeys.run(data.reconciliationRunId),
    queryFn: () => reconciliationApi.getRun(data.reconciliationRunId),
    enabled: Boolean(data),
  })
  const actionError = investigate.error ?? returnOpen.error ?? resolve.error
  const periodClosed = (periods.data ?? []).some(
    (period) => period.id === runDetail.data?.billingPeriodId && period.status === 'CLOSED',
  )
  const busy = investigate.isPending || returnOpen.isPending || resolve.isPending

  return (
    <main className="settings-page m6-page">
      <header className="page-header m6-page-header">
        <div>
          <Button type="link" className="m6-back-link" onClick={() => navigate(`/reconciliation/${data.reconciliationRunId}`)}>← 返回运行详情</Button>
          <Typography.Text className="m6-eyebrow">财务 / 对账案例</Typography.Text>
          <h1>对账案例详情</h1>
          <Typography.Text type="secondary">案例 #{data.id} · 运行 #{data.reconciliationRunId}</Typography.Text>
        </div>
        {canResolve && (
          <Space wrap>
            {data.status === 'OPEN' && <Button type="primary" loading={investigate.isPending} disabled={busy} onClick={() => investigate.mutate()}>开始调查</Button>}
            {data.status === 'INVESTIGATING' && <Button loading={returnOpen.isPending} disabled={busy} onClick={() => returnOpen.mutate()}>退回待处理</Button>}
            {data.status === 'INVESTIGATING' && <Button type="primary" loading={resolve.isPending} disabled={busy} onClick={() => setResolveOpen(true)}>标记已解决</Button>}
          </Space>
        )}
      </header>
      {actionError && <CaseError error={actionError} />}
      {periodClosed && (
        <Alert
          type="info"
          showIcon
          className="m6-section-card"
          title="该账期已关闭"
          description="对账仅作证据查阅，不会自动重开历史账期或修改已关闭的财务数据。如需财务更正，请使用显式的账期重开流程或选择其他开启账期。"
        />
      )}

      <Row gutter={[16, 16]} className="m6-summary-grid">
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="案例状态" value={formatReconciliationCaseStatus(data.status)} /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="差异金额" value={formatMoney(data.differenceAmount, data.currency)} /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="数据行数" value={`${data.externalRowCount} / ${data.internalRowCount}`} suffix="外部 / 内部" /></Card></Col>
      </Row>

      <Card className="m6-section-card" title="金额核对">
        <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} size="small">
          <Descriptions.Item label="状态"><Tag color={reconciliationCaseTagColor(data.status)}>{formatReconciliationCaseStatus(data.status)}</Tag></Descriptions.Item>
          <Descriptions.Item label="差异类型">{formatReconciliationCaseType(data.caseType)}</Descriptions.Item>
          <Descriptions.Item label="供应商账号">{data.providerAccountId}</Descriptions.Item>
          <Descriptions.Item label="币种">{data.currency}</Descriptions.Item>
          <Descriptions.Item label="外部金额">{formatMoney(data.externalAmount, data.currency)}</Descriptions.Item>
          <Descriptions.Item label="内部金额">{formatMoney(data.internalAmount, data.currency)}</Descriptions.Item>
          <Descriptions.Item label="差异金额">{formatMoney(data.differenceAmount, data.currency)}</Descriptions.Item>
          <Descriptions.Item label="外部记录数">{data.externalRowCount}</Descriptions.Item>
          <Descriptions.Item label="内部记录数">{data.internalRowCount}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Card
        className="m6-section-card"
        title="混合证据"
        extra={<Typography.Text type="secondary">处理单条证据不会自动解决同案例下的其他证据</Typography.Text>}
      >
        {evidence.error ? null : evidence.isLoading ? (
          <Skeleton active paragraph={{ rows: 3 }} />
        ) : (evidence.data?.items ?? []).length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无证据记录" />
        ) : (
          <Table<ReconciliationEvidenceResponse>
            rowKey="id"
            dataSource={evidence.data?.items ?? []}
            pagination={{ hideOnSinglePage: true, showSizeChanger: false }}
            scroll={{ x: 900 }}
            columns={[
              { title: '证据', dataIndex: 'id', width: 90, render: (value: string) => `#${value}` },
              { title: '关联方式', dataIndex: 'matchKind', width: 130, render: (value: string) => MATCH_KIND_LABEL[value] ?? value },
              { title: '差异类型', dataIndex: 'differenceKind', width: 140, render: (value: string | null) => (value ? (DIFFERENCE_KIND_LABEL[value] ?? value) : '—') },
              { title: '外部金额', width: 130, render: (_: unknown, row: ReconciliationEvidenceResponse) => formatMoney(row.externalAmount, row.currency) },
              { title: '内部金额', width: 130, render: (_: unknown, row: ReconciliationEvidenceResponse) => formatMoney(row.internalAmount, row.currency) },
              { title: '差异', width: 130, render: (_: unknown, row: ReconciliationEvidenceResponse) => formatMoney(row.differenceAmount, row.currency) },
              { title: '网关请求', dataIndex: 'gatewayRequestId', width: 110, render: (value: string | null) => (value ? `#${value}` : '—') },
              { title: '供应商请求号', dataIndex: 'providerRequestId', width: 160, render: (value: string | null) => value ?? '—' },
            ]}
          />
        )}
      </Card>

      <Card className="m6-section-card" title="处理记录">
        <Descriptions column={{ xs: 1, sm: 2 }} size="small">
          <Descriptions.Item label="创建时间">{formatEventDateTime(data.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{formatEventDateTime(data.updatedAt)}</Descriptions.Item>
          <Descriptions.Item label="处理原因">{data.reasonCode ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="解决时间">{formatEventDateTime(data.resolvedAt)}</Descriptions.Item>
        </Descriptions>
        <Divider />
        <Typography.Text type="secondary">解决说明</Typography.Text>
        <Typography.Paragraph className="m6-note-block">{data.resolutionNote ?? '尚未填写解决说明。'}</Typography.Paragraph>
      </Card>

      <Modal
        open={resolveOpen}
        title="解决对账案例"
        okText={resolve.isPending ? '正在提交…' : '确认解决'}
        okButtonProps={{ disabled: !reasonCode.trim() || !resolutionNote.trim() || resolve.isPending }}
        onOk={() => resolve.mutate()}
        onCancel={() => setResolveOpen(false)}
      >
        <Form layout="vertical">
          <Form.Item label="处理原因" required>
            <Input value={reasonCode} maxLength={100} placeholder="请输入处理原因" onChange={(event) => setReasonCode(event.target.value)} />
          </Form.Item>
          <Form.Item label="解决说明" required>
            <Input.TextArea value={resolutionNote} maxLength={2000} rows={5} placeholder="请说明本次案例的处理结论" onChange={(event) => setResolutionNote(event.target.value)} />
          </Form.Item>
        </Form>
      </Modal>
    </main>
  )
}
