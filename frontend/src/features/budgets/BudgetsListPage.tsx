import { useQuery } from '@tanstack/react-query'
import { Alert, Table, Tag } from 'antd'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toProblemDetail } from '../../api/problem'
import { budgetApi, budgetKeys, type BudgetResponse } from './api/budgetApi'

const PAGE_SIZE = 50

export function BudgetsListPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)

  const list = useQuery({
    queryKey: budgetKeys.list({ page, size: PAGE_SIZE }),
    queryFn: () => budgetApi.list({ page, size: PAGE_SIZE }),
  })

  const problem = list.error ? toProblemDetail(list.error) : null

  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>预算</h1>
      </header>
      {problem && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title="无法加载预算"
          description={(
            <>
              <div>{`${problem.title}（${problem.code}）`}</div>
              {problem.detail && <div>{problem.detail}</div>}
            </>
          )}
        />
      )}
      <Table<BudgetResponse>
        rowKey="id"
        loading={list.isLoading}
        dataSource={list.data?.items ?? []}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: list.data?.totalElements ?? 0,
          showSizeChanger: false,
          onChange: (nextPage) => setPage(nextPage - 1),
        }}
        onRow={(row) => ({ onClick: () => navigate('/budgets/' + row.id) })}
        scroll={{ x: 1080 }}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 90 },
          {
            title: 'Scope',
            width: 130,
            render: (_: unknown, row: BudgetResponse) => `${row.scopeType} · ${row.scopeId}`,
          },
          { title: 'Billing Period', dataIndex: 'billingPeriodId', width: 120 },
          { title: 'Currency', dataIndex: 'currency', width: 90 },
          { title: 'Total', dataIndex: 'totalAmount', width: 180, render: (value: string, row: BudgetResponse) => `${value} ${row.currency}` },
          { title: 'Actual', dataIndex: 'actualAmount', width: 180, render: (value: string, row: BudgetResponse) => `${value} ${row.currency}` },
          { title: 'Outstanding Commitment', dataIndex: 'committedAmount', width: 210, render: (value: string, row: BudgetResponse) => `${value} ${row.currency}` },
          { title: 'Available', dataIndex: 'availableAmount', width: 180, render: (value: string, row: BudgetResponse) => `${value} ${row.currency}` },
          {
            title: 'Over-budget',
            dataIndex: 'overBudget',
            width: 120,
            render: (value: boolean) => (value ? <Tag color="error">超支</Tag> : <Tag>未超支</Tag>),
          },
        ]}
      />
    </main>
  )
}
