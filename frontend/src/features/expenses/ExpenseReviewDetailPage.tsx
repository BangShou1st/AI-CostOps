import { EXPENSE_STATUS_LABEL as STATUS_LABEL, EXPENSE_STATUS_COLOR as STATUS_COLOR } from './presentation'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Input, Modal, Space, Tag } from 'antd'
import { expenseApi, expenseKeys } from './api/expenseApi'
import { ApprovalHistory } from './components/ApprovalHistory'
import { ExpenseEvidenceSection } from './components/ExpenseEvidenceSection'
import { problemDetail as presentProblemDetail, toProblemDetail } from '../../api/problem'
import { formatBusinessDate } from '../../lib/dateTime'
import { formatMoney } from '../../lib/money'
import { hasPermission } from '../auth/permissions'
import { useAuth } from '../auth/AuthSessionProvider'
import { allocationApi } from '../allocation/api/allocationApi'
import { allocationKeys } from '../allocation/api/allocationKeys'
import { AllocationEditor } from '../allocation/AllocationEditor'
import { PostingAction } from '../ledger/PostingAction'
import { ledgerApi } from '../ledger/api/ledgerApi'
import { ledgerKeys } from '../ledger/api/ledgerKeys'
import type { CommitmentLinkRequest } from '../ledger/api/ledgerApi'
import { CommitmentLinkPicker } from '../ledger/CommitmentLinkPicker'
import { useExpenseCommand } from './hooks/useExpenseCommand'


export function ExpenseReviewDetailPage() {
  const { expenseId } = useParams<{ expenseId: string }>()
  const auth = useAuth()
  const canAllocate = hasPermission(auth.user?.permissions, 'ALLOCATION_EDIT')
  const canConfirm = hasPermission(auth.user?.permissions, 'ALLOCATION_CONFIRM')
  const canReadAllocation = hasPermission(auth.user?.permissions, 'ALLOCATION_READ')
  const canReview = hasPermission(auth.user?.permissions, 'EXPENSE_REVIEW')
  const canPost = hasPermission(auth.user?.permissions, 'EXPENSE_POST') && hasPermission(auth.user?.permissions, 'LEDGER_POST')
  const qc = useQueryClient()
  const execute = useExpenseCommand()
  const [problem, setProblem] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [comment, setComment] = useState('')
  const [rejectModal, setRejectModal] = useState(false)
  const [requestInfoModal, setRequestInfoModal] = useState(false)
  const [commitmentLinks, setCommitmentLinks] = useState<CommitmentLinkRequest[]>([])

  const { data: expense, isLoading, error, refetch: reload } = useQuery({
    queryKey: expenseKeys.reviewDetail(expenseId!),
    queryFn: () => expenseApi.getForReview(expenseId!),
    enabled: !!expenseId,
  })

  // The allocation decisions are read from the backend (never guessed from
  // local state), so a Finance reviewer can continue a MANUAL DRAFT that was
  // created earlier and confirm it. Posting readiness is re-read from the
  // backend after every mutation; it is never faked from frontend state.
  const { data: decisions, error: allocationError, isLoading: allocationLoading, refetch: reloadAllocation } = useQuery({
    queryKey: allocationKeys.byExpense(expenseId!),
    queryFn: () => allocationApi.listDecisionsByExpense(expenseId!),
    enabled: !!expenseId && canReadAllocation,
  })

  const refetch = useCallback(() => {
    qc.invalidateQueries({ queryKey: expenseKeys.reviewDetail(expenseId!) })
    qc.invalidateQueries({ queryKey: allocationKeys.byExpense(expenseId!) })
    qc.invalidateQueries({ queryKey: ledgerKeys.lists() })
  }, [qc, expenseId])

  if (error) return <Alert type="error" showIcon title="审核详情加载失败"
    description={presentProblemDetail(toProblemDetail(error))}
    action={<Button onClick={() => { void reload() }}>重试</Button>} />
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
      const body = { expectedVersion: expense.version }
      await execute(`approve:${expense.id}`, body, key => expenseApi.approve(expense.id, body, key))
      refetch()
    } catch (e) { setProblem(presentProblemDetail(toProblemDetail(e)) ?? '操作失败') }
    finally { setLoading(false) }
  }

  const handleReject = async () => {
    setLoading(true); setProblem(null)
    try {
      const body = { expectedVersion: expense.version, comment }
      await execute(`reject:${expense.id}`, body, key => expenseApi.reject(expense.id, body, key))
      refetch(); setRejectModal(false); setComment('')
    } catch (e) { setProblem(presentProblemDetail(toProblemDetail(e)) ?? '操作失败') }
    finally { setLoading(false) }
  }

  const handleRequestInfo = async () => {
    setLoading(true); setProblem(null)
    try {
      const body = { expectedVersion: expense.version, comment }
      await execute(`requestInfo:${expense.id}`, body, key => expenseApi.requestInfo(expense.id, body, key))
      refetch(); setRequestInfoModal(false); setComment('')
    } catch (e) { setProblem(presentProblemDetail(toProblemDetail(e)) ?? '操作失败') }
    finally { setLoading(false) }
  }

  return (
    <Space orientation="vertical" style={{ width: '100%' }} size="middle">
      {problem && <Alert type="error" showIcon title={problem} closable onClose={() => setProblem(null)}
        action={<Button onClick={() => { void reload() }}>刷新详情</Button>} />}
      <Card title={`报销审核 ${expense.id}`} extra={
        <Space>
          <Tag color={STATUS_COLOR[expense.status]}>{STATUS_LABEL[expense.status]}</Tag>
          {expense.status === 'APPROVED' && expense.postingReady && canPost && !allocationError && !allocationLoading && (
            <>
              <CommitmentLinkPicker
                lines={confirmedDecision?.lines ?? []}
                effectiveAt={expense.expenseDate}
                value={commitmentLinks}
                onChange={setCommitmentLinks}
              />
              <PostingAction label="记账" onPost={() => ledgerApi.postExpense(expense.id, commitmentLinks)} onCompleted={refetch} />
            </>
          )}
        </Space>
      }>
        <Descriptions column={2} size="small">
          <Descriptions.Item label="日期">{formatBusinessDate(expense.expenseDate)}</Descriptions.Item>
          <Descriptions.Item label="金额">{formatMoney(expense.amount, expense.currency)}</Descriptions.Item>
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
      {allocationError && <Alert type="error" showIcon title="分摊信息加载失败"
        description={presentProblemDetail(toProblemDetail(allocationError))}
        action={<Button onClick={() => { void reloadAllocation() }}>重试</Button>} />}
      {expense.status === 'APPROVED' && canReview && canReadAllocation && canAllocate && !allocationError && !allocationLoading && (
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
