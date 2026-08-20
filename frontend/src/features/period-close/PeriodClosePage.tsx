import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Col, Descriptions, Empty, Form, Input, Modal, Row, Skeleton, Space, Statistic, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { problemDetail, problemTitle, toProblemDetail } from '../../api/problem'
import { formatBusinessDateRange, formatEventDateTime } from '../../lib/dateTime'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { periodCloseApi } from './api/periodCloseApi'
import { periodCloseKeys } from './api/periodCloseKeys'
import { CLOSE_BLOCKER_ORDER, closeResultTagColor, closeRunTagColor, createIdempotencyKey, formatBillingPeriodStatus, formatCheckSummary, formatCloseBlocker, formatCloseCheckResult, formatCloseRunStatus, periodStatusTagColor } from './presentation'
import type { BillingPeriodResponse, CloseCheckResponse, CloseRunResponse } from './types'

const PAGE_SIZE = 20

function CloseError({ title = '账期操作未完成', error }: { title?: string; error: unknown }) {
  const problem = toProblemDetail(error)
  return <Alert type="error" showIcon title={title} description={problemDetail(problem) ?? problemTitle(problem)} />
}

function PeriodStatusTag({ status }: { status: string }) {
  return <Tag color={periodStatusTagColor(status)}>{formatBillingPeriodStatus(status)}</Tag>
}

function CloseChecks({ checks }: { checks: CloseCheckResponse[] }) {
  const byCode = new Map(checks.map((check) => [check.blockerCode, check]))
  return (
    <div className="m6-check-grid">
      {CLOSE_BLOCKER_ORDER.map((code) => {
        const check = byCode.get(code)
        return (
          <Card key={code} size="small" className={`m6-check-card m6-check-${check?.result?.toLowerCase() ?? 'pending'}`}>
            <div className="m6-check-heading">
              <Typography.Text strong>{formatCloseBlocker(code)}</Typography.Text>
              {check ? <Tag color={closeResultTagColor(check.result)}>{formatCloseCheckResult(check.result)}</Tag> : <Tag>未评估</Tag>}
            </div>
            <Typography.Text type="secondary">{check ? formatCheckSummary(check.summary, check.itemCount) : '等待服务端完成校验。'}</Typography.Text>
            {check && <div className="m6-check-meta">涉及 {check.itemCount} 项 · {formatEventDateTime(check.evaluatedAt)}</div>}
          </Card>
        )
      })}
    </div>
  )
}

function PeriodList({ periods, onSelect }: { periods: BillingPeriodResponse[]; onSelect: (periodId: string) => void }) {
  return (
    <Table<BillingPeriodResponse>
      rowKey="id"
      dataSource={periods}
      pagination={false}
      scroll={{ x: 760 }}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无账期" /> }}
      onRow={(record) => ({ onClick: () => onSelect(record.id), className: 'm6-clickable-row' })}
      columns={[
        { title: '账期', width: 260, render: (_: unknown, row: BillingPeriodResponse) => formatBusinessDateRange(row.periodStart, row.periodEnd) },
        { title: '状态', dataIndex: 'status', width: 150, render: (value: string) => <PeriodStatusTag status={value} /> },
        { title: '版本', dataIndex: 'version', width: 100 },
        { title: '账期编号', dataIndex: 'id', width: 150 },
        { title: '操作', width: 110, render: () => <Button type="link">查看详情</Button> },
      ]}
    />
  )
}

export function PeriodClosePage() {
  const { periodId = '' } = useParams<{ periodId?: string }>()
  const navigate = useNavigate()
  const auth = useAuth()
  const queryClient = useQueryClient()
  const canClose = hasPermission(auth.user?.permissions, 'PERIOD_CLOSE')
  const canReopen = hasPermission(auth.user?.permissions, 'PERIOD_REOPEN')
  const [closeConfirmOpen, setCloseConfirmOpen] = useState(false)
  const [reopenConfirmOpen, setReopenConfirmOpen] = useState(false)
  const [reasonCode, setReasonCode] = useState('')
  const [reasonNote, setReasonNote] = useState('')

  const periods = useQuery({ queryKey: periodCloseKeys.periods(), queryFn: periodCloseApi.listBillingPeriods })
  const period = periods.data?.find((item) => item.id === periodId)
  const readiness = useQuery({
    queryKey: periodCloseKeys.readiness(periodId),
    queryFn: () => periodCloseApi.getReadiness(periodId),
    enabled: periodId.length > 0,
  })
  const closeRuns = useQuery({
    queryKey: periodCloseKeys.closeRuns(periodId, { page: 0, size: PAGE_SIZE }),
    queryFn: () => periodCloseApi.listCloseRuns(periodId, { page: 0, size: PAGE_SIZE }),
    enabled: periodId.length > 0,
  })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: periodCloseKeys.periods() })
    void queryClient.invalidateQueries({ queryKey: periodCloseKeys.readiness(periodId) })
    void queryClient.invalidateQueries({ queryKey: ['period-close', 'runs', periodId] })
  }
  const close = useMutation({
    mutationFn: () => periodCloseApi.close(periodId, createIdempotencyKey()),
    retry: false,
    onSuccess: () => { setCloseConfirmOpen(false); refresh() },
  })
  const reopen = useMutation({
    mutationFn: () => periodCloseApi.reopen(periodId, { reasonCode: reasonCode.trim(), reasonNote: reasonNote.trim() }, createIdempotencyKey()),
    retry: false,
    onSuccess: () => { setReopenConfirmOpen(false); setReasonCode(''); setReasonNote(''); refresh() },
  })

  const latestRun = closeRuns.data?.items?.[0]
  const openPeriodCount = periods.data?.filter((item) => item.status === 'OPEN').length ?? 0
  const closedPeriodCount = periods.data?.filter((item) => item.status === 'CLOSED').length ?? 0
  const checkFailureCount = readiness.data?.checks.filter((check) => check.result !== 'PASS').length ?? 0
  const detailError = periods.error ?? readiness.error ?? closeRuns.error

  if (periodId.length === 0) {
    return (
      <main className="settings-page m6-page">
        <header className="page-header m6-page-header">
          <div>
            <Typography.Text className="m6-eyebrow">财务 / 期间结账</Typography.Text>
            <h1>期间结账</h1>
            <Typography.Paragraph type="secondary" className="m6-page-subtitle">查看账期关闭准备度、七项校验结果与历史关闭尝试。</Typography.Paragraph>
          </div>
        </header>
        {periods.error && <CloseError title="无法加载账期" error={periods.error} />}
        <Row gutter={[16, 16]} className="m6-summary-grid">
          <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="账期总数" value={periods.data?.length ?? 0} suffix="个" /></Card></Col>
          <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="开放账期" value={openPeriodCount} suffix="个" /></Card></Col>
          <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="已关闭账期" value={closedPeriodCount} suffix="个" /></Card></Col>
        </Row>
        <Card className="m6-section-card" title="账期列表">
          {periods.isLoading ? <Skeleton active paragraph={{ rows: 5 }} /> : <PeriodList periods={periods.data ?? []} onSelect={(id) => navigate(`/period-close/${id}`)} />}
        </Card>
      </main>
    )
  }

  if (periods.isLoading || readiness.isLoading || closeRuns.isLoading) return <main className="settings-page m6-page"><Skeleton active paragraph={{ rows: 12 }} /></main>
  if (!period || detailError) return <main className="settings-page m6-page"><CloseError title="无法加载账期详情" error={detailError ?? new Error('missing period')} /></main>

  const closeDisabled = period.status !== 'OPEN' || close.isPending
  const reopenDisabled = period.status !== 'CLOSED' || reopen.isPending || !reasonCode.trim() || !reasonNote.trim()

  return (
    <main className="settings-page m6-page">
      <header className="page-header m6-page-header">
        <div>
          <Button type="link" className="m6-back-link" onClick={() => navigate('/period-close')}>← 返回账期列表</Button>
          <Typography.Text className="m6-eyebrow">财务 / 期间结账</Typography.Text>
          <h1>账期关闭准备度</h1>
          <Typography.Text type="secondary">{formatBusinessDateRange(period.periodStart, period.periodEnd)} · 账期 #{period.id}</Typography.Text>
        </div>
        <Space wrap>
          {canClose && period.status === 'OPEN' && <Button type="primary" size="large" onClick={() => setCloseConfirmOpen(true)}>关闭账期</Button>}
          {canReopen && period.status === 'CLOSED' && <Button size="large" onClick={() => setReopenConfirmOpen(true)}>重新开放账期</Button>}
        </Space>
      </header>
      {(close.error || reopen.error) && <CloseError error={close.error ?? reopen.error} />}

      <Row gutter={[16, 16]} className="m6-summary-grid">
        <Col xs={24} sm={6}><Card className="m6-stat-card"><Statistic title="账期状态" value={formatBillingPeriodStatus(period.status)} /></Card></Col>
        <Col xs={24} sm={6}><Card className="m6-stat-card"><Statistic title="关闭代数" value={readiness.data?.closeGeneration ?? '—'} /></Card></Col>
        <Col xs={24} sm={6}><Card className="m6-stat-card"><Statistic title="准备度" value={readiness.data?.ready ? '可以关闭' : '需要处理'} /></Card></Col>
        <Col xs={24} sm={6}><Card className="m6-stat-card"><Statistic title="未通过校验" value={checkFailureCount} suffix="项" /></Card></Col>
      </Row>

      <Card className="m6-section-card" title="账期概览">
        <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} size="small">
          <Descriptions.Item label="账期范围">{formatBusinessDateRange(period.periodStart, period.periodEnd)}</Descriptions.Item>
          <Descriptions.Item label="账期编号">{period.id}</Descriptions.Item>
          <Descriptions.Item label="状态"><PeriodStatusTag status={period.status} /></Descriptions.Item>
          <Descriptions.Item label="账期版本">{period.version}</Descriptions.Item>
          <Descriptions.Item label="最新关闭尝试">{latestRun ? `第 ${latestRun.attemptNo} 次` : '暂无记录'}</Descriptions.Item>
          <Descriptions.Item label="最新结果">{latestRun ? <Tag color={closeRunTagColor(latestRun.runStatus)}>{formatCloseRunStatus(latestRun.runStatus)}</Tag> : '暂无记录'}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card className="m6-section-card" title="关闭校验" extra={<Typography.Text type="secondary">共七项服务端校验</Typography.Text>}>
        {readiness.data ? <CloseChecks checks={readiness.data.checks} /> : <Empty description="暂无校验结果" />}
      </Card>

      <Card className="m6-section-card" title="关闭历史">
        {closeRuns.data?.items.length ? (
          <Table<CloseRunResponse>
            rowKey="runId"
            dataSource={closeRuns.data.items}
            pagination={false}
            scroll={{ x: 900 }}
            columns={[
              { title: '尝试次数', dataIndex: 'attemptNo', width: 110, render: (value: number) => `第 ${value} 次` },
              { title: '关闭代数', dataIndex: 'closeGeneration', width: 120 },
              { title: '结果', dataIndex: 'runStatus', width: 130, render: (value: string) => <Tag color={closeRunTagColor(value)}>{formatCloseRunStatus(value)}</Tag> },
              { title: '开始时间', dataIndex: 'startedAt', width: 180, render: (value: string) => formatEventDateTime(value) },
              { title: '完成时间', dataIndex: 'finishedAt', width: 180, render: (value: string | null) => formatEventDateTime(value) },
              { title: '校验项', width: 100, render: (_: unknown, row: CloseRunResponse) => `${row.checks.length} 项` },
            ]}
          />
        ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无关闭尝试记录" />}
      </Card>

      <Modal
        open={closeConfirmOpen}
        title="确认关闭账期"
        okText={close.isPending ? '正在关闭…' : '确认关闭'}
        okButtonProps={{ disabled: closeDisabled }}
        onOk={() => close.mutate()}
        onCancel={() => setCloseConfirmOpen(false)}
      >
        <Typography.Paragraph>系统将重新执行七项关闭校验，并根据服务端结果决定账期是否关闭。此操作会冻结当前账期的新财务事实。</Typography.Paragraph>
        <Typography.Text type="secondary">当前准备度：{readiness.data?.ready ? '可以关闭' : '仍有校验项需要处理'}</Typography.Text>
      </Modal>

      <Modal
        open={reopenConfirmOpen}
        title="确认重新开放账期"
        okText={reopen.isPending ? '正在提交…' : '确认重新开放'}
        okButtonProps={{ disabled: reopenDisabled }}
        onOk={() => reopen.mutate()}
        onCancel={() => setReopenConfirmOpen(false)}
      >
        <Typography.Paragraph>重新开放会创建新的关闭代数，历史关闭记录与账本记录会保留。</Typography.Paragraph>
        <Form layout="vertical">
          <Form.Item label="重新开放原因" required>
            <Input value={reasonCode} maxLength={100} placeholder="请输入原因" onChange={(event) => setReasonCode(event.target.value)} />
          </Form.Item>
          <Form.Item label="原因说明" required>
            <Input.TextArea value={reasonNote} maxLength={2000} rows={4} placeholder="请说明重新开放的业务原因" onChange={(event) => setReasonNote(event.target.value)} />
          </Form.Item>
        </Form>
      </Modal>
    </main>
  )
}
