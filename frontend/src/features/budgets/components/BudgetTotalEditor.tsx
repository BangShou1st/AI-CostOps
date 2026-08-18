import { Alert, Button, Descriptions, Input, Modal, Space } from 'antd'
import { useState } from 'react'
import { toProblemDetail } from '../../../api/problem'
import { formatDecimal8, parseUserDecimal8 } from '../../../lib/money'
import { budgetApi, type BudgetResponse } from '../api/budgetApi'

/**
 * Budget total change is a sensitive financial action: the user sees the
 * current total, currency and the normalized new total before confirming.
 * Only BUDGET_MANAGE holders ever see the entry point (UX only; the backend
 * remains the security boundary). A 409 never auto-retries the mutation: it
 * asks the user to refresh the latest version and refetches instead.
 */
export function BudgetTotalEditor({ budget, onChanged }: { budget: BudgetResponse; onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  const [input, setInput] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  let newAmount: bigint | null = null
  if (input.trim() !== '') {
    try {
      newAmount = parseUserDecimal8(input.trim())
    } catch {
      newAmount = null
    }
  }
  const inputInvalid = input.trim() !== '' && newAmount === null
  const nonPositive = newAmount !== null && newAmount <= 0n
  const canSubmit = newAmount !== null && !nonPositive

  const close = () => {
    setOpen(false)
    setInput('')
    setError(null)
  }

  const submit = async () => {
    if (newAmount === null || !canSubmit) return
    setLoading(true)
    setError(null)
    try {
      await budgetApi.update(budget.id, {
        totalAmount: formatDecimal8(newAmount),
        expectedVersion: budget.version,
      })
      close()
      onChanged()
    } catch (e) {
      const problem = toProblemDetail(e)
      setError(problem.code === 'STATE_CONFLICT'
        ? 'Budget has changed. Refresh the latest version before retrying.'
        : (problem.detail ?? '操作失败'))
      // A conflict means our version is stale: refresh the latest version,
      // but never re-send the mutation automatically.
      if (problem.status === 409) onChanged()
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)}>修改总额</Button>
      <Modal
        title="修改预算总额"
        open={open}
        onOk={() => void submit()}
        onCancel={close}
        okText="确认修改"
        cancelText="取消"
        okButtonProps={{ loading, disabled: !canSubmit }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="Current Total">{`${budget.totalAmount} ${budget.currency}`}</Descriptions.Item>
            <Descriptions.Item label="Currency">{budget.currency}</Descriptions.Item>
            <Descriptions.Item label="New Total">{newAmount === null ? '—' : `${formatDecimal8(newAmount)} ${budget.currency}`}</Descriptions.Item>
          </Descriptions>
          <Input
            aria-label="新的总额"
            value={input}
            placeholder="例如 150.5"
            onChange={(event) => setInput(event.target.value)}
          />
          {inputInvalid && <div style={{ color: '#cf1322' }}>请输入有效金额（最多 8 位小数）</div>}
          {nonPositive && <div style={{ color: '#cf1322' }}>总额必须大于 0</div>}
          {error && <Alert type="error" showIcon message={error} />}
        </Space>
      </Modal>
    </>
  )
}
