import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Card, Descriptions, Space } from 'antd'
import { useCallback, useState } from 'react'
import { useParams } from 'react-router-dom'
import { toProblemDetail } from '../../api/problem'
import { hasPermission } from '../settings/permissions'
import { useAuth } from '../auth/AuthSessionProvider'
import { RequestCommitmentButton } from './components/RequestCommitmentButton'
import { budgetApi, budgetKeys } from './api/budgetApi'
import { commitmentApi, commitmentKeys } from './api/commitmentApi'
import { BudgetMetrics } from './components/BudgetMetrics'
import { BudgetTotalEditor } from './components/BudgetTotalEditor'
import { CommitmentTable } from './components/CommitmentTable'

const COMMITMENT_PAGE_SIZE = 10

export function BudgetDetailPage() {
  const { budgetId } = useParams<{ budgetId: string }>()
  const auth = useAuth()
  const canManage = hasPermission(auth.user?.permissions, 'BUDGET_MANAGE')
  const canRequest = hasPermission(auth.user?.permissions, 'COMMITMENT_REQUEST')
  const qc = useQueryClient()
  const [commitmentPage, setCommitmentPage] = useState(0)

  const budget = useQuery({
    queryKey: budgetKeys.detail(budgetId!),
    queryFn: () => budgetApi.get(budgetId!),
    enabled: !!budgetId,
  })

  const commitments = useQuery({
    queryKey: commitmentKeys.list({ budgetId: budgetId!, page: commitmentPage, size: COMMITMENT_PAGE_SIZE }),
    queryFn: () => commitmentApi.list({ budgetId: budgetId!, page: commitmentPage, size: COMMITMENT_PAGE_SIZE }),
    enabled: !!budgetId,
  })

  const budgetProblem = budget.error ? toProblemDetail(budget.error) : null
  const commitmentProblem = commitments.error ? toProblemDetail(commitments.error) : null

  // After any financial mutation the server read model is the truth: refresh
  // the detail and every paged list instead of patching caches optimistically.
  const refreshBudget = useCallback(() => {
    qc.invalidateQueries({ queryKey: budgetKeys.detail(budgetId!) })
    qc.invalidateQueries({ queryKey: budgetKeys.lists() })
  }, [qc, budgetId])

  // A request only creates a REQUESTED row: the budget counters are unchanged,
  // so only the commitment list needs a refresh; there is never an optimistic
  // bump of committedAmount anywhere in the UI.
  const refreshCommitments = useCallback(() => {
    qc.invalidateQueries({ queryKey: commitmentKeys.byBudget(budgetId!) })
  }, [qc, budgetId])

  if (budgetProblem) {
    return (
      <main className="settings-page">
        <header className="page-header"><h1>预算详情</h1></header>
        <Alert
          type="error"
          showIcon
          title="无法加载预算"
          description={(
            <>
              <div>{`${budgetProblem.title}（${budgetProblem.code}）`}</div>
              {budgetProblem.detail && <div>{budgetProblem.detail}</div>}
            </>
          )}
        />
      </main>
    )
  }

  if (budget.isLoading || !budget.data) return <Card loading={budget.isLoading} />
  const b = budget.data

  return (
    <main className="settings-page">
      <header className="page-header"><h1>{`预算详情 #${b.id}`}</h1></header>
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {b.overBudget && (
          <Alert
            type="error"
            showIcon
            message="预算超支"
            description="预算可用额度为负，请检查预算总额与实际 / 承诺支出。"
          />
        )}
        <Card title="预算指标" size="small" extra={canManage ? <BudgetTotalEditor budget={b} onChanged={refreshBudget} /> : undefined}>
          <BudgetMetrics budget={b} />
        </Card>
        <Card title="基本信息" size="small">
          <Descriptions column={2} size="small">
            <Descriptions.Item label="Scope">{b.scopeType}</Descriptions.Item>
            <Descriptions.Item label="Scope ID">{b.scopeId}</Descriptions.Item>
            <Descriptions.Item label="Billing Period ID">{b.billingPeriodId}</Descriptions.Item>
            <Descriptions.Item label="Currency">{b.currency}</Descriptions.Item>
            <Descriptions.Item label="Status">{b.status}</Descriptions.Item>
            <Descriptions.Item label="Version">{`v${b.version}`}</Descriptions.Item>
          </Descriptions>
        </Card>
        <Card title="承诺 (Commitments)" size="small" extra={canRequest ? <RequestCommitmentButton budget={b} onCreated={refreshCommitments} /> : undefined}>
          {commitmentProblem ? (
            <Alert
              type="error"
              showIcon
              title="无法加载承诺"
              description={(
                <>
                  <div>{`${commitmentProblem.title}（${commitmentProblem.code}）`}</div>
                  {commitmentProblem.detail && <div>{commitmentProblem.detail}</div>}
                </>
              )}
            />
          ) : (
            <CommitmentTable
              items={commitments.data?.items ?? []}
              loading={commitments.isLoading}
              page={commitmentPage}
              pageSize={COMMITMENT_PAGE_SIZE}
              total={commitments.data?.totalElements ?? 0}
              onPageChange={setCommitmentPage}
              currency={b.currency}
            />
          )}
        </Card>
      </Space>
    </main>
  )
}
