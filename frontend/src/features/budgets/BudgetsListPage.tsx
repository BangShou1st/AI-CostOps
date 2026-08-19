import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Input, Modal, Select, Table, Tag } from 'antd'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { billingPeriodApi, billingPeriodKeys } from './api/billingPeriodApi'
import { budgetApi, budgetKeys, type BudgetResponse, type BudgetScopeType } from './api/budgetApi'
import { BUDGET_SCOPE_LABEL } from './presentation'

const PAGE_SIZE = 50

export function BudgetsListPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [createOpen, setCreateOpen] = useState(false)
  const [billingPeriodId, setBillingPeriodId] = useState<string>()
  const [scopeType, setScopeType] = useState<BudgetScopeType>()
  const [scopeId, setScopeId] = useState('')
  const [currency, setCurrency] = useState('CNY')
  const [totalAmount, setTotalAmount] = useState('')

  const canManage = hasPermission(auth.user?.permissions, 'BUDGET_MANAGE')

  const list = useQuery({
    queryKey: budgetKeys.list({ page, size: PAGE_SIZE }),
    queryFn: () => budgetApi.list({ page, size: PAGE_SIZE }),
  })

  const periods = useQuery({
    queryKey: billingPeriodKeys.list(),
    queryFn: () => billingPeriodApi.list(),
    enabled: canManage && createOpen,
  })

  const createBudget = useMutation({
    mutationFn: () => budgetApi.create({
      billingPeriodId: billingPeriodId!,
      scopeType: scopeType!,
      scopeId,
      currency: currency.toUpperCase(),
      totalAmount,
    }),
    retry: false,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: budgetKeys.lists() })
      setCreateOpen(false)
    },
  })

  const problem = list.error ? toProblemDetail(list.error) : null
  const createProblem = createBudget.error ? toProblemDetail(createBudget.error) : null
  const periodsProblem = periods.error ? toProblemDetail(periods.error) : null
  const openCreateModal = () => {
    createBudget.reset()
    setCreateOpen(true)
  }

  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>预算</h1>
        {canManage && <Button type="primary" onClick={openCreateModal}>创建预算</Button>}
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
            title: '范围',
            width: 130,
            render: (_: unknown, row: BudgetResponse) => `${BUDGET_SCOPE_LABEL[row.scopeType]} · ${row.scopeId}`,
          },
          { title: '账期', dataIndex: 'billingPeriodId', width: 120 },
          { title: '币种', dataIndex: 'currency', width: 90 },
          { title: '总额', dataIndex: 'totalAmount', width: 180, render: (value: string, row: BudgetResponse) => `${value} ${row.currency}` },
          { title: '实际发生', dataIndex: 'actualAmount', width: 180, render: (value: string, row: BudgetResponse) => `${value} ${row.currency}` },
          { title: '未结承诺', dataIndex: 'committedAmount', width: 210, render: (value: string, row: BudgetResponse) => `${value} ${row.currency}` },
          { title: '可用额度', dataIndex: 'availableAmount', width: 180, render: (value: string, row: BudgetResponse) => `${value} ${row.currency}` },
          {
            title: '超支状态',
            dataIndex: 'overBudget',
            width: 120,
            render: (value: boolean) => (value ? <Tag color="error">超支</Tag> : <Tag>未超支</Tag>),
          },
        ]}
      />
      <Modal
        open={createOpen}
        title="创建预算"
        okText={createBudget.isPending ? '正在创建…' : '创建'}
        okButtonProps={{
          disabled: !billingPeriodId || !scopeType || !scopeId || !currency || !totalAmount || createBudget.isPending,
        }}
        onOk={() => createBudget.mutate()}
        onCancel={() => setCreateOpen(false)}
      >
        <div style={{ display: 'grid', gap: 12 }}>
          {periodsProblem && (
            <Alert
              type="error"
              role="alert"
              showIcon
              title={`${periodsProblem.title}（${periodsProblem.code}）`}
              description={periodsProblem.detail ?? undefined}
            />
          )}
          {createProblem && (
            <Alert
              type="error"
              role="alert"
              showIcon
              title={`${createProblem.title}（${createProblem.code}）`}
              description={createProblem.detail ?? undefined}
            />
          )}
          <label>
            账期
            <Select
              style={{ width: '100%' }}
              value={billingPeriodId}
              placeholder="选择账期"
              options={(periods.data ?? []).map((period) => ({
                value: period.id,
                label: `${period.periodStart} → ${period.periodEnd}（${period.status}）`,
              }))}
              loading={periods.isLoading}
              onChange={setBillingPeriodId}
            />
          </label>
          <label>
            范围类型
            <Select
              style={{ width: '100%' }}
              value={scopeType}
              placeholder="选择范围类型"
              options={(['ORG', 'PROJECT', 'TEAM', 'COST_CENTER'] as const).map((type) => ({
                value: type,
                label: BUDGET_SCOPE_LABEL[type],
              }))}
              onChange={setScopeType}
            />
          </label>
          <label>
            范围 ID
            <Input value={scopeId} onChange={(event) => setScopeId(event.target.value)} />
          </label>
          <label>
            币种
            <Input value={currency} maxLength={3} onChange={(event) => setCurrency(event.target.value)} />
          </label>
          <label>
            总额
            <Input value={totalAmount} placeholder="1000.00000000" onChange={(event) => setTotalAmount(event.target.value)} />
          </label>
        </div>
      </Modal>
    </main>
  )
}
