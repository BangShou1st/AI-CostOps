import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Space, Table, Tag } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import { toProblemDetail } from '../../api/problem'
import { budgetApi, budgetKeys } from './api/budgetApi'
import {
  commitmentApi,
  commitmentKeys,
  type ApprovalActionResponse,
  type ApprovalActionType,
  type CommitmentResponse,
  type CommitmentStatus,
} from './api/commitmentApi'
import { APPROVAL_STATUS_LABEL, COMMITMENT_STATUS_COLOR, COMMITMENT_STATUS_LABEL } from './presentation'

const ACTION_LABEL: Record<ApprovalActionType, string> = {
  SUBMIT: '提交',
  REQUEST_INFO: '要求补充',
  RESUBMIT: '重新提交',
  APPROVE: '批准',
  REJECT: '拒绝',
  CANCEL: '取消',
}

export function BudgetCommitmentDetailPage() {
  const { commitmentId } = useParams<{ commitmentId: string }>()
  const navigate = useNavigate()

  const commitmentQuery = useQuery({
    queryKey: commitmentKeys.detail(commitmentId!),
    queryFn: () => commitmentApi.get(commitmentId!),
    enabled: !!commitmentId,
  })

  // The commitment response carries no currency; it comes from the owning
  // budget, which is itself BUDGET_READ-gated on the backend.
  const budgetQuery = useQuery({
    queryKey: budgetKeys.detail(commitmentQuery.data?.budgetId ?? ''),
    queryFn: () => budgetApi.get(commitmentQuery.data!.budgetId),
    enabled: !!commitmentQuery.data?.budgetId,
  })

  const problem = commitmentQuery.error ? toProblemDetail(commitmentQuery.error) : null

  if (problem) {
    return (
      <main className="settings-page">
        <header className="page-header"><h1>承诺详情</h1></header>
        <Alert
          type="error"
          showIcon
          title="无法加载承诺"
          description={(
            <>
              <div>{`${problem.title}（${problem.code}）`}</div>
              {problem.detail && <div>{problem.detail}</div>}
            </>
          )}
        />
      </main>
    )
  }

  if (commitmentQuery.isLoading || !commitmentQuery.data) return <Card loading={commitmentQuery.isLoading} />
  const commitment = commitmentQuery.data
  const currency = budgetQuery.data?.currency ?? ''

  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>{`承诺详情 #${commitment.id}`}</h1>
        <Button onClick={() => navigate('/budgets/' + commitment.budgetId)}>返回预算</Button>
      </header>
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Card
          title="承诺信息"
          size="small"
          extra={<Tag color={COMMITMENT_STATUS_COLOR[commitment.status]}>{COMMITMENT_STATUS_LABEL[commitment.status]}</Tag>}
        >
          <Descriptions column={2} size="small">
            <Descriptions.Item label="Requested">{`${commitment.requestedAmount} ${currency}`}</Descriptions.Item>
            <Descriptions.Item label="Approved">{commitment.approvedAmount === null ? '—' : `${commitment.approvedAmount} ${currency}`}</Descriptions.Item>
            <Descriptions.Item label="Remaining">{commitment.remainingAmount === null ? '—' : `${commitment.remainingAmount} ${currency}`}</Descriptions.Item>
            <Descriptions.Item label="Budget ID">{commitment.budgetId}</Descriptions.Item>
            <Descriptions.Item label="Approval Status">{commitment.approvalStatus === null ? '—' : APPROVAL_STATUS_LABEL[commitment.approvalStatus]}</Descriptions.Item>
            <Descriptions.Item label="Version">{`v${commitment.version}`}</Descriptions.Item>
          </Descriptions>
        </Card>
        <Card title="审批历史" size="small">
          <HistoryTable history={commitment.history} />
        </Card>
      </Space>
    </main>
  )
}

function HistoryTable({ history }: { history: ApprovalActionResponse[] }) {
  return (
    <Table<ApprovalActionResponse>
      rowKey="id"
      dataSource={history}
      pagination={false}
      scroll={{ x: 760 }}
      columns={[
        { title: '时间', dataIndex: 'createdAt', width: 190 },
        { title: '动作', dataIndex: 'actionType', width: 110, render: (value: ApprovalActionType) => ACTION_LABEL[value] },
        { title: '从', dataIndex: 'fromState', width: 130, render: (value: string | null) => value ?? '—' },
        { title: '到', dataIndex: 'toState', width: 130, render: (value: string | null) => value ?? '—' },
        { title: '操作人', dataIndex: 'actorMemberId', width: 110 },
        { title: '备注', dataIndex: 'comment', width: 160, render: (value: string | null) => value ?? '—' },
      ]}
    />
  )
}
