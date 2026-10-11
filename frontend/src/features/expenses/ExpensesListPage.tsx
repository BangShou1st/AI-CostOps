import { EXPENSE_STATUS_LABEL as STATUS_LABEL, EXPENSE_STATUS_COLOR as STATUS_COLOR } from './presentation'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Table, Tag } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { expenseApi, expenseKeys, type ExpenseClaimStatus, type ExpenseSummaryResponse } from './api/expenseApi'
import { formatMoney } from '../../lib/money'
import { hasPermission } from '../auth/permissions'
import { useAuth } from '../auth/AuthSessionProvider'
import { useState } from 'react'
import { problemDetail, problemTitle, toProblemDetail } from '../../api/problem'



export function ExpensesListPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const canCreate = hasPermission(auth.user?.permissions, 'EXPENSE_CREATE_OWN')
  const [page, setPage] = useState(0)
  const size = 20

  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: expenseKeys.mine(page, size),
    queryFn: () => expenseApi.listMine(page, size),
    retry: false,
  })

  return (
    <Card title="我的报销" extra={
      canCreate && (
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/expenses/new')}>
          新建报销
        </Button>
      )
    }>
      {error ? (
        <Alert type="error" showIcon title="报销列表加载失败"
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
          {
            title: '审核', dataIndex: 'approvalStatus', width: 100,
            render: (s: string | null) => s ? <Tag>{STATUS_LABEL[s as ExpenseClaimStatus] ?? s}</Tag> : '-',
          },
          { title: '可发布', dataIndex: 'postingReady', width: 80, render: (v: boolean) => v ? '✓' : '-' },
          { title: '版本', dataIndex: 'version', width: 60 },
          {
            title: '', width: 80,
            render: (_, record) => (
              <Button size="small" onClick={() => navigate(`/expenses/${record.id}`)}>查看</Button>
            ),
          },
        ]}
      />}
    </Card>
  )
}
