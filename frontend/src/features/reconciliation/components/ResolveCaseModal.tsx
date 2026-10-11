import { useMutation } from '@tanstack/react-query'
import { Form, Input, Modal } from 'antd'
import { useState } from 'react'
import { reconciliationApi } from '../api/reconciliationApi'
import { useIdempotentCommand } from '../../../lib/useIdempotentCommand'
import { CaseError } from './CaseError'
import type { ReconciliationCaseResponse } from '../types'

interface Props {
  caseId: string
  open: boolean
  onClose: () => void
  onSuccess: (updated: ReconciliationCaseResponse) => void
  onPendingChange: (pending: boolean) => void
  onError: (error: unknown) => void
}

export function ResolveCaseModal({ caseId, open, onClose, onSuccess, onPendingChange, onError }: Props) {
  const [reasonCode, setReasonCode] = useState('')
  const [resolutionNote, setResolutionNote] = useState('')
  const execute = useIdempotentCommand()
  const resolve = useMutation({
    mutationFn: () => {
      const body = { reasonCode: reasonCode.trim(), resolutionNote: resolutionNote.trim() }
      return execute(`resolve-case:${caseId}`, body, key => reconciliationApi.resolveCase(caseId, body, key))
    },
    retry: false,
    onMutate: () => { onError(null); onPendingChange(true) },
    onError,
    onSettled: () => onPendingChange(false),
    onSuccess: (updated) => { onSuccess(updated); onClose(); setReasonCode(''); setResolutionNote('') },
  })

  return (
    <Modal
      open={open}
      title="接受已解释差异（ACCEPT_EXPLAINED_DIFFERENCE）"
      okText={resolve.isPending ? '正在提交…' : '确认解决'}
      okButtonProps={{ disabled: !reasonCode.trim() || !resolutionNote.trim() || resolve.isPending }}
      onOk={() => resolve.mutate()}
      onCancel={onClose}
      closable={!resolve.isPending}
      keyboard={!resolve.isPending}
      maskClosable={!resolve.isPending}
      cancelButtonProps={{ disabled: resolve.isPending }}
    >
      <CaseError error={resolve.error} />
      <Form layout="vertical" disabled={resolve.isPending}>
        <Form.Item label="处理原因" required>
          <Input aria-label="处理原因" value={reasonCode} maxLength={100} placeholder="请输入处理原因" onChange={(event) => setReasonCode(event.target.value)} />
        </Form.Item>
        <Form.Item label="解决说明" required>
          <Input.TextArea aria-label="解决说明" value={resolutionNote} maxLength={2000} rows={5} placeholder="请说明本次案例的处理结论" onChange={(event) => setResolutionNote(event.target.value)} />
        </Form.Item>
      </Form>
    </Modal>
  )
}
