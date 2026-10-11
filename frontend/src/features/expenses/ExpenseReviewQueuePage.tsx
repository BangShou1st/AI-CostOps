import { EXPENSE_STATUS_LABEL as STATUS_LABEL, EXPENSE_STATUS_COLOR as STATUS_COLOR } from './presentation'
import { useQuery } from '@tanstack/react-query'
import { Alert, Card, Table, Tag, Button, Segmented } from 'antd'
import { useNavigate } from 'react-router-dom'
import { expenseApi, expenseKeys, type ExpenseClaimStatus, type ExpenseReviewStatusFilter, type ExpenseSummaryResponse } from './api/expenseApi'
import { formatMoney } from '../../lib/money'
import { useState } from 'react'
import { problemDetail, problemTitle, toProblemDetail } from '../../api/problem'


export function ExpenseReviewQueuePage() {
  const navigate = useNavigate()
  const [status, setStatus] = useState<ExpenseReviewStatusFilter>('ALL')
  const [page, setPage] = useState(0)
  const size = 20

  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: expenseKeys.reviewQueue(status, page, size),
    queryFn: () => expenseApi.listReviewQueue(status, page, size),
    retry: false,
  })

  return (
    <Card title="费用审核">
      <Segmented
        value={status}
        onChange={(val) => { setStatus(val as ExpenseReviewStatusFilter); setPage(0) }}
        options={[
          { label: '全部', value: 'ALL' },
          { label: '已提交', value: 'SUBMITTED' },
          { label: '需补充', value: 'NEEDS_INFO' },
          { label: '已批准（待分配）', value: 'APPROVED' },
        ]}
        style={{ marginBottom: 16 }}
      />
      {error ? (
        <Alert type="error" showIcon title="审核列表加载失败"
          description={problemDetail(toProblemDetail(error)) ?? problemTitle(toProblemDetail(error))}
          action={<Button onClick={() => { void refetch() }} loading={isFetching}>重试</Button>} />
      ) : <Table
        loading={isLoading}
        dataSource={data?.items ?? []}
        rowKey="id"
        pagination={{
          current: page + 1,
          pageSize: size,
          total: data?.totalElements ?? 0,
          onChange: (p) => setPage(p - 1),
        }}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 80 },
          { title: '日期', dataIndex: 'expenseDate', width: 120 },
          { title: '金额', dataIndex: 'amount', width: 160, render: (_, record: ExpenseSummaryResponse) => formatMoney(record.amount, record.currency) },
          {
            title: '状态', dataIndex: 'status', width: 100,
            render: (status: ExpenseClaimStatus) => <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status]}</Tag>,
          },
          { title: '版本', dataIndex: 'version', width: 60 },
          {
            title: '', width: 80,
            render: (_, record) => (
              <Button size="small" onClick={() => navigate(`/expense-reviews/${record.id}`)}>审核</Button>
            ),
          },
        ]}
      />}
    </Card>
  )
}
