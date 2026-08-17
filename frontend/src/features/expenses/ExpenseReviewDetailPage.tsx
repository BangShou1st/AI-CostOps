import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Input, Modal, Space, Tag } from 'antd'
import { expenseApi, expenseKeys } from './api/expenseApi'
import { ApprovalHistory } from './components/ApprovalHistory'
import { ExpenseEvidenceSection } from './components/ExpenseEvidenceSection'
import { toProblemDetail } from '../../api/problem'
import { hasPermission } from '../settings/permissions'
import { useAuth } from '../auth/AuthSessionProvider'
import { allocationApi } from '../allocation/api/allocationApi'
import { allocationKeys } from '../allocation/api/allocationKeys'
import { AllocationEditor } from '../allocation/AllocationEditor'

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', NEEDS_INFO: '需补充', APPROVED: '已批准', REJECTED: '已拒绝', CANCELED: '已取消',
}
const STATUS_COLOR: Record<string, string> = {
  DRAFT: 'default', SUBMITTED: 'processing', NEEDS_INFO: 'warning', APPROVED: 'success', REJECTED: 'error', CANCELED: 'default',
}

export function ExpenseReviewDetailPage() {
  const { expenseId } = useParams<{ expenseId: string }>()
  const auth = useAuth()
  const canAllocate = hasPermission(auth.user?.permissions, 'ALLOCATION_EDIT')
  const canConfirm = hasPermission(auth.user?.permissions, 'ALLOCATION_CONFIRM')
  const canReadAllocation = hasPermission(auth.user?.permissions, 'ALLOCATION_READ')
  const canReview = hasPermission(auth.user?.permissions, 'EXPENSE_REVIEW')
  const qc = useQueryClient()
  const [problem, setProblem] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [comment, setComment] = useState('')
  const [rejectModal, setRejectModal] = useState(false)
  const [requestInfoModal, setRequestInfoModal] = useState(false)

  const { data: expense, isLoading } = useQuery({
    queryKey: expenseKeys.reviewDetail(expenseId!),
    queryFn: () => expenseApi.getForReview(expenseId!),
    enabled: !!expenseId,
  })

  // The allocation decisions are read from the backend (never guessed from
  // local state), so a Finance reviewer can continue a MANUAL DRAFT that was
  // created earlier and confirm it. Posting readiness is re-read from the
  // backend after every mutation; it is never faked from frontend state.
  const { data: decisions } = useQuery({
    queryKey: allocationKeys.byExpense(expenseId!),
    queryFn: () => allocationApi.listDecisionsByExpense(expenseId!),
    enabled: !!expenseId && canReadAllocation,
  })

  const refetch = useCallback(() => {
    qc.invalidateQueries({ queryKey: expenseKeys.reviewDetail(expenseId!) })
    qc.invalidateQueries({ queryKey: allocationKeys.byExpense(expenseId!) })
  }, [qc, expenseId])

  if (isLoading || !expense) return <Card loading={isLoading} />
  const isSubmitted = expense.status === 'SUBMITTED'

  const allDecisions = decisions ?? []
  const manualDraft = allDecisions.find(
    (decision) => decision.source === 'MANUAL' && decision.status === 'DRAFT',
  ) ?? null
  const confirmedDecision = allDecisions.find((decision) => decision.status === 'CONFIRMED') ?? null
  const hasConfirmed = confirmedDecision !== null
  // The editor shows the current decision: an open MANUAL DRAFT while one
  // exists, otherwise the CONFIRMED decision as read-only truth. A confirmed
  // decision must never be rendered as "no allocation lines".
  const draft = manualDraft ?? confirmedDecision

  const handleApprove = async () => {
    setLoading(true); setProblem(null)
    try {
      await expenseApi.approve(expense.id, { expectedVersion: expense.version }, crypto.randomUUID())
      refetch()
    } catch (e) { setProblem(toProblemDetail(e).detail ?? '操作失败') }
    finally { setLoading(false) }
  }

  const handleReject = async () => {
    setLoading(true); setProblem(null)
    try {
      await expenseApi.reject(expense.id, { expectedVersion: expense.version, comment }, crypto.randomUUID())
      refetch(); setRejectModal(false); setComment('')
    } catch (e) { setProblem(toProblemDetail(e).detail ?? '操作失败') }
    finally { setLoading(false) }
  }

  const handleRequestInfo = async () => {
    setLoading(true); setProblem(null)
    try {
      await expenseApi.requestInfo(expense.id, { expectedVersion: expense.version, comment }, crypto.randomUUID())
      refetch(); setRequestInfoModal(false); setComment('')
    } catch (e) { setProblem(toProblemDetail(e).detail ?? '操作失败') }
    finally { setLoading(false) }
  }

  return (
    <Space orientation="vertical" style={{ width: '100%' }} size="middle">
      {problem && <Alert type="error" showIcon title={problem} closable onClose={() => setProblem(null)} />}
      <Card title={`报销审核 ${expense.id}`} extra={
        <Tag color={STATUS_COLOR[expense.status]}>{STATUS_LABEL[expense.status]}</Tag>
      }>
        <Descriptions column={2} size="small">
          <Descriptions.Item label="日期">{expense.expenseDate}</Descriptions.Item>
          <Descriptions.Item label="金额">{expense.amount} {expense.currency}</Descriptions.Item>
          <Descriptions.Item label="版本">v{expense.version}</Descriptions.Item>
          <Descriptions.Item label="发布就绪">{expense.postingReady ? '✓' : '否'}</Descriptions.Item>
        </Descriptions>
      </Card>
      {isSubmitted && canReview && (
        <Card title="审核操作" size="small">
          <Space>
            <Button type="primary" loading={loading} onClick={handleApprove}>批准</Button>
            <Button loading={loading} onClick={() => setRequestInfoModal(true)}>要求补充</Button>
            <Button danger loading={loading} onClick={() => setRejectModal(true)}>拒绝</Button>
          </Space>
        </Card>
      )}
      <Card title="凭证" size="small">
        <ExpenseEvidenceSection
          mode="finance"
          canUpload={false}
          expenseId={expense.id}
          evidenceId={expense.evidenceId}
          expectedVersion={expense.version}
          onChanged={refetch}
        />
      </Card>
      <Card title="审批历史" size="small">
        <ApprovalHistory history={expense.history} />
      </Card>
      {expense.status === 'APPROVED' && canReview && canReadAllocation && canAllocate && (
        <Card title="手动分摊" size="small">
          <AllocationEditor
            chargeId=""
            subjectType="EXPENSE_CLAIM"
            subjectId={expense.id}
            subjectAmount={expense.amount}
            subjectCurrency={expense.currency}
            draft={draft}
            canEdit={canAllocate}
            canConfirm={canConfirm}
            hasConfirmed={hasConfirmed}
            onChanged={refetch}
            onProposalApplied={refetch}
          />
        </Card>
      )}
      <Modal title="要求补充信息" open={requestInfoModal} onOk={handleRequestInfo} onCancel={() => setRequestInfoModal(false)} okButtonProps={{ loading }}>
        <Input.TextArea rows={3} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="请输入需要补充的信息" />
      </Modal>
      <Modal title="拒绝报销" open={rejectModal} onOk={handleReject} onCancel={() => setRejectModal(false)} okButtonProps={{ loading, danger: true }}>
        <Input.TextArea rows={3} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="请输入拒绝原因" />
      </Modal>
    </Space>
  )
}