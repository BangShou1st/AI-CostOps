import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Space, Tag, Modal } from 'antd'
import { expenseApi, expenseKeys } from './api/expenseApi'
import { ExpenseForm } from './components/ExpenseForm'
import { ApprovalHistory } from './components/ApprovalHistory'
import { ExpenseEvidenceUpload } from './components/ExpenseEvidenceUpload'
import { toProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { AllocationEditor } from '../allocation/AllocationEditor'

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', NEEDS_INFO: '需补充', APPROVED: '已批准', REJECTED: '已拒绝', CANCELED: '已取消',
}
const STATUS_COLOR: Record<string, string> = {
  DRAFT: 'default', SUBMITTED: 'processing', NEEDS_INFO: 'warning', APPROVED: 'success', REJECTED: 'error', CANCELED: 'default',
}

export function ExpenseDetailPage() {
  const { expenseId } = useParams<{ expenseId: string }>()
  const auth = useAuth()
  const canAllocate = hasPermission(auth.user?.permissions, 'ALLOCATION_EDIT')
  const canConfirm = hasPermission(auth.user?.permissions, 'ALLOCATION_CONFIRM')
  const qc = useQueryClient()
  const [problem, setProblem] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [cancelModal, setCancelModal] = useState(false)

  const { data: expense, isLoading } = useQuery({
    queryKey: expenseKeys.detail(expenseId!),
    queryFn: () => expenseApi.get(expenseId!),
    enabled: !!expenseId,
  })

  const refetch = useCallback(() => {
    qc.invalidateQueries({ queryKey: expenseKeys.detail(expenseId!) })
  }, [qc, expenseId])

  if (isLoading || !expense) return <Card loading={isLoading} />
  const canEdit = expense.canEdit

  const submit = async () => {
    setLoading(true); setProblem(null)
    try {
      await expenseApi.submit(expense.id, { expectedVersion: expense.version }, crypto.randomUUID())
      refetch()
    } catch (e) { setProblem(toProblemDetail(e).detail ?? '提交失败') }
    finally { setLoading(false) }
  }

  const handleEdit = async (body: { expenseDate: string; amount: string; currency: string }) => {
    setLoading(true); setProblem(null)
    try {
      await expenseApi.edit(expense.id, { ...body, expectedVersion: expense.version })
      refetch()
    } catch (e) { setProblem(toProblemDetail(e).detail ?? '保存失败') }
    finally { setLoading(false) }
  }

  const handleCancel = async () => {
    setLoading(true); setProblem(null)
    try {
      await expenseApi.cancel(expense.id, { expectedVersion: expense.version }, crypto.randomUUID())
      refetch(); setCancelModal(false)
    } catch (e) { setProblem(toProblemDetail(e).detail ?? '取消失败') }
    finally { setLoading(false) }
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      {problem && <Alert type="error" showIcon message={problem} closable onClose={() => setProblem(null)} />}
      <Card title={`报销 ${expense.id}`} extra={
        <Tag color={STATUS_COLOR[expense.status]}>{STATUS_LABEL[expense.status]}</Tag>
      }>
        <Descriptions column={2} size="small">
          <Descriptions.Item label="日期">{expense.expenseDate}</Descriptions.Item>
          <Descriptions.Item label="金额">{expense.amount} {expense.currency}</Descriptions.Item>
          <Descriptions.Item label="版本">v{expense.version}</Descriptions.Item>
          <Descriptions.Item label="审核状态">{expense.approvalStatus ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="发布就绪">{expense.postingReady ? '✓' : '否'}</Descriptions.Item>
          <Descriptions.Item label="提交时间">{'-'}</Descriptions.Item>
        </Descriptions>
      </Card>
      {(canEdit || expense.status === 'DRAFT' || expense.status === 'NEEDS_INFO') && (
        <Card title="编辑">
          <ExpenseForm
            expenseDate={expense.expenseDate}
            amount={expense.amount}
            currency={expense.currency}
            editable={canEdit}
            onSubmit={handleEdit}
            loading={loading}
          />
          <div style={{ marginTop: 16 }}>
            <ExpenseEvidenceUpload
              expenseId={expense.id}
              evidenceId={expense.evidenceId}
              expectedVersion={expense.version}
              onChanged={refetch}
            />
          </div>
        </Card>
      )}
      <Card title="操作" size="small">
        <Space>
          {expense.status === 'DRAFT' && (
            <Button type="primary" loading={loading} onClick={submit} disabled={!expense.evidenceId}>
              提交
            </Button>
          )}
          {expense.status === 'SUBMITTED' && (
            <Button danger loading={loading} onClick={() => setCancelModal(true)}>
              取消
            </Button>
          )}
          {expense.status === 'NEEDS_INFO' && (
            <Button type="primary" loading={loading} onClick={submit} disabled={!expense.evidenceId}>
              重新提交
            </Button>
          )}
        </Space>
      </Card>
      <Card title="审批历史" size="small">
        <ApprovalHistory history={expense.history} />
      </Card>
      {expense.status === 'APPROVED' && canAllocate && (
        <Card title="手动分摊" size="small">
          <AllocationEditor
            chargeId=""
            subjectType="EXPENSE_CLAIM"
            subjectId={expense.id}
            subjectAmount={expense.amount}
            subjectCurrency={expense.currency}
            draft={null}
            canEdit={true}
            canConfirm={canConfirm}
            onChanged={refetch}
            onProposalApplied={() => {}}
            hasConfirmed={expense.currentAllocationDecisionId !== null}
          />
        </Card>
      )}
      <Modal
        title="确认取消报销"
        open={cancelModal}
        onOk={handleCancel}
        onCancel={() => setCancelModal(false)}
        okButtonProps={{ loading }}
      >
        <p>确定取消这笔报销吗？</p>
      </Modal>
    </Space>
  )
}
