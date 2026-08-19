import { Table, Tag } from 'antd'
import { useNavigate } from 'react-router-dom'
import type { ApprovalCaseStatus, CommitmentResponse, CommitmentStatus } from '../api/commitmentApi'
import { APPROVAL_STATUS_LABEL, COMMITMENT_STATUS_COLOR, COMMITMENT_STATUS_LABEL } from '../presentation'

interface CommitmentTableProps {
  items: CommitmentResponse[]
  loading: boolean
  page: number
  pageSize: number
  total: number
  onPageChange: (page: number) => void
  /** Currency of the owning budget; commitment responses carry no currency. */
  currency: string
}

/** Paged commitment list shown inside the budget detail page. */
export function CommitmentTable({
  items,
  loading,
  page,
  pageSize,
  total,
  onPageChange,
  currency,
}: CommitmentTableProps) {
  const navigate = useNavigate()
  return (
    <Table<CommitmentResponse>
      rowKey="id"
      loading={loading}
      dataSource={items}
      pagination={{
        current: page + 1,
        pageSize,
        total,
        showSizeChanger: false,
        onChange: (nextPage) => onPageChange(nextPage - 1),
      }}
      onRow={(row) => ({ onClick: () => navigate('/budget-commitments/' + row.id) })}
      scroll={{ x: 920 }}
      columns={[
        { title: 'ID', dataIndex: 'id', width: 90 },
        {
          title: 'Status',
          dataIndex: 'status',
          width: 130,
          render: (value: CommitmentStatus) => (
            <Tag color={COMMITMENT_STATUS_COLOR[value]}>{COMMITMENT_STATUS_LABEL[value]}</Tag>
          ),
        },
        { title: 'Requested', dataIndex: 'requestedAmount', width: 180, render: (value: string) => `${value} ${currency}` },
        { title: 'Approved', dataIndex: 'approvedAmount', width: 180, render: (value: string | null) => value === null ? '—' : `${value} ${currency}` },
        { title: 'Remaining', dataIndex: 'remainingAmount', width: 180, render: (value: string | null) => value === null ? '—' : `${value} ${currency}` },
        {
          title: 'Approval Status',
          dataIndex: 'approvalStatus',
          width: 140,
          render: (value: ApprovalCaseStatus | null) => (value === null ? '—' : APPROVAL_STATUS_LABEL[value]),
        },
        { title: 'Created At', dataIndex: 'createdAt', width: 200 },
      ]}
    />
  )
}
