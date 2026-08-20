import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Col, Empty, Input, Row, Select, Skeleton, Statistic, Table, Tag, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { problemDetail, problemTitle, toProblemDetail } from '../../api/problem'
import { formatBusinessDateRange, formatEventDateTime } from '../../lib/dateTime'
import { formatMoney } from '../../lib/money'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { periodCloseApi } from '../period-close/api/periodCloseApi'
import { periodCloseKeys } from '../period-close/api/periodCloseKeys'
import { formatBillingPeriodStatus } from '../period-close/presentation'
import { reconciliationApi } from './api/reconciliationApi'
import { reconciliationKeys } from './api/reconciliationKeys'
import { createIdempotencyKey, formatReconciliationRunStatus, reconciliationRunTagColor, summaryCount } from './presentation'
import type { ReconciliationRunResponse, ReconciliationRunStatus } from './types'

const PAGE_SIZE = 30

function M6ErrorAlert({ title, error }: { title: string; error: unknown }) {
  const problem = toProblemDetail(error)
  return (
    <Alert
      type="error"
      showIcon
      title={title}
      description={problemDetail(problem) ?? problemTitle(problem)}
      style={{ marginBottom: 16 }}
    />
  )
}

function runMatchesProvider(run: ReconciliationRunResponse, providerId: string): boolean {
  if (!providerId.trim()) return true
  return JSON.stringify(run.summary).includes(providerId.trim())
}

export function ReconciliationPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const canRun = hasPermission(auth.user?.permissions, 'RECONCILIATION_RUN')
  const [periodId, setPeriodId] = useState('')
  const [status, setStatus] = useState<ReconciliationRunStatus | undefined>()
  const [providerId, setProviderId] = useState('')
  const [page, setPage] = useState(0)

  const periods = useQuery({
    queryKey: periodCloseKeys.periods(),
    queryFn: periodCloseApi.listBillingPeriods,
  })

  useEffect(() => {
    if (!periodId && periods.data?.[0]) setPeriodId(periods.data[0].id)
  }, [periodId, periods.data])

  const params = useMemo(() => ({ billingPeriodId: periodId, page, size: PAGE_SIZE }), [page, periodId])
  const runs = useQuery({
    queryKey: reconciliationKeys.runs(params),
    queryFn: () => reconciliationApi.listRuns(params),
    enabled: periodId.length > 0,
  })

  const createRun = useMutation({
    mutationFn: () => reconciliationApi.createRun({ billingPeriodId: periodId }, createIdempotencyKey()),
    retry: false,
    onSuccess: (run) => {
      void queryClient.invalidateQueries({ queryKey: ['reconciliation', 'runs'] })
      navigate(`/reconciliation/${run.id}`)
    },
  })

  const period = periods.data?.find((item) => item.id === periodId)
  const visibleRuns = useMemo(() => (runs.data?.items ?? []).filter((run) => (
    (!status || run.status === status) && runMatchesProvider(run, providerId)
  )), [providerId, runs.data?.items, status])
  const completedCount = visibleRuns.filter((run) => run.status === 'COMPLETED').length
  const caseCount = visibleRuns.reduce((total, run) => total + (summaryCount(run.summary, ['caseCount', 'cases', 'differenceCount']) ?? 0), 0)
  const listProblem = runs.error ?? periods.error
  const mutationProblem = createRun.error

  return (
    <main className="settings-page m6-page">
      <header className="page-header m6-page-header">
        <div>
          <Typography.Text className="m6-eyebrow">财务 / 对账中心</Typography.Text>
          <h1>对账运行</h1>
          <Typography.Paragraph type="secondary" className="m6-page-subtitle">
            按账期核对外部供应商金额与内部账本，并跟踪每次对账产生的差异案例。
          </Typography.Paragraph>
        </div>
        {canRun && (
          <Button
            type="primary"
            size="large"
            disabled={!periodId || createRun.isPending}
            loading={createRun.isPending}
            onClick={() => createRun.mutate()}
          >
            运行对账
          </Button>
        )}
      </header>

      {mutationProblem && <M6ErrorAlert title="对账运行未完成" error={mutationProblem} />}
      {listProblem && <M6ErrorAlert title="无法加载对账数据" error={listProblem} />}

      <Row gutter={[16, 16]} className="m6-summary-grid">
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="当前账期" value={period ? formatBillingPeriodStatus(period.status) : '—'} suffix={period ? ` · ${formatBusinessDateRange(period.periodStart, period.periodEnd)}` : undefined} /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="本页运行次数" value={visibleRuns.length} suffix="次" /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="已完成运行" value={completedCount} suffix="次" /></Card></Col>
      </Row>

      <Card className="m6-section-card" title="筛选条件">
        <div className="m6-filter-grid">
          <label>账期<Select
            aria-label="账期"
            value={periodId || undefined}
            placeholder="选择账期"
            loading={periods.isLoading}
            options={(periods.data ?? []).map((item) => ({
              value: item.id,
              label: `${formatBusinessDateRange(item.periodStart, item.periodEnd)} · ${formatBillingPeriodStatus(item.status)}`,
            }))}
            onChange={(value) => { setPage(0); setPeriodId(value) }}
          /></label>
          <label>运行状态<Select<ReconciliationRunStatus>
            allowClear
            aria-label="运行状态"
            value={status}
            placeholder="全部运行状态"
            options={(['CREATED', 'RUNNING', 'COMPLETED', 'FAILED'] as const).map((value) => ({ value, label: formatReconciliationRunStatus(value) }))}
            onChange={(value) => { setPage(0); setStatus(value) }}
          /></label>
          <label>供应商账号<Input aria-label="供应商账号" placeholder="输入供应商账号 ID" value={providerId} onChange={(event) => { setPage(0); setProviderId(event.target.value) }} /></label>
        </div>
        <Typography.Text type="secondary" className="m6-filter-hint">供应商筛选依据运行摘要中的服务端快照，不会在浏览器重新计算金额。</Typography.Text>
      </Card>

      <Card className="m6-section-card" title={<span>运行历史 {caseCount > 0 ? <Typography.Text type="secondary">· 发现 {caseCount} 个差异案例</Typography.Text> : null}</span>}>
        {periods.isLoading || (periodId.length > 0 && runs.isLoading) ? (
          <Skeleton active paragraph={{ rows: 5 }} />
        ) : (
          <Table<ReconciliationRunResponse>
            rowKey="id"
            dataSource={visibleRuns}
            pagination={{ current: page + 1, pageSize: PAGE_SIZE, total: runs.data?.totalElements ?? 0, showSizeChanger: false, onChange: (next) => setPage(next - 1) }}
            scroll={{ x: 980 }}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={periodId ? '当前筛选条件下暂无对账运行' : '请先选择账期'} /> }}
            onRow={(record) => ({ onClick: () => navigate(`/reconciliation/${record.id}`), className: 'm6-clickable-row' })}
            columns={[
              { title: '运行时间', dataIndex: 'startedAt', width: 180, render: (value: string) => formatEventDateTime(value) },
              { title: '状态', dataIndex: 'status', width: 120, render: (value: string) => <Tag color={reconciliationRunTagColor(value)}>{formatReconciliationRunStatus(value)}</Tag> },
              { title: '算法版本', dataIndex: 'algorithmVersion', width: 150 },
              { title: '容差', dataIndex: 'toleranceAmount', width: 150, render: (value: string) => formatMoney(value) },
              { title: '差异案例', width: 120, render: (_: unknown, row: ReconciliationRunResponse) => summaryCount(row.summary, ['caseCount', 'cases', 'differenceCount']) ?? '—' },
              { title: '查看', width: 100, render: () => <Button type="link">查看详情</Button> },
            ]}
          />
        )}
      </Card>
    </main>
  )
}
