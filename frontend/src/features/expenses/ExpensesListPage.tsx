import { useQuery } from '@tanstack/react-query'
import { Button, Card, Table, Tag } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { expenseApi, expenseKeys, type ExpenseClaimStatus } from './api/expenseApi'
import { hasPermission } from '../settings/permissions'
import { useAuth } from '../auth/AuthSessionProvider'
import { useState } from 'react'

const STATUS_COLOR: Record<ExpenseClaimStatus, string> = {
  DRAFT: 'default',
  SUBMITTED: 'processing',
  NEEDS_INFO: 'warning',
  APPROVED: 'success',
  REJECTED: 'error',
  CANCELED: 'default',
}

const STATUS_LABEL: Record<ExpenseClaimStatus, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  NEEDS_INFO: '需补充',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  CANCELED: '已取消',
}

export function ExpensesListPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const canCreate = hasPermission(auth.user?.permissions, 'EXPENSE_CREATE_OWN')
  const [page, setPage] = useState(0)
  const size = 20

  const { data, isLoading } = useQuery({
    queryKey: expenseKeys.mine(page, size),
    queryFn: () => expenseApi.listMine(page, size),
  })

  return (
    <Card title="我的报销" extra={
      canCreate && (
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/expenses/new')}>
          新建报销
        </Button>
      )
    }>
      <Table
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
          { title: '金额', dataIndex: 'amount', width: 140 },
          { title: '币种', dataIndex: 'currency', width: 60 },
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
      />
    </Card>
  )
}
