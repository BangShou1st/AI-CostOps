import { useQuery } from '@tanstack/react-query'
import { Alert, Card, Col, Empty, Row, Spin, Statistic, Tag } from 'antd'
import { Link } from 'react-router-dom'
import { problemDetail as presentProblemDetail, problemSummary, toProblemDetail } from '../../api/problem'
import { workbenchApi } from './api/workbenchApi'
import { workbenchKeys } from './api/workbenchKeys'
import type { WorkbenchResponse } from './api/workbenchTypes'
import { budgetOverrunCount, currencyAmounts, moneyAmount, PERIOD_STATUS_LABEL } from './presentation'

/**
 * M7 workbench: card-first dashboard over the read-only reporting API.
 * Sections the caller has no ORG grant for are absent from the response and
 * simply not rendered; charts are deliberately secondary to blocker counts.
 */
export function WorkbenchPage() {
  const view = useQuery({
    queryKey: workbenchKeys.view(),
    queryFn: () => workbenchApi.get(),
    staleTime: 30_000,
  })
  const problem = view.error ? toProblemDetail(view.error) : null
  const data: WorkbenchResponse | undefined = view.data

  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>工作台</h1>
        {data?.period && (
          <span className="workbench-period">
            账期 #{data.period.billingPeriodId}{' '}
            <Tag color={data.period.status === 'OPEN' ? 'success' : data.period.status === 'CLOSING' ? 'warning' : 'default'}>
              {PERIOD_STATUS_LABEL[data.period.status] ?? data.period.status}
            </Tag>
          </span>
        )}
      </header>
      {problem && (
        <Alert
          type="error"
          showIcon
          title={problemSummary(problem)}
          description={presentProblemDetail(problem) ?? undefined}
          style={{ marginBottom: 16 }}
        />
      )}
      {view.isLoading && <Spin aria-label="加载中" />}
      {!view.isLoading && !problem && !hasAnySection(data) && (
        <Empty description="当前账号没有可展示的工作台数据权限" />
      )}
      {!view.isLoading && data && (
        <Row gutter={[16, 16]}>
          {data.unallocatedCharges && (
            <Col xs={24} sm={12} lg={8}>
              <Card title="未分摊成本" aria-label="未分摊成本">
                {data.unallocatedCharges.length === 0 ? (
                  <Statistic value={0} suffix="笔" />
                ) : (
                  currencyAmounts(data.unallocatedCharges).map((line) => (
                    <Statistic key={line} value={line} />
                  ))
                )}
                <Link to="/costs">查看成本</Link>
              </Card>
            </Col>
          )}
          {data.duplicateCandidates && (
            <Col xs={24} sm={12} lg={8}>
              <Card title="重复候选" aria-label="重复候选">
                <Statistic value={data.duplicateCandidates.openCount} suffix="条待审" />
                <Link to="/costs/duplicates">去审查</Link>
              </Card>
            </Col>
          )}
          {data.pendingApprovals && (
            <Col xs={24} sm={12} lg={8}>
              <Card title="待审批报销" aria-label="待审批报销">
                <Statistic value={data.pendingApprovals.submittedCount + data.pendingApprovals.needsInfoCount} suffix="单" />
                <div>已提交 {data.pendingApprovals.submittedCount} · 待补信息 {data.pendingApprovals.needsInfoCount}</div>
                <Link to="/expense-reviews">去审核</Link>
              </Card>
            </Col>
          )}
          {data.openReconciliations && (
            <Col xs={24} sm={12} lg={8}>
              <Card title="进行中对账" aria-label="进行中对账">
                <Statistic value={data.openReconciliations.activeRunCount} suffix="次运行" />
                <div>未决差异 {data.openReconciliations.openCaseCount} 条</div>
                <Link to="/reconciliation">查看对账</Link>
              </Card>
            </Col>
          )}
          {data.budgetVariance && (
            <Col xs={24} sm={12} lg={8}>
              <Card title="预算偏差" aria-label="预算偏差">
                <Statistic
                  value={budgetOverrunCount(data.budgetVariance)}
                  suffix={`/${data.budgetVariance.length} 个预算超支`}
                />
                {data.budgetVariance.slice(0, 5).map((line) => (
                  <div key={line.budgetId}>
                    {moneyAmount(line.availableAmount, line.currency)}
                    {line.overBudget && <Tag color="error">超支</Tag>}
                  </div>
                ))}
                <Link to="/budgets">查看预算</Link>
              </Card>
            </Col>
          )}
          {data.costByProvider && (
            <Col xs={24} sm={12} lg={8}>
              <Card title="供应商成本" aria-label="供应商成本">
                {data.costByProvider.slice(0, 5).map((line) => (
                  <div key={`${line.providerCode}-${line.currency}`}>
                    {line.providerCode}: {moneyAmount(line.totalAmount, line.currency)}（{line.chargeCount} 笔）
                  </div>
                ))}
                <Link to="/costs">查看成本</Link>
              </Card>
            </Col>
          )}
        </Row>
      )}
    </main>
  )
}

function hasAnySection(data: WorkbenchResponse | undefined): boolean {
  return Boolean(
    data
      && (data.period || data.costByProvider || data.costByProject || data.budgetVariance
        || data.unallocatedCharges || data.duplicateCandidates || data.pendingApprovals
        || data.openReconciliations || data.closeStatus),
  )
}
