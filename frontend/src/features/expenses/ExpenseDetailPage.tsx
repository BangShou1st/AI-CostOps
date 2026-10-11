import { EXPENSE_STATUS_LABEL as STATUS_LABEL, EXPENSE_STATUS_COLOR as STATUS_COLOR } from './presentation'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Space, Tag, Modal } from 'antd'
import { expenseApi, expenseKeys, type ApprovalActionResponse } from './api/expenseApi'
import { ExpenseForm } from './components/ExpenseForm'
import { ApprovalHistory } from './components/ApprovalHistory'
import { ExpenseEvidenceSection } from './components/ExpenseEvidenceSection'
import { problemDetail as presentProblemDetail, toProblemDetail } from '../../api/problem'
import { formatBusinessDate, formatEventDateTime } from '../../lib/dateTime'
import { formatMoney } from '../../lib/money'
import { useExpenseCommand } from './hooks/useExpenseCommand'


/** The API has no submittedAt field; the last SUBMIT/RESUBMIT action is the
 *  authoritative submit instant (the employee detail page and the approval
 *  history must agree). Returns null when the expense was never submitted. */
function lastSubmittedAt(history: ApprovalActionResponse[]): string | null {
  for (let index = history.length - 1; index >= 0; index -= 1) {
    const action = history[index]
    if (action.actionType === 'SUBMIT' || action.actionType === 'RESUBMIT') {
      return action.createdAt
    }
  }
  return null
}

export function ExpenseDetailPage() {
  const { expenseId } = useParams<{ expenseId: string }>()
  const qc = useQueryClient()
  const execute = useExpenseCommand()
  const [problem, setProblem] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [cancelModal, setCancelModal] = useState(false)

  const { data: expense, isLoading, error, refetch: reload } = useQuery({
    queryKey: expenseKeys.detail(expenseId!),
    queryFn: () => expenseApi.get(expenseId!),
    enabled: !!expenseId,
  })

  const refetch = useCallback(() => {
    qc.invalidateQueries({ queryKey: expenseKeys.detail(expenseId!) })
  }, [qc, expenseId])

  if (error) return <Alert type="error" showIcon title="报销详情加载失败"
    description={presentProblemDetail(toProblemDetail(error))}
    action={<Button onClick={() => { void reload() }}>重试</Button>} />
  if (isLoading || !expense) return <Card loading={isLoading} />
  const canEdit = expense.canEdit

  const submit = async () => {
    setLoading(true); setProblem(null)
    try {
      const body = { expectedVersion: expense.version }
      await execute(`submit:${expense.id}`, body, key => expenseApi.submit(expense.id, body, key))
      refetch()
    } catch (e) { setProblem(presentProblemDetail(toProblemDetail(e)) ?? '提交失败') }
    finally { setLoading(false) }
  }

  const handleEdit = async (body: { expenseDate: string; amount: string; currency: string }) => {
    setLoading(true); setProblem(null)
    try {
      await expenseApi.edit(expense.id, { ...body, expectedVersion: expense.version })
      refetch()
    } catch (e) { setProblem(presentProblemDetail(toProblemDetail(e)) ?? '保存失败') }
    finally { setLoading(false) }
  }

  const handleCancel = async () => {
    setLoading(true); setProblem(null)
    try {
      const body = { expectedVersion: expense.version }
      await execute(`cancel:${expense.id}`, body, key => expenseApi.cancel(expense.id, body, key))
      refetch(); setCancelModal(false)
    } catch (e) { setProblem(presentProblemDetail(toProblemDetail(e)) ?? '取消失败') }
    finally { setLoading(false) }
  }

  return (
    <Space orientation="vertical" style={{ width: '100%' }} size="middle">
      {problem && <Alert type="error" showIcon title={problem} closable onClose={() => setProblem(null)}
        action={<Button onClick={() => { void reload() }}>刷新详情</Button>} />}
      <Card title={`报销 ${expense.id}`} extra={
        <Tag color={STATUS_COLOR[expense.status]}>{STATUS_LABEL[expense.status]}</Tag>
      }>
        <Descriptions column={2} size="small">
          <Descriptions.Item label="日期">{formatBusinessDate(expense.expenseDate)}</Descriptions.Item>
          <Descriptions.Item label="金额">{formatMoney(expense.amount, expense.currency)}</Descriptions.Item>
          <Descriptions.Item label="版本">v{expense.version}</Descriptions.Item>
          <Descriptions.Item label="审核状态">{expense.approvalStatus ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="发布就绪">{expense.postingReady ? '✓' : '否'}</Descriptions.Item>
          <Descriptions.Item label="提交时间">
            {(() => {
              const submittedAt = lastSubmittedAt(expense.history)
              return formatEventDateTime(submittedAt)
            })()}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      {(canEdit || expense.status === 'DRAFT' || expense.status === 'NEEDS_INFO') && (
        <Card title="编辑">
          <ExpenseForm
            key={`${expense.id}:${expense.version}`}
            expenseDate={expense.expenseDate}
            amount={expense.amount}
            currency={expense.currency}
            editable={canEdit}
            onSubmit={handleEdit}
            loading={loading}
          />
        </Card>
      )}
      <Card title="凭证" size="small">
        <ExpenseEvidenceSection
          mode="employee"
          canUpload={expense.status === 'DRAFT' || expense.status === 'NEEDS_INFO'}
          expenseId={expense.id}
          evidenceId={expense.evidenceId}
          expectedVersion={expense.version}
          onChanged={refetch}
        />
      </Card>
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
