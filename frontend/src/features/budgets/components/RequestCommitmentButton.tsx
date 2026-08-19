import { Alert, Button, Descriptions, Input, Modal, Space } from 'antd'
import { useState } from 'react'
import { toProblemDetail } from '../../../api/problem'
import { formatDecimal8, parseUserDecimal8 } from '../../../lib/money'
import { commitmentApi } from '../api/commitmentApi'
import type { BudgetResponse } from '../api/budgetApi'
import { budgetCommandProblemMessage } from '../presentation'

/**
 * Request a commitment against the open budget. The currency always comes
 * from the owning budget; the user never types it. The amount is normalized
 * to exact scale-8 (no binary float) and one Idempotency-Key is sent per
 * explicit user attempt. A 409 is displayed, never auto-retried.
 */
export function RequestCommitmentButton({ budget, onCreated }: { budget: BudgetResponse; onCreated: () => void }) {
  const [open, setOpen] = useState(false)
  const [input, setInput] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  let amount: bigint | null = null
  if (input.trim() !== '') {
    try {
      amount = parseUserDecimal8(input.trim())
    } catch {
      amount = null
    }
  }
  const inputInvalid = input.trim() !== '' && amount === null
  const nonPositive = amount !== null && amount <= 0n
  const canSubmit = amount !== null && !nonPositive

  const close = () => {
    setOpen(false)
    setInput('')
    setError(null)
  }

  const submit = async () => {
    if (amount === null || !canSubmit) return
    setLoading(true)
    setError(null)
    try {
      await commitmentApi.create(budget.id, {
        requestedAmount: formatDecimal8(amount),
        currency: budget.currency,
      }, crypto.randomUUID())
      close()
      onCreated()
    } catch (e) {
      const problem = toProblemDetail(e)
      setError(budgetCommandProblemMessage(problem))
      if (problem.status === 409) onCreated()
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)}>申请承诺</Button>
      <Modal
        title="申请承诺"
        open={open}
        onOk={() => void submit()}
        onCancel={close}
        okText="确认申请"
        cancelText="取消"
        okButtonProps={{ loading, disabled: !canSubmit }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="预算">{budget.id}</Descriptions.Item>
            <Descriptions.Item label="币种">{budget.currency}</Descriptions.Item>
          </Descriptions>
          <Input
            aria-label="承诺金额"
            value={input}
            placeholder="例如 60.5"
            onChange={(event) => setInput(event.target.value)}
          />
          {inputInvalid && <div style={{ color: '#cf1322' }}>请输入有效金额（最多 8 位小数）</div>}
          {nonPositive && <div style={{ color: '#cf1322' }}>金额必须大于 0</div>}
          {error && <Alert type="error" showIcon message={error} />}
        </Space>
      </Modal>
    </>
  )
}
