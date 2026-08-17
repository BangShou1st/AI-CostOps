import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Space } from 'antd'
import { toProblemDetail, type ProblemDetail } from '../../api/problem'
import {
  allocationApi,
  type AllocationDecision,
} from './api/allocationApi'
import {
  AllocationLinesEditor,
  initialLines,
  toLineInputs,
  type AllocationEditorLine,
} from './shared/AllocationLinesEditor'
import { parseDecimal8, sumDecimal8, compareDecimal8 } from '../../lib/money'

interface AllocationEditorProps {
  /** For charge subjects: the charge id; for expense subjects: pass '' */
  chargeId: string
  /** Subject type routing: CHARGE_FACT uses charge endpoints, EXPENSE_CLAIM uses expense endpoints. */
  subjectType?: 'CHARGE_FACT' | 'EXPENSE_CLAIM'
  /** For expense subjects: the expense id; ignored when subjectType is CHARGE_FACT */
  subjectId?: string
  /** Source amount: either the charge amount or expense amount */
  subjectAmount: string
  /** Source currency */
  subjectCurrency: string
  draft: AllocationDecision | null
  canEdit: boolean
  canConfirm: boolean
  hasConfirmed?: boolean
  onChanged: () => void
  onProposalApplied: () => void
  /** Charge-only: review status for suspected-duplicate gate; omitted for expense */
  reviewStatus?: string
}

export function AllocationEditor({
  chargeId,
  subjectType = 'CHARGE_FACT',
  subjectId,
  subjectAmount,
  subjectCurrency,
  draft,
  canEdit,
  canConfirm,
  hasConfirmed = false,
  onChanged,
  onProposalApplied,
  reviewStatus,
}: AllocationEditorProps) {
  const [lines, setLines] = useState<AllocationEditorLine[]>(initialLines(draft?.lines ?? []))
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => { setLines(initialLines(draft?.lines ?? [])) }, [draft])

  const ruleDraft = draft !== null && draft.source === 'RULE'
  const editable = canEdit && !ruleDraft && !hasConfirmed
  const suspectedDuplicate = reviewStatus === 'SUSPECTED_DUPLICATE'
  // Exact-sum check: sum of parsed lines must equal the source amount.
  const parsedAmounts = lines.map((l) => { try { return parseDecimal8(l.allocatedAmount) } catch { return null } })
  const exactSum = parsedAmounts.every((v) => v !== null) &&
    compareDecimal8(sumDecimal8(parsedAmounts.filter((v): v is bigint => v !== null)), parseDecimal8(subjectAmount)) === 0
  const confirmEnabled = canConfirm
    && lines.length > 0
    && lines.every((l) => l.targetId !== null)
    && exactSum
    && draft !== null
    && !hasConfirmed
    && !suspectedDuplicate
    && !submitting

  const refresh = useCallback(() => { setProblem(null); onChanged() }, [onChanged])

  const saveDraft = async () => {
    if (!canEdit || submitting) return
    setSubmitting(true); setProblem(null)
    try {
      const input = toLineInputs(lines, subjectCurrency)
      if (draft && draft.source === 'MANUAL') {
        await allocationApi.replaceLines(draft.id, input)
      } else if (subjectType === 'EXPENSE_CLAIM' && subjectId) {
        await allocationApi.createManualDraftForExpense(subjectId, input, crypto.randomUUID())
      } else {
        await allocationApi.createManualDraft(chargeId, input, crypto.randomUUID())
      }
      refresh()
    } catch (e) { setProblem(toProblemDetail(e)) }
    finally { setSubmitting(false) }
  }

  const confirmDecision = async () => {
    if (!draft || !confirmEnabled) return
    setSubmitting(true); setProblem(null)
    try {
      await allocationApi.confirm(draft.id, crypto.randomUUID())
      refresh()
    } catch (e) { setProblem(toProblemDetail(e)) }
    finally { setSubmitting(false) }
  }

  const runProposal = async () => {
    if (!editable || submitting || draft) return
    setSubmitting(true); setProblem(null)
    try {
      await allocationApi.propose(chargeId, crypto.randomUUID())
      refresh(); onProposalApplied()
    } catch (e) { setProblem(toProblemDetail(e)) }
    finally { setSubmitting(false) }
  }

  const overrideRuleDraft = async () => {
    if (!canEdit || !ruleDraft || submitting) return
    setSubmitting(true); setProblem(null)
    try {
      const input = toLineInputs(lines, subjectCurrency)
      if (subjectType === 'EXPENSE_CLAIM' && subjectId) {
        await allocationApi.createManualDraftForExpense(subjectId, input, crypto.randomUUID())
      } else {
        await allocationApi.createManualDraft(chargeId, input, crypto.randomUUID())
      }
      refresh()
    } catch (e) { setProblem(toProblemDetail(e)) }
    finally { setSubmitting(false) }
  }

  return (
    <section aria-label="分摊编辑">
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {suspectedDuplicate && (
          <Alert type="warning" showIcon message="疑似重复待处理"
            description="该成本被标记为疑似重复，需先在重复审核中处理，确认分摊被阻止。" />
        )}
        {ruleDraft && (
          <Alert type="info" showIcon message="规则草案"
            description="该分摊由规则生成，行内容只读；可直接确认，或手动覆盖生成 MANUAL 草稿后编辑。" />
        )}
        {problem && (
          <Alert type="error" showIcon
            message={`${problem.title}（${problem.code}）`}
            description={problem.detail} />
        )}
        <AllocationLinesEditor
          sourceAmount={subjectAmount}
          currency={subjectCurrency}
          lines={lines}
          setLines={setLines}
          editable={editable}
        />
        <Space>
          {editable && (
            <>
              <Button type="primary" disabled={!lines.length || lines.some((l) => !l.targetId) || submitting} loading={submitting} onClick={saveDraft}>
                {draft ? '保存分摊' : '创建分摊草稿'}
              </Button>
              {subjectType === 'CHARGE_FACT' && (
                <Button disabled={submitting || draft !== null} loading={submitting} onClick={runProposal}>
                  按规则生成
                </Button>
              )}
            </>
          )}
          {canEdit && ruleDraft && (
            <Button disabled={!lines.length || submitting} loading={submitting} onClick={overrideRuleDraft}>
              手动覆盖
            </Button>
          )}
          {canConfirm && (
            <Button type="primary" danger disabled={!confirmEnabled} loading={submitting} onClick={confirmDecision}
              title={suspectedDuplicate ? '疑似重复成本不可确认分摊' : undefined}>
              确认分摊
            </Button>
          )}
        </Space>
      </Space>
    </section>
  )
}
