import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Input, Modal, Space, Table, Tag } from 'antd'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { toProblemDetail } from '../../api/problem'
import { hasPermission } from '../settings/permissions'
import { useAuth } from '../auth/AuthSessionProvider'
import { budgetApi, budgetKeys } from './api/budgetApi'
import {
  commitmentApi,
  commitmentKeys,
  type ApprovalActionResponse,
  type ApprovalActionType,
} from './api/commitmentApi'
import { APPROVAL_STATUS_LABEL, COMMITMENT_STATUS_COLOR, COMMITMENT_STATUS_LABEL, budgetCommandProblemMessage } from './presentation'

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

  const auth = useAuth()
  const qc = useQueryClient()
  const [actionProblem, setActionProblem] = useState<string | null>(null)
  const [actionLoading, setActionLoading] = useState(false)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [rejectComment, setRejectComment] = useState('')
  const [cancelOpen, setCancelOpen] = useState(false)
  const [releaseOpen, setReleaseOpen] = useState(false)

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

  // Every financial surface here (Requested/Approved/Remaining amounts and
  // the release confirmation) renders the owning budget's currency. The
  // commitment payload itself carries none, so the lifecycle action UI only
  // appears once the owning budget read model is available; a confirm dialog
  // with an empty currency must never be reachable (Sensitive Action UX).
  const budgetProblem = budgetQuery.error ? toProblemDetail(budgetQuery.error) : null
  if (budgetProblem) {
    return (
      <main className="settings-page">
        <header className="page-header"><h1>承诺详情</h1></header>
        <Alert
          type="error"
          showIcon
          title="无法加载关联预算"
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
  if (budgetQuery.isLoading || !budgetQuery.data) return <Card loading={budgetQuery.isLoading} />
  const currency = budgetQuery.data.currency

  const canApprove = hasPermission(auth.user?.permissions, 'COMMITMENT_APPROVE')
  const canRelease = hasPermission(auth.user?.permissions, 'COMMITMENT_RELEASE')
  const submitActor = commitment.history.find((action) => action.actionType === 'SUBMIT')?.actorMemberId ?? null
  const isRequester = submitActor !== null && submitActor === (auth.user?.organizationMemberId ?? null)
  const canReject = commitment.status === 'REQUESTED' && canApprove
  const canApproveNow = commitment.status === 'REQUESTED' && canApprove
  const canCancel = commitment.status === 'REQUESTED' && (isRequester || canApprove)
  const canReleaseNow = canRelease && (commitment.status === 'ACTIVE' || commitment.status === 'PARTIALLY_CONSUMED')
  const showActions = canReject || canApproveNow || canCancel || canReleaseNow

  // After any financial command the server read model is the truth: the
  // commitment detail, the budget's commitment list and the budget itself are
  // all refetched. Nothing is patched optimistically.
  const refreshAll = () => {
    qc.invalidateQueries({ queryKey: commitmentKeys.detail(commitment.id) })
    qc.invalidateQueries({ queryKey: commitmentKeys.byBudget(commitment.budgetId) })
    qc.invalidateQueries({ queryKey: budgetKeys.detail(commitment.budgetId) })
    qc.invalidateQueries({ queryKey: budgetKeys.lists() })
  }

  const runAction = async (action: () => Promise<unknown>) => {
    setActionLoading(true)
    setActionProblem(null)
    try {
      await action()
      refreshAll()
    } catch (e) {
      const problemDetail = toProblemDetail(e)
      setActionProblem(budgetCommandProblemMessage(problemDetail))
      // A 409 means our version is stale: refresh the latest state, but never
      // re-send the financial mutation automatically.
      if (problemDetail.status === 409) refreshAll()
    } finally {
      setActionLoading(false)
    }
  }

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
        {showActions && (
          <Card title="承诺操作" size="small">
            <Space>
              {canApproveNow && (
                <Button
                  type="primary"
                  loading={actionLoading}
                  onClick={() => void runAction(() => commitmentApi.approve(
                    commitment.id,
                    { expectedVersion: commitment.version },
                    crypto.randomUUID(),
                  ))}
                >
                  批准
                </Button>
              )}
              {canReject && (
                <Button loading={actionLoading} onClick={() => setRejectOpen(true)}>拒绝</Button>
              )}
              {canCancel && (
                <Button loading={actionLoading} onClick={() => setCancelOpen(true)}>取消申请</Button>
              )}
              {canReleaseNow && (
                <Button loading={actionLoading} onClick={() => setReleaseOpen(true)}>释放</Button>
              )}
            </Space>
          </Card>
        )}
        {actionProblem && (
          <Alert type="error" showIcon message={actionProblem} closable onClose={() => setActionProblem(null)} />
        )}
        <Card title="审批历史" size="small">
          <HistoryTable history={commitment.history} />
        </Card>
        <Modal
          title="拒绝承诺申请"
          open={rejectOpen}
          onOk={() => void runAction(async () => {
            await commitmentApi.reject(
              commitment.id,
              { expectedVersion: commitment.version, comment: rejectComment },
              crypto.randomUUID(),
            )
            setRejectOpen(false)
            setRejectComment('')
          })}
          onCancel={() => setRejectOpen(false)}
          okText="确认拒绝"
          cancelText="取消"
          okButtonProps={{ loading: actionLoading, danger: true }}
        >
          <Input.TextArea
            aria-label="拒绝原因"
            rows={3}
            maxLength={2000}
            value={rejectComment}
            placeholder="请输入拒绝原因"
            onChange={(event) => setRejectComment(event.target.value)}
          />
        </Modal>
        <Modal
          title="取消承诺申请"
          open={cancelOpen}
          onOk={() => void runAction(async () => {
            await commitmentApi.cancel(
              commitment.id,
              { expectedVersion: commitment.version },
              crypto.randomUUID(),
            )
            setCancelOpen(false)
          })}
          onCancel={() => setCancelOpen(false)}
          okText="确认取消"
          cancelText="再想想"
          okButtonProps={{ loading: actionLoading }}
        >
          <div>确认取消该承诺申请？此操作不会改变预算承诺金额。</div>
        </Modal>
        <Modal
          title="释放承诺"
          open={releaseOpen}
          onOk={() => void runAction(async () => {
            await commitmentApi.release(
              commitment.id,
              { expectedVersion: commitment.version },
              crypto.randomUUID(),
            )
            setReleaseOpen(false)
          })}
          onCancel={() => setReleaseOpen(false)}
          okText="确认释放"
          cancelText="取消"
          okButtonProps={{ loading: actionLoading, danger: true }}
        >
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="Commitment ID">{commitment.id}</Descriptions.Item>
            <Descriptions.Item label="Remaining Amount">
              {commitment.remainingAmount === null ? '—' : `${commitment.remainingAmount} ${currency}`}
            </Descriptions.Item>
            <Descriptions.Item label="Currency">{currency}</Descriptions.Item>
          </Descriptions>
        </Modal>
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
