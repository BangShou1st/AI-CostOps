import { useQuery, useMutation } from '@tanstack/react-query'
import { Alert, Button, Input, Modal, Select, Space, Typography } from 'antd'
import { useState } from 'react'
import { problemDetail as presentProblemDetail, problemSummary, toProblemDetail } from '../../api/problem'
import { billingPeriodApi, billingPeriodKeys } from '../budgets/api/billingPeriodApi'
import { ledgerApi, type LedgerEntryResponse } from './api/ledgerApi'

export function CorrectionAction({ entry, onCompleted }: { entry: LedgerEntryResponse; onCompleted: () => void }) {
  const [open, setOpen] = useState(false)
  const [mode, setMode] = useState<'REVERSAL_ONLY' | 'REPLACE'>('REVERSAL_ONLY')
  const [correctionPeriodId, setCorrectionPeriodId] = useState<string>()
  const [reasonCode, setReasonCode] = useState('ALLOCATION_ERROR')
  const [reasonText, setReasonText] = useState('')
  const [amount, setAmount] = useState('')
  const [currency, setCurrency] = useState(entry.currency)
  const [targetType, setTargetType] = useState<'PROJECT' | 'COST_CENTER' | 'TEAM'>('PROJECT')
  const [targetId, setTargetId] = useState('')
  const periods = useQuery({ queryKey: billingPeriodKeys.list(), queryFn: billingPeriodApi.list, enabled: open })
  const mutation = useMutation({
    mutationFn: () => ledgerApi.correct({
      targetEntryId: entry.id,
      correctionPeriodId: correctionPeriodId!,
      mode,
      reasonCode,
      reasonText: reasonText || null,
      replacement: mode === 'REPLACE' ? {
        amount,
        currency: currency.toUpperCase(),
        projectId: targetType === 'PROJECT' ? targetId : null,
        costCenterId: targetType === 'COST_CENTER' ? targetId : null,
        teamId: targetType === 'TEAM' ? targetId : null,
      } : null,
    }, crypto.randomUUID()),
    retry: false,
    onSuccess: () => { setOpen(false); onCompleted() },
  })
  const problem = mutation.error ? toProblemDetail(mutation.error) : null
  const replacementValid = mode === 'REVERSAL_ONLY' || (!!amount && !!currency && !!targetId)
  const canSubmit = !!correctionPeriodId && !!reasonCode.trim() && replacementValid && !mutation.isPending
  return (
    <>
      <Button onClick={() => { mutation.reset(); setOpen(true) }}>纠正</Button>
      <Modal
        open={open}
        title="创建账本纠正"
        okText={mutation.isPending ? '正在提交…' : '提交纠正'}
        okButtonProps={{ disabled: !canSubmit, loading: mutation.isPending }}
        onOk={() => mutation.mutate()}
        onCancel={() => setOpen(false)}
      >
        <Space orientation="vertical" style={{ width: '100%' }}>
          <Alert type="info" showIcon message="原始分录不可修改" description={`${entry.amount} ${entry.currency} · ${entry.targetType} ${entry.targetId}`} />
          {periods.error && <Alert type="error" title={problemSummary(toProblemDetail(periods.error))} />}
          <label>纠正账期<Select style={{ width: '100%' }} placeholder="选择 OPEN 账期" value={correctionPeriodId} onChange={setCorrectionPeriodId} loading={periods.isLoading} options={(periods.data ?? []).filter((period) => period.status === 'OPEN').map((period) => ({ value: period.id, label: `${period.id} · ${period.periodStart} ~ ${period.periodEnd}` }))} /></label>
          <label>模式<Select style={{ width: '100%' }} value={mode} onChange={setMode} options={[{ value: 'REVERSAL_ONLY', label: '仅反转' }, { value: 'REPLACE', label: '反转并替换' }]} /></label>
          <label>原因代码<Input value={reasonCode} onChange={(event) => setReasonCode(event.target.value)} maxLength={64} /></label>
          <label>原因说明<Input.TextArea value={reasonText} onChange={(event) => setReasonText(event.target.value)} maxLength={2000} rows={3} /></label>
          {mode === 'REPLACE' && <>
            <Typography.Text strong>替换分录（币种必须为 {entry.currency}）</Typography.Text>
            <label>金额<Input value={amount} onChange={(event) => setAmount(event.target.value)} placeholder="0.00000000" /></label>
            <label>币种<Input value={currency} onChange={(event) => setCurrency(event.target.value)} maxLength={3} /></label>
            <label>目标类型<Select style={{ width: '100%' }} value={targetType} onChange={setTargetType} options={[{ value: 'PROJECT', label: '项目' }, { value: 'COST_CENTER', label: '成本中心' }, { value: 'TEAM', label: '团队' }]} /></label>
            <label>目标 ID<Input value={targetId} onChange={(event) => setTargetId(event.target.value)} /></label>
          </>}
          {problem && <Alert type="error" showIcon title={problemSummary(problem)} description={presentProblemDetail(problem) ?? undefined} />}
        </Space>
      </Modal>
    </>
  )
}
