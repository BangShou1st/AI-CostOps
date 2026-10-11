import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Col, Descriptions, Divider, Empty, Input, Row, Skeleton, Space, Statistic, Table, Tag, Typography } from 'antd'
import { useState, useMemo } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { formatEventDateTime } from '../../lib/dateTime'
import { formatMoney } from '../../lib/money'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../auth/permissions'
import { periodCloseApi } from '../period-close/api/periodCloseApi'
import { ResolveCaseModal } from './components/ResolveCaseModal'
import { CaseError } from './components/CaseError'
import { CaseAdjustmentModal } from './components/CaseAdjustmentModal'
import { ChargeDispositionModal } from './components/ChargeDispositionModal'
import { GatewayResolutionModal } from './GatewayResolutionModal'
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

export function ReconciliationCaseDetailPage() {
  const { caseId = '' } = useParams<{ caseId: string }>()
  const navigate = useNavigate()
  const auth = useAuth()
  const queryClient = useQueryClient()
  const canResolve = hasPermission(auth.user?.permissions, 'RECONCILIATION_RESOLVE')
  const canLedgerCorrect = hasPermission(auth.user?.permissions, 'LEDGER_CORRECT')
  const canPostAdjustment = canResolve && canLedgerCorrect
  const canResolveGateway = canResolve && canLedgerCorrect
  const [resolveOpen, setResolveOpen] = useState(false)
  const [adjustmentOpen, setAdjustmentOpen] = useState(false)
  const [dispositionTarget, setDispositionTarget] = useState<ReconciliationEvidenceResponse | null>(null)
  const [resolvePending, setResolvePending] = useState(false)
  const [formError, setFormError] = useState<unknown>(null)
  const [gatewayTarget, setGatewayTarget] = useState<ReconciliationEvidenceResponse | null>(null)
  const [correctionGroupId, setCorrectionGroupId] = useState('')
  // Case evidence is paginated by the server; the pager drives real requests.
  const [evidencePage, setEvidencePage] = useState(0)

  const detail = useQuery({ queryKey: reconciliationKeys.case(caseId), queryFn: () => reconciliationApi.getCase(caseId), enabled: caseId.length > 0 })
  const evidenceParams = useMemo(() => ({ page: evidencePage, size: 50 }), [evidencePage])
  const evidence = useQuery({
    queryKey: [...reconciliationKeys.caseEvidence(caseId), evidenceParams],
    queryFn: () => reconciliationApi.listCaseEvidence(caseId, evidenceParams),
    enabled: caseId.length > 0,
    retry: false,
  })
  const periods = useQuery({ queryKey: ['period-close', 'periods'], queryFn: () => periodCloseApi.listBillingPeriods(), retry: false })
  // Hooks order rule: every hook runs before any early return; the run query
  // simply waits for the case identity instead of being called conditionally.
  const reconciliationRunId = detail.data?.reconciliationRunId ?? ''
  // Reconciliation-owned period context for the Gateway resolution modal
  // (RECONCILIATION_READ only, never BUDGET_READ); loading/error/missing
  // maps to UNKNOWN and disables submission instead of defaulting to OPEN.
  const resolutionContext = useQuery({
    queryKey: [...reconciliationKeys.run(reconciliationRunId), 'financial-resolution-context'],
    queryFn: () => reconciliationApi.getFinancialResolutionContext(reconciliationRunId),
    enabled: reconciliationRunId.length > 0,
    retry: false,
  })
  const runDetail = useQuery({
    queryKey: reconciliationKeys.run(reconciliationRunId),
    queryFn: () => reconciliationApi.getRun(reconciliationRunId),
    enabled: reconciliationRunId.length > 0,
  })
  const refresh = (updated: ReconciliationCaseResponse) => {
    void queryClient.invalidateQueries({ queryKey: reconciliationKeys.case(updated.id) })
    void queryClient.invalidateQueries({ queryKey: ['reconciliation', 'cases'] })
  }
  const refreshEvidence = () => {
    void queryClient.invalidateQueries({ queryKey: reconciliationKeys.caseEvidence(caseId) })
    void queryClient.invalidateQueries({ queryKey: reconciliationKeys.case(caseId) })
    void queryClient.invalidateQueries({ queryKey: ['reconciliation', 'cases'] })
  }
  const investigate = useMutation({ mutationFn: () => reconciliationApi.investigateCase(caseId, createIdempotencyKey()), retry: false, onSuccess: refresh })
  const returnOpen = useMutation({ mutationFn: () => reconciliationApi.returnCaseToOpen(caseId, createIdempotencyKey()), retry: false, onSuccess: refresh })
  const linkCorrectionMutation = useMutation({
    mutationFn: () => reconciliationApi.linkCorrection(caseId, { correctionGroupId: correctionGroupId.trim() }),
    retry: false,
    onSuccess: () => {
      setCorrectionGroupId('')
      refreshEvidence()
    },
  })

  if (detail.isLoading) return <main className="settings-page m6-page"><Skeleton active paragraph={{ rows: 8 }} /></main>
  if (detail.error || !detail.data) return <main className="settings-page m6-page"><CaseError error={detail.error ?? new Error('missing case')} /></main>
  const data = detail.data
  const actionError = investigate.error ?? returnOpen.error ?? formError ?? linkCorrectionMutation.error
  const periodClosed = (periods.data ?? []).some(
    (period) => period.id === runDetail.data?.billingPeriodId && period.status === 'CLOSED',
  )
  const busy = investigate.isPending || returnOpen.isPending || resolvePending
  // The server-required CASE_FULL amount is external - internal, i.e. the
  // exact negation of the canonical M6 difference (internal - external).
  const requiredDifference = data.differenceAmount === '0.00000000'
    ? ''
    : data.differenceAmount.startsWith('-')
      ? data.differenceAmount.slice(1)
      : '-' + data.differenceAmount
  const evidenceItems = evidence.data?.items ?? []

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
            {data.status === 'INVESTIGATING' && <Button type="primary" loading={resolvePending} disabled={busy} onClick={() => setResolveOpen(true)}>接受差异并解决</Button>}
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

      {canPostAdjustment && (
        <Card
          className="m6-section-card"
          title="整体案例操作"
          extra={<Typography.Text type="secondary">以下操作作用于整个聚合差异；单条证据的处理不会自动解决整个案例</Typography.Text>}
        >
          <Space wrap>
            <Button onClick={() => setAdjustmentOpen(true)} disabled={data.status !== 'INVESTIGATING'}>
              提交 CASE_FULL 调整
            </Button>
            <Typography.Text type="secondary">
              服务端要求的调整金额 = 外部金额 − 内部金额 = {requiredDifference ? formatMoney(requiredDifference, data.currency) : '无需调整'}，必须以显式分配行拆分，不接受推断分摊。
            </Typography.Text>
          </Space>
        </Card>
      )}

      <Card
        className="m6-section-card"
        title="混合证据与单条证据操作"
        extra={<Typography.Text type="secondary">处理单条证据不会自动解决同案例下的其他证据</Typography.Text>}
      >
        {evidence.error ? null : evidence.isLoading ? (
          <Skeleton active paragraph={{ rows: 3 }} />
        ) : evidenceItems.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无证据记录" />
        ) : (
          <Table<ReconciliationEvidenceResponse>
            rowKey="id"
            dataSource={evidenceItems}
            pagination={{
              current: evidencePage + 1,
              pageSize: 50,
              total: evidence.data?.totalElements ?? 0,
              showSizeChanger: false,
              onChange: (nextPage) => setEvidencePage(nextPage - 1),
            }}
            scroll={{ x: 1100 }}
            columns={[
              { title: '证据', dataIndex: 'id', width: 90, render: (value: string) => `#${value}` },
              { title: '关联方式', dataIndex: 'matchKind', width: 130, render: (value: string) => MATCH_KIND_LABEL[value] ?? value },
              { title: '差异类型', dataIndex: 'differenceKind', width: 140, render: (value: string | null) => (value ? (DIFFERENCE_KIND_LABEL[value] ?? value) : '—') },
              { title: '外部金额', width: 130, render: (_: unknown, row: ReconciliationEvidenceResponse) => formatMoney(row.externalAmount, row.currency) },
              { title: '内部金额', width: 130, render: (_: unknown, row: ReconciliationEvidenceResponse) => formatMoney(row.internalAmount, row.currency) },
              { title: '差异', width: 130, render: (_: unknown, row: ReconciliationEvidenceResponse) => formatMoney(row.differenceAmount, row.currency) },
              { title: '供应商请求号', dataIndex: 'providerRequestId', width: 150, render: (value: string | null) => value ?? '—' },
              { title: '单条证据操作', width: 260, render: (_: unknown, row: ReconciliationEvidenceResponse) => {
                if (!canResolve) return <Typography.Text type="secondary">无处理权限</Typography.Text>
                // Historical evidence rows carry read-model current state: a
                // resolved request or a decided Charge keeps its immutable
                // history but offers no further action.
                if (row.currentChargeDisposition) {
                  return <Tag color={row.currentChargeDisposition === 'DIRECT_PROVIDER_CHARGE' ? 'green' : 'blue'}>
                    {row.currentChargeDisposition === 'DIRECT_PROVIDER_CHARGE' ? '已定：直接供应商费用（DIRECT_PROVIDER_CHARGE）' : '已定：对账证据（RECONCILIATION_EVIDENCE）'}
                  </Tag>
                }
                if (row.currentGatewayResolutionId) {
                  return <Tag color="blue">已处理（终端财务决定 #{row.currentGatewayResolutionId}）</Tag>
                }
                if (row.chargeFactId) {
                  return (
                    <Button
                      size="small"
                      onClick={() => setDispositionTarget(row)}
                    >
                      决定费用处理方式
                    </Button>
                  )
                }
                if (row.matchKind === 'GATEWAY_UNRESOLVED' && row.gatewayRequestId) {
                  // Current actionability comes from the read-model state, never
                  // from the immutable history alone: only ACTIONABLE rows (or
                  // legacy rows without the projection that carry no terminal
                  // resolution) offer the action; every other current state is
                  // shown without a button.
                  const gatewayState = row.currentGatewayState
                  if (gatewayState === 'ACTIONABLE'
                    || (gatewayState == null && !row.currentGatewayResolutionId)) {
                    if (!canResolveGateway) return <Typography.Text type="secondary">需要 RECONCILIATION_RESOLVE 与 LEDGER_CORRECT</Typography.Text>
                    return (
                      <Space>
                        <Button size="small" onClick={() => setGatewayTarget(row)}>处理网关财务工作</Button>
                      </Space>
                    )
                  }
                  if (gatewayState === 'RESOLVED' || row.currentGatewayResolutionId) {
                    return <Tag color="blue">已处理{row.currentGatewayResolutionId ? `（终端财务决定 #${row.currentGatewayResolutionId}）` : ''}</Tag>
                  }
                  const stateLabel: Record<string, string> = {
                    M13_FINAL: 'M13 终态（FINAL 用量归正常结算）',
                    SETTLEMENT_PENDING: '结算处理中（PENDING）',
                    RETRYABLE_FAILED: '结算可重试失败（RETRYABLE_FAILED）',
                    SETTLED: '已结算（SETTLED）',
                    STALE_ROUTE: '路由已变更（STALE_ROUTE）',
                  }
                  return <Tag>{gatewayState ? (stateLabel[gatewayState] ?? gatewayState) : '非当前可操作'}</Tag>
                }
                if (row.matchKind === 'AGGREGATE_SCOPE') {
                  return (
                    <Space>
                      <Input
                        size="small"
                        style={{ width: 140 }}
                        placeholder="更正组编号"
                        value={correctionGroupId}
                        onChange={(event) => setCorrectionGroupId(event.target.value)}
                        aria-label="更正组编号"
                      />
                      <Button size="small" loading={linkCorrectionMutation.isPending} disabled={!correctionGroupId.trim()} onClick={() => linkCorrectionMutation.mutate()}>
                        关联更正
                      </Button>
                    </Space>
                  )
                }
                return <Typography.Text type="secondary">—</Typography.Text>
              } },
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

      <ResolveCaseModal
        caseId={caseId}
        open={resolveOpen}
        onClose={() => setResolveOpen(false)}
        onSuccess={refresh}
        onPendingChange={setResolvePending}
        onError={setFormError}
      />
      <CaseAdjustmentModal
        caseId={caseId}
        open={adjustmentOpen}
        currency={data.currency}
        requiredDifference={requiredDifference}
        periods={periods.data ?? []}
        onClose={() => setAdjustmentOpen(false)}
        onSuccess={refreshEvidence}
        onError={setFormError}
      />
      <ChargeDispositionModal
        caseId={caseId}
        target={dispositionTarget}
        onClose={() => setDispositionTarget(null)}
        onSuccess={refreshEvidence}
        onError={setFormError}
      />

      <GatewayResolutionModal
        runId={reconciliationRunId}
        caseId={caseId}
        target={gatewayTarget}
        onClose={() => setGatewayTarget(null)}
        periodContext={resolutionContext.data
          ? {
            originalPeriodId: resolutionContext.data.originalBillingPeriodId,
            status: resolutionContext.data.originalBillingPeriodStatus as 'OPEN' | 'CLOSING' | 'CLOSED',
            eligibleCorrectionPeriods: resolutionContext.data.eligibleCorrectionPeriods,
          }
          : { originalPeriodId: null, status: 'UNKNOWN' as const, eligibleCorrectionPeriods: [] as { id: string }[] }}
      />
    </main>
  )
}
