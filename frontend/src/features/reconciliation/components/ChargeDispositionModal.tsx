import { useMutation } from '@tanstack/react-query'
import { Form, Input, Modal, Select } from 'antd'
import { useState } from 'react'
import { reconciliationApi } from '../api/reconciliationApi'
import { useIdempotentCommand } from '../../../lib/useIdempotentCommand'
import { CaseError } from './CaseError'
import type { ReconciliationEvidenceResponse } from '../types'

interface Props {
  caseId: string
  target: ReconciliationEvidenceResponse | null
  onClose: () => void
  onSuccess: () => void
  onError: (error: unknown) => void
}

export function ChargeDispositionModal({ caseId, target, onClose, onSuccess, onError }: Props) {
  const [dispositionValue, setDispositionValue] = useState<'RECONCILIATION_EVIDENCE' | 'DIRECT_PROVIDER_CHARGE'>('RECONCILIATION_EVIDENCE')
  const [dispositionReasonCode, setDispositionReasonCode] = useState('')
  const [dispositionReasonNote, setDispositionReasonNote] = useState('')
  const execute = useIdempotentCommand()
  const decideDisposition = useMutation({
    mutationFn: () => {
      const body = {
        chargeFactId: target?.chargeFactId ?? '',
        disposition: dispositionValue,
        reasonCode: dispositionReasonCode.trim(),
        reasonNote: dispositionReasonNote.trim(),
      }
      return execute(`charge-disposition:${caseId}`, body, key => reconciliationApi.decideChargeDisposition(caseId, body, key))
    },
    retry: false,
    onMutate: () => onError(null),
    onError,
    onSuccess: () => {
      onClose()
      setDispositionValue('RECONCILIATION_EVIDENCE')
      setDispositionReasonCode('')
      setDispositionReasonNote('')
      onSuccess()
    },
  })

  return (
    <Modal
      open={target !== null}
      title={`决定费用处理方式（费用 #${target?.chargeFactId ?? ''}）`}
      okText={decideDisposition.isPending ? '正在提交…' : '确认决定'}
      okButtonProps={{ disabled: !dispositionReasonCode.trim() || !dispositionReasonNote.trim() || decideDisposition.isPending }}
      onOk={() => decideDisposition.mutate()}
      onCancel={() => { setDispositionValue('RECONCILIATION_EVIDENCE'); onClose() }}
      closable={!decideDisposition.isPending}
      keyboard={!decideDisposition.isPending}
      maskClosable={!decideDisposition.isPending}
      cancelButtonProps={{ disabled: decideDisposition.isPending }}
    >
      <CaseError error={decideDisposition.error} />
      <Form layout="vertical" disabled={decideDisposition.isPending}>
        <Form.Item label="处理方式" required>
          <Select
            value={dispositionValue}
            options={[
              { value: 'RECONCILIATION_EVIDENCE', label: 'RECONCILIATION_EVIDENCE（作为对账证据，不再直接入账）' },
              { value: 'DIRECT_PROVIDER_CHARGE', label: 'DIRECT_PROVIDER_CHARGE（作为直接供应商费用入账）' },
            ]}
            onChange={(value) => setDispositionValue(value)}
            aria-label="费用处理方式"
          />
        </Form.Item>
        <Form.Item label="原因代码" required>
          <Input value={dispositionReasonCode} maxLength={64} onChange={(event) => setDispositionReasonCode(event.target.value)} aria-label="处理原因代码" />
        </Form.Item>
        <Form.Item label="说明" required>
          <Input.TextArea value={dispositionReasonNote} maxLength={2000} rows={3} onChange={(event) => setDispositionReasonNote(event.target.value)} aria-label="处理说明" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
