import { useMutation, useQuery } from '@tanstack/react-query'
import { Alert, Form, Input, Modal, Select, Space } from 'antd'
import { useState } from 'react'
import { reconciliationApi } from '../api/reconciliationApi'
import { useIdempotentCommand } from '../../../lib/useIdempotentCommand'
import { CaseError } from './CaseError'
import { formatMoney } from '../../../lib/money'
import { allocationApi } from '../../allocation/api/allocationApi'

interface Props {
  caseId: string
  open: boolean
  currency: string
  requiredDifference: string
  periods: readonly { id: string; status: string }[]
  onClose: () => void
  onSuccess: () => void
  onError: (error: unknown) => void
}

export function CaseAdjustmentModal({ caseId, open, currency, requiredDifference, periods, onClose, onSuccess, onError }: Props) {
  // Keep the server-derived amount and the explicit allocation as decimal strings.
  const [adjustmentPeriodId, setAdjustmentPeriodId] = useState('')
  const [adjustmentScopeType, setAdjustmentScopeType] = useState<'PROJECT' | 'COST_CENTER' | 'TEAM'>('PROJECT')
  const [adjustmentScopeId, setAdjustmentScopeId] = useState('')
  const [adjustmentLineAmount, setAdjustmentLineAmount] = useState('')
  const [adjustmentReasonCode, setAdjustmentReasonCode] = useState('')
  const [adjustmentReasonNote, setAdjustmentReasonNote] = useState('')
  const execute = useIdempotentCommand()
  const targets = useQuery({ queryKey: ['allocation', 'targets'], queryFn: () => allocationApi.listTargets(), retry: false, enabled: open })
  const postAdjustment = useMutation({
    mutationFn: () => {
      const body = {
        amount: requiredDifference,
        adjustmentPeriodId,
        lines: [{ lineIndex: 0, scopeType: adjustmentScopeType, scopeId: adjustmentScopeId, amount: requiredDifference }],
        reasonCode: adjustmentReasonCode.trim(),
        reasonNote: adjustmentReasonNote.trim(),
      }
      return execute(`case-adjustment:${caseId}`, body, key => reconciliationApi.postCaseAdjustment(caseId, body, key))
    },
    retry: false,
    onMutate: () => onError(null),
    onError,
    onSuccess: () => {
      onClose()
      setAdjustmentPeriodId('')
      setAdjustmentScopeId('')
      setAdjustmentLineAmount('')
      setAdjustmentReasonCode('')
      setAdjustmentReasonNote('')
      onSuccess()
    },
  })
  const adjustmentInvalid = !adjustmentPeriodId.trim()
    || !adjustmentScopeId.trim()
    || !adjustmentLineAmount.trim()
    || adjustmentLineAmount.trim() !== requiredDifference
    || !adjustmentReasonCode.trim()
    || !adjustmentReasonNote.trim()
    || postAdjustment.isPending

  return (
    <Modal
      open={open}
      title="提交 CASE_FULL 调整"
      okText={postAdjustment.isPending ? '正在提交…' : '确认提交'}
      okButtonProps={{ disabled: adjustmentInvalid }}
      onOk={() => postAdjustment.mutate()}
      onCancel={onClose}
      closable={!postAdjustment.isPending}
      keyboard={!postAdjustment.isPending}
      maskClosable={!postAdjustment.isPending}
      cancelButtonProps={{ disabled: postAdjustment.isPending }}
      width={640}
    >
      <Alert
        type="info"
        showIcon
        className="m6-section-card"
        title={`服务端要求金额：${formatMoney(requiredDifference || null, currency)}`}
        description="调整金额必须等于当前外部金额 − 内部金额，且分配行金额之和必须精确等于该金额；提交后运行基准将失效，需要重新对账。"
      />
      <CaseError error={postAdjustment.error} />
      <Form layout="vertical" disabled={postAdjustment.isPending}>
        <Form.Item label="调整入账账期（必须为开启账期）" required>
          <Select
            value={adjustmentPeriodId || undefined}
            placeholder="选择调整账期"
            options={periods.filter((period) => period.status === 'OPEN').map((period) => ({ value: period.id, label: `#${period.id} · ${period.status}` }))}
            onChange={(value) => setAdjustmentPeriodId(value)}
            aria-label="调整账期"
          />
        </Form.Item>
        <Form.Item label="分配目标（恰好一个 PROJECT / COST_CENTER / TEAM）" required>
          <Space.Compact style={{ width: '100%' }}>
            <Select
              style={{ width: '40%', minWidth: 0 }}
              value={adjustmentScopeType}
              options={(["PROJECT", "COST_CENTER", "TEAM"] as const).map((value) => ({ value, label: value }))}
              onChange={(value) => { setAdjustmentScopeType(value); setAdjustmentScopeId('') }}
              aria-label="分配目标类型"
            />
            <Select
              style={{ width: '60%', minWidth: 0 }}
              showSearch
              value={adjustmentScopeId || undefined}
              placeholder="选择 ACTIVE 同组织目标"
              options={(targets.data ?? []).filter((target) => target.type === adjustmentScopeType).map((target) => ({ value: target.id, label: `#${target.id} · ${target.name}` }))}
              onChange={(value) => setAdjustmentScopeId(value)}
              aria-label="分配目标"
            />
          </Space.Compact>
        </Form.Item>
        <Form.Item label={`分配行金额（必须等于 ${formatMoney(requiredDifference || null, currency)}）`} required>
          <Input
            value={adjustmentLineAmount}
            maxLength={32}
            placeholder={requiredDifference || '0.00000000'}
            onChange={(event) => setAdjustmentLineAmount(event.target.value)}
            aria-label="分配行金额"
          />
        </Form.Item>
        <Form.Item label="原因代码" required>
          <Input value={adjustmentReasonCode} maxLength={64} onChange={(event) => setAdjustmentReasonCode(event.target.value)} aria-label="调整原因代码" />
        </Form.Item>
        <Form.Item label="说明" required>
          <Input.TextArea value={adjustmentReasonNote} maxLength={2000} rows={3} onChange={(event) => setAdjustmentReasonNote(event.target.value)} aria-label="调整说明" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
