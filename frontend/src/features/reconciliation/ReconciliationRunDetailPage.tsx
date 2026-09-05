import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Col, Descriptions, Empty, Row, Select, Skeleton, Statistic, Table, Tag, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { problemDetail, problemTitle, toProblemDetail } from '../../api/problem'
import { formatEventDateTime } from '../../lib/dateTime'
import { formatMoney } from '../../lib/money'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { reconciliationApi } from './api/reconciliationApi'
import { reconciliationKeys } from './api/reconciliationKeys'
import { createIdempotencyKey, formatReconciliationCaseStatus, formatReconciliationCaseType, formatReconciliationRunStatus, reconciliationCaseTagColor, reconciliationRunTagColor } from './presentation'
import type { ReconciliationCaseResponse, ReconciliationCaseStatus } from './types'

const PAGE_SIZE = 50

function DetailError({ error }: { error: unknown }) {
  const problem = toProblemDetail(error)
  return <Alert type="error" showIcon title="无法加载对账详情" description={problemDetail(problem) ?? problemTitle(problem)} />
}

export function ReconciliationRunDetailPage() {
  const { runId = '' } = useParams<{ runId: string }>()
  const navigate = useNavigate()
  const auth = useAuth()
  const queryClient = useQueryClient()
  const canRun = hasPermission(auth.user?.permissions, 'RECONCILIATION_RUN')
  const [caseStatus, setCaseStatus] = useState<ReconciliationCaseStatus | undefined>()
  const [casePage, setCasePage] = useState(0)

  const run = useQuery({ queryKey: reconciliationKeys.run(runId), queryFn: () => reconciliationApi.getRun(runId), enabled: runId.length > 0 })
  const caseParams = useMemo(() => ({ runId, page: casePage, size: PAGE_SIZE, status: caseStatus }), [casePage, caseStatus, runId])
  const cases = useQuery({ queryKey: reconciliationKeys.cases(caseParams), queryFn: () => reconciliationApi.listCases(caseParams), enabled: runId.length > 0 && Boolean(run.data) })
  const evidence = useQuery({ queryKey: reconciliationKeys.runEvidence(runId), queryFn: () => reconciliationApi.listRunEvidence(runId), enabled: runId.length > 0, retry: false })
  const rerun = useMutation({
    mutationFn: () => reconciliationApi.createRun({ billingPeriodId: run.data!.billingPeriodId }, createIdempotencyKey()),
    retry: false,
    onSuccess: (newRun) => {
      void queryClient.invalidateQueries({ queryKey: ['reconciliation', 'runs'] })
      navigate(`/reconciliation/${newRun.id}`)
    },
  })

  if (run.isLoading) return <main className="settings-page m6-page"><Skeleton active paragraph={{ rows: 8 }} /></main>
  if (run.error || !run.data) return <main className="settings-page m6-page"><DetailError error={run.error ?? new Error('missing run')} /></main>
  const data = run.data
  const matchedCount = data.summary.matchedCount
  const differenceCount = data.summary.discrepancyCount
  const unresolvedGatewayEvidence = (evidence.data?.items ?? []).filter(
    (row) => row.matchKind === 'GATEWAY_UNRESOLVED',
  )

  return (
    <main className="settings-page m6-page">
      <header className="page-header m6-page-header">
        <div>
          <Button type="link" className="m6-back-link" onClick={() => navigate('/reconciliation')}>← 返回对账运行</Button>
          <Typography.Text className="m6-eyebrow">财务 / 对账运行</Typography.Text>
          <h1>对账运行详情</h1>
          <Typography.Text type="secondary">运行编号 #{data.id} · 账期 #{data.billingPeriodId}</Typography.Text>
        </div>
        {canRun && <Button onClick={() => rerun.mutate()} loading={rerun.isPending}>再次运行对账</Button>}
      </header>
      {rerun.error && <DetailError error={rerun.error} />}

      <Row gutter={[16, 16]} className="m6-summary-grid">
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="运行状态" value={formatReconciliationRunStatus(data.status)} /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="已匹配记录" value={matchedCount ?? '—'} suffix={matchedCount === null ? undefined : '条'} /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="差异案例" value={differenceCount ?? '—'} suffix={differenceCount === null ? undefined : '个'} /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="精确请求证据" value={data.summary.exactEvidenceCount ?? '—'} suffix={data.summary.exactEvidenceCount === undefined ? undefined : '条'} /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="未决网关财务工作" value={data.summary.unresolvedGatewayCount ?? '—'} suffix={data.summary.unresolvedGatewayCount === undefined ? undefined : '项'} /></Card></Col>
      </Row>

      <Card className="m6-section-card" title="运行摘要">
        <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} size="small">
          <Descriptions.Item label="状态"><Tag color={reconciliationRunTagColor(data.status)}>{formatReconciliationRunStatus(data.status)}</Tag></Descriptions.Item>
          <Descriptions.Item label="算法版本">{data.algorithmVersion}</Descriptions.Item>
          <Descriptions.Item label="对账容差">{formatMoney(data.toleranceAmount)}</Descriptions.Item>
          <Descriptions.Item label="匹配键总数">{data.summary.totalKeys}</Descriptions.Item>
          <Descriptions.Item label="启动时间">{formatEventDateTime(data.startedAt)}</Descriptions.Item>
          <Descriptions.Item label="完成时间">{formatEventDateTime(data.finishedAt)}</Descriptions.Item>
          <Descriptions.Item label="基准快照">{data.basisHash ? '已生成' : '尚未生成'}</Descriptions.Item>
        </Descriptions>
      </Card>

      {differenceCount !== null && differenceCount > 0 && (
        <Alert
          type="warning"
          showIcon
          className="m6-section-card"
          title="存在需要处理的对账阻塞项"
          description={`当前运行产生 ${differenceCount} 个差异案例。未解决的案例可能影响账期关闭准备度。`}
        />
      )}

      {unresolvedGatewayEvidence.length > 0 && (
        <Card
          className="m6-section-card"
          title="未决网关财务工作（运行级）"
          extra={<Typography.Text type="secondary">无需金额差异即可存在，不会为此虚构零金额案例</Typography.Text>}
        >
          <Alert
            type="warning"
            showIcon
            title={`${unresolvedGatewayEvidence.length} 个网关请求仍需人工财务决定`}
            description={unresolvedGatewayEvidence
              .map((row) => `请求 #${row.gatewayRequestId ?? '?'} · 供应商账号 ${row.providerAccountId} · ${row.currency}`)
              .join('；')}
          />
        </Card>
      )}

      <Card className="m6-section-card" title="差异案例" extra={<Typography.Text type="secondary">金额以服务端结果为准</Typography.Text>}>
        <div className="m6-filter-grid m6-case-filters">
          <label>案例状态<Select<ReconciliationCaseStatus> allowClear aria-label="案例状态" value={caseStatus} placeholder="全部案例状态" options={(['OPEN', 'INVESTIGATING', 'RESOLVED'] as const).map((value) => ({ value, label: formatReconciliationCaseStatus(value) }))} onChange={(value) => { setCasePage(0); setCaseStatus(value) }} /></label>
        </div>
        {cases.error ? <DetailError error={cases.error} /> : cases.isLoading ? <Skeleton active paragraph={{ rows: 5 }} /> : (
          <Table<ReconciliationCaseResponse>
            rowKey="id"
            dataSource={cases.data?.items ?? []}
            pagination={{
              current: casePage + 1,
              pageSize: PAGE_SIZE,
              total: cases.data?.totalElements ?? 0,
              showSizeChanger: false,
              onChange: (nextPage) => setCasePage(nextPage - 1),
            }}
            scroll={{ x: 1080 }}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有符合筛选条件的差异案例" /> }}
            onRow={(record) => ({ onClick: () => navigate(`/reconciliation/cases/${record.id}`), className: 'm6-clickable-row' })}
            columns={[
              { title: '案例', dataIndex: 'id', width: 110, render: (value: string) => `#${value}` },
              { title: '差异类型', dataIndex: 'caseType', width: 160, render: (value: string) => formatReconciliationCaseType(value) },
              { title: '供应商账号', dataIndex: 'providerAccountId', width: 130 },
              { title: '币种', dataIndex: 'currency', width: 85 },
              { title: '外部金额', width: 150, render: (_: unknown, row: ReconciliationCaseResponse) => formatMoney(row.externalAmount, row.currency) },
              { title: '内部金额', width: 150, render: (_: unknown, row: ReconciliationCaseResponse) => formatMoney(row.internalAmount, row.currency) },
              { title: '差异', width: 150, render: (_: unknown, row: ReconciliationCaseResponse) => formatMoney(row.differenceAmount, row.currency) },
              { title: '状态', dataIndex: 'status', width: 110, render: (value: string) => <Tag color={reconciliationCaseTagColor(value)}>{formatReconciliationCaseStatus(value)}</Tag> },
              { title: '查看', width: 90, render: () => <Button type="link">详情</Button> },
            ]}
          />
        )}
      </Card>
    </main>
  )
}
