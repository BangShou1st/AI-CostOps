import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Form, Input, Modal, Select, Typography } from 'antd'
import { useState } from 'react'
import { problemDetail, problemTitle, toProblemDetail } from '../../api/problem'
import { createIdempotencyKey } from './presentation'
import { reconciliationApi } from './api/reconciliationApi'
import { NO_CHARGE_PROOF_CODES } from './types'
import type { ReconciliationEvidenceResponse } from './types'

const NO_CHARGE_PROOF_LABEL: Record<string, string> = {
  PROVIDER_PORTAL_CONFIRMED_NO_CHARGE: '供应商门户确认无费用',
  PROVIDER_SUPPORT_CONFIRMED_NO_CHARGE: '供应商支持确认无费用',
  EXPLICIT_ZERO_PROVIDER_RECORD: '供应商明确零记录',
}

/**
 * Shared reviewed Gateway financial resolution workflow for one unresolved
 * evidence item, used by the run-level (case_id=NULL) list and the case
 * detail page. The adjustment amount is always server-derived; the binding
 * classification (exact vs manual) is derived by the server from the run
 * evidence, so the reviewer only supplies the business reason. The exact
 * correlation evidence of the target request is fetched through the
 * request-scoped server filter, so it never depends on the target row
 * happening to sit on the first generic evidence page. The caller supplies
 * the reconciliation-owned period context: an OPEN original period adjusts
 * into itself (no correctionPeriodId is ever sent), while a CLOSED original
 * period requires the reviewer to explicitly select another OPEN correction
 * period. CLOSING and unknown (loading/error/missing) contexts disable
 * submission instead of defaulting to OPEN. NO_CHARGE_CONFIRMED never sends
 * a correctionPeriodId.
 */
export function GatewayResolutionModal({
  runId,
  caseId,
  target,
  onClose,
  periodContext,
}: {
  runId: string
  /** Null for run-level unresolved work without a fabricated case. */
  caseId: string | null
  target: ReconciliationEvidenceResponse | null
  onClose: () => void
  /**
   * Reconciliation-owned period context (RECONCILIATION_READ only, never
   * BUDGET_READ). Unknown covers loading/error/missing and disables
   * submission; the server remains the period state machine authority.
   */
  periodContext: {
    originalPeriodId: string | null
    status: 'OPEN' | 'CLOSING' | 'CLOSED' | 'UNKNOWN'
    eligibleCorrectionPeriods: { id: string }[]
  }
}) {
  const queryClient = useQueryClient()
  const [resolutionType, setResolutionType] = useState<'NO_CHARGE_CONFIRMED' | 'STATEMENT_ADJUSTMENT_POSTED'>('NO_CHARGE_CONFIRMED')
  const [statementChargeId, setStatementChargeId] = useState('')
  const [proofCode, setProofCode] = useState<string>(NO_CHARGE_PROOF_CODES[0])
  const [evidenceReference, setEvidenceReference] = useState('')
  const [reasonCode, setReasonCode] = useState('')
  const [reasonNote, setReasonNote] = useState('')
  const [correctionPeriodId, setCorrectionPeriodId] = useState('')
  const targetRequestId = target?.gatewayRequestId ?? null
  const originalPeriodId = periodContext.originalPeriodId
  const periodStatus = periodContext.status
  const originalPeriodClosed = periodStatus === 'CLOSED'
  // Fail closed: CLOSING and unknown (loading/error/missing) contexts never
  // fall back to the OPEN workflow; submission stays disabled instead.
  const periodBlocksSubmit = periodStatus === 'CLOSING' || periodStatus === 'UNKNOWN'
  const correctionPeriodOptions = periodContext.eligibleCorrectionPeriods
    .filter((candidate) => candidate.id !== originalPeriodId)
    .map((candidate) => ({ value: candidate.id, label: `#${candidate.id} · OPEN` }))
  const exactQuery = useQuery({
    queryKey: ['reconciliation', 'run', runId, 'evidence', 'exact', targetRequestId],
    queryFn: () => reconciliationApi.listRunEvidence(runId, {
      matchKind: 'EXACT_PROVIDER_REQUEST',
      gatewayRequestId: targetRequestId!,
      page: 0,
      size: 5,
    }),
    enabled: targetRequestId !== null,
    retry: false,
  })
  const exactEvidence = targetRequestId === null
    ? undefined
    : (exactQuery.data?.items ?? []).find((row) => row.gatewayRequestId === targetRequestId)
  const mutation = useMutation({
    mutationFn: () => {
      if (!target?.gatewayRequestId) throw new Error('missing request')
      return reconciliationApi.postGatewayResolution(runId, {
        caseId,
        requestId: target.gatewayRequestId,
        resolutionType,
        statementChargeFactId: resolutionType === 'STATEMENT_ADJUSTMENT_POSTED'
          ? (statementChargeId.trim() || null)
          : null,
        positiveEvidenceReference: resolutionType === 'NO_CHARGE_CONFIRMED'
          ? evidenceReference.trim()
          : null,
        // Only a CLOSED original period carries an explicit OPEN correction
        // period; the server remains the period state machine authority.
        correctionPeriodId: resolutionType === 'STATEMENT_ADJUSTMENT_POSTED' && originalPeriodClosed
          ? correctionPeriodId
          : null,
        reasonCode: resolutionType === 'NO_CHARGE_CONFIRMED' ? proofCode : reasonCode.trim(),
        reasonNote: reasonNote.trim(),
      }, createIdempotencyKey())
    },
    retry: false,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['reconciliation'] })
      onClose()
      setStatementChargeId('')
      setEvidenceReference('')
      setReasonCode('')
      setReasonNote('')
      setCorrectionPeriodId('')
    },
  })

  return (
    <Modal
      open={target !== null}
      title={`网关财务处理（请求 #${target?.gatewayRequestId ?? ''}）`}
      okText={mutation.isPending ? '正在提交…' : '确认提交'}
      okButtonProps={{
        disabled: resolutionType === 'STATEMENT_ADJUSTMENT_POSTED'
          ? periodBlocksSubmit
            || (!exactEvidence && !statementChargeId.trim())
            || (originalPeriodClosed && !correctionPeriodId)
            || !reasonCode.trim() || !reasonNote.trim() || mutation.isPending
          : periodBlocksSubmit
            || !evidenceReference.trim() || !reasonNote.trim() || mutation.isPending,
      }}
      onOk={() => mutation.mutate()}
      onCancel={() => { onClose(); mutation.reset() }}
      width={640}
    >
      {periodBlocksSubmit && (
        <Alert
          type="warning"
          showIcon
          className="m6-section-card"
          title={periodStatus === 'CLOSING' ? '账期正在关闭，暂时无法处理' : '账期信息加载失败，暂时无法处理'}
          description={periodStatus === 'CLOSING'
            ? '账期正在关闭，暂时无法处理，请稍后再试。'
            : '账期信息加载失败，请稍后重试。'}
        />
      )}
      {resolutionType === 'STATEMENT_ADJUSTMENT_POSTED' ? (
        <Alert
          type="info"
          showIcon
          className="m6-section-card"
          title="调整金额由服务端推导"
          description="金额由系统自动计算，无需填写。"
        />
      ) : (
        <Alert
          type="info"
          showIcon
          className="m6-section-card"
          title="仅账单缺失不能证明无费用"
          description="请选择证明方式并填写出处（如工单号），此操作不产生账务变动。"
        />
      )}
      {exactEvidence && (
        <Alert
          type="success"
          showIcon
          className="m6-section-card"
          title={`精确请求关联（费用 #${exactEvidence.chargeFactId ?? '?'}）`}
          description="已自动关联账单费用，无需填写。"
        />
      )}
      <Form layout="vertical">
        <Form.Item label="处理类型" required>
          <Select
            value={resolutionType}
            options={[
              { value: 'NO_CHARGE_CONFIRMED', label: '确认无费用' },
              { value: 'STATEMENT_ADJUSTMENT_POSTED', label: '按账单调整' },
            ]}
            onChange={(value) => setResolutionType(value)}
            aria-label="网关处理类型"
          />
        </Form.Item>
        {resolutionType === 'STATEMENT_ADJUSTMENT_POSTED' && (
          originalPeriodClosed ? (
            <Form.Item label="调整入账账期（原账期已关闭，必须选择其他开启账期）" required>
              <Select
                value={correctionPeriodId || undefined}
                placeholder="选择 OPEN 的调整入账账期"
                options={correctionPeriodOptions}
                onChange={(value) => setCorrectionPeriodId(value)}
                aria-label="调整入账账期"
              />
            </Form.Item>
          ) : (
            <Form.Item label="入账账期">
              <Typography.Text type="secondary">
                {originalPeriodId ? `原账期 #${originalPeriodId}（开启账期，调整固定入原账期）` : '由服务端按原账期入账'}
              </Typography.Text>
            </Form.Item>
          )
        )}
        {resolutionType === 'STATEMENT_ADJUSTMENT_POSTED' ? (
          <Form.Item
            label={exactEvidence
              ? '绑定的账单费用编号（精确关联已推导，可不填）'
              : '账单费用编号'}
            required={!exactEvidence}
          >
            <Input value={statementChargeId} maxLength={24} onChange={(event) => setStatementChargeId(event.target.value)} aria-label="账单费用编号" />
          </Form.Item>
        ) : (
          <>
            <Form.Item label="积极证明类型" required>
              <Select
                value={proofCode}
                options={NO_CHARGE_PROOF_CODES.map((code) => ({ value: code, label: NO_CHARGE_PROOF_LABEL[code] ?? code }))}
                onChange={(value) => setProofCode(value)}
                aria-label="积极证明类型"
              />
            </Form.Item>
            <Form.Item label="证明出处（如门户截图编号 / 工单号，6-256 字符）" required>
              <Input value={evidenceReference} maxLength={256} onChange={(event) => setEvidenceReference(event.target.value)} aria-label="证明出处" />
            </Form.Item>
          </>
        )}
        {resolutionType === 'STATEMENT_ADJUSTMENT_POSTED' && (
          <Form.Item label="业务原因代码（绑定分类由服务端推导）" required>
            <Input value={reasonCode} maxLength={64} onChange={(event) => setReasonCode(event.target.value)} aria-label="业务原因代码" />
          </Form.Item>
        )}
        <Form.Item label="说明" required>
          <Input.TextArea value={reasonNote} maxLength={2000} rows={3} onChange={(event) => setReasonNote(event.target.value)} aria-label="网关处理说明" />
        </Form.Item>
        <Typography.Text type="secondary">
          提交后不可修改，且仅处理本条请求。
        </Typography.Text>
      </Form>
      {mutation.error && (
        <Alert type="error" showIcon title={problemTitle(toProblemDetail(mutation.error))} description={problemDetail(toProblemDetail(mutation.error))} />
      )}
    </Modal>
  )
}
