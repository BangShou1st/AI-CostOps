import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Space, Table, Typography } from 'antd'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../api/problem'
import {
  allocationApi,
  type AllocationDecision,
  type AllocationLineInput,
  type AllocationTargetRef,
  type AllocationTargetType,
} from './api/allocationApi'
import { allocationKeys } from './api/allocationKeys'
import {
  compareDecimal8,
  formatDecimal8,
  parseDecimal8,
  subtractDecimal8,
  sumDecimal8,
} from '../../lib/money'

export interface TargetOption {
  id: string
  label: string
}

export interface AllocationEditorLine {
  key: number
  allocatedAmount: string
  targetType: 'project' | 'costCenter' | 'team'
  targetId: string | null
}

const TARGET_TYPE_BY_DIRECTORY: Record<AllocationTargetType, AllocationEditorLine['targetType']> = {
  PROJECT: 'project',
  COST_CENTER: 'costCenter',
  TEAM: 'team',
}

const DIRECTORY_TYPE_BY_LINE: Record<AllocationEditorLine['targetType'], AllocationTargetType> = {
  project: 'PROJECT',
  costCenter: 'COST_CENTER',
  team: 'TEAM',
}

/** Encodes both facets of the selection so switching groups updates targetType too. */
function toOptionValue(type: AllocationTargetType, id: string): string {
  return `${type}:${id}`
}

function fromOptionValue(value: string): Pick<AllocationEditorLine, 'targetType' | 'targetId'> | null {
  const [type, id] = value.split(':')
  if (id === undefined || !(type in TARGET_TYPE_BY_DIRECTORY)) return null
  return { targetType: TARGET_TYPE_BY_DIRECTORY[type as AllocationTargetType], targetId: id }
}

interface AllocationEditorProps {
  chargeId: string
  chargeAmount: string
  chargeCurrency: string
  reviewStatus: string
  draft: AllocationDecision | null
  canEdit: boolean
  canConfirm: boolean
  /** True once any decision of this charge is CONFIRMED: creation/proposal actions hide. */
  hasConfirmed?: boolean
  /** Parent refetch after any successful mutation: UI returns to backend truth. */
  onChanged: () => void
  onProposalApplied: () => void
}

export function AllocationEditor({
  chargeId,
  chargeAmount,
  chargeCurrency,
  reviewStatus,
  draft,
  canEdit,
  canConfirm,
  hasConfirmed = false,
  onChanged,
  onProposalApplied,
}: AllocationEditorProps) {
  const [lines, setLines] = useState<AllocationEditorLine[]>(initialLines(draft))
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // Backend truth wins: whenever the parent refetches a changed draft the
  // local edits are replaced instead of silently diverging.
  useEffect(() => {
    setLines(initialLines(draft))
  }, [draft])

  const ruleDraft = draft !== null && draft.source === 'RULE'
  const editable = canEdit && !ruleDraft && !hasConfirmed

  const targets = useQuery({
    queryKey: allocationKeys.targets(),
    queryFn: () => allocationApi.listTargets(),
  })
  const targetGroups = useMemo(() => groupTargets(targets.data ?? []), [targets.data])

  const source = useMemo(() => parseDecimal8(chargeAmount), [chargeAmount])
  const parsedAmounts = useMemo(
    () => lines.map((line) => safeParse(line.allocatedAmount)),
    [lines],
  )
  const allocated = useMemo(
    () => sumDecimal8(parsedAmounts.filter((value): value is bigint => value !== null)),
    [parsedAmounts],
  )
  const remaining = subtractDecimal8(source, allocated)
  const shapeValid = lines.length > 0
    && parsedAmounts.every((value) => value !== null)
    && lines.every((line) => line.targetId !== null)
  const remainingExact = compareDecimal8(remaining, 0n) === 0
  const suspectedDuplicate = reviewStatus === 'SUSPECTED_DUPLICATE'
  const confirmEnabled = canConfirm
    && shapeValid
    && remainingExact
    && !suspectedDuplicate
    && draft !== null
    && !submitting

  const remainingLabel = remainingExact
    ? '精确分配'
    : remaining > 0n
      ? `未分配金额：${formatDecimal8(remaining)}`
      : `超额分配：${formatDecimal8(-remaining)}`

  const refresh = useCallback(() => {
    setProblem(null)
    onChanged()
  }, [onChanged])

  const saveDraft = async () => {
    if (!canEdit || !shapeValid || submitting) return
    setSubmitting(true)
    setProblem(null)
    try {
      const input = toLineInputs(lines, chargeCurrency)
      if (draft && draft.source === 'MANUAL') {
        await allocationApi.replaceLines(draft.id, input)
      } else {
        await allocationApi.createManualDraft(chargeId, input, crypto.randomUUID())
      }
      refresh()
    } catch (error) {
      setProblem(toProblemDetail(error))
    } finally {
      setSubmitting(false)
    }
  }

  const confirmDecision = async () => {
    if (!draft || !confirmEnabled || submitting) return
    setSubmitting(true)
    setProblem(null)
    try {
      await allocationApi.confirm(draft.id, crypto.randomUUID())
      refresh()
    } catch (error) {
      setProblem(toProblemDetail(error))
    } finally {
      setSubmitting(false)
    }
  }

  const runProposal = async () => {
    if (!editable || submitting || draft) return
    setSubmitting(true)
    setProblem(null)
    try {
      await allocationApi.propose(chargeId, crypto.randomUUID())
      refresh()
      onProposalApplied()
    } catch (error) {
      setProblem(toProblemDetail(error))
    } finally {
      setSubmitting(false)
    }
  }

  // A RULE draft is server-owned: overriding it snapshots its read-only lines
  // into a fresh MANUAL draft (the backend supersedes the rule draft).
  const overrideRuleDraft = async () => {
    if (!canEdit || !ruleDraft || !shapeValid || submitting) return
    setSubmitting(true)
    setProblem(null)
    try {
      await allocationApi.createManualDraft(chargeId, toLineInputs(lines, chargeCurrency), crypto.randomUUID())
      refresh()
    } catch (error) {
      setProblem(toProblemDetail(error))
    } finally {
      setSubmitting(false)
    }
  }

  const updateLine = (key: number, patch: Partial<AllocationEditorLine>) => {
    setLines((current) => current.map((line) => (line.key === key ? { ...line, ...patch } : line)))
  }

  return (
    <section aria-label="分摊编辑">
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Typography.Text strong>
          来源金额：{chargeAmount} {chargeCurrency}
          {'　'}已分配：{formatDecimal8(allocated)} {chargeCurrency}
          {'　'}{remainingLabel} {chargeCurrency}
        </Typography.Text>

        {suspectedDuplicate && (
          <Alert
            type="warning"
            showIcon
            message="疑似重复待处理"
            description="该成本被标记为疑似重复，需先在重复审核中处理，确认分摊被阻止。"
          />
        )}

        {ruleDraft && (
          <Alert
            type="info"
            showIcon
            message="规则草案"
            description="该分摊由规则生成，行内容只读；可直接确认，或手动覆盖生成 MANUAL 草稿后编辑。"
          />
        )}

        {problem && (
          <Alert
            type="error"
            showIcon
            message={`${problem.title}（${problem.code}）`}
            description={problem.detail}
          />
        )}

        <Table<AllocationEditorLine>
          rowKey="key"
          size="small"
          dataSource={lines}
          pagination={false}
          locale={{ emptyText: '尚无分摊行' }}
          columns={[
            {
              title: '金额',
              width: 200,
              render: (_, line) => (
                <input
                  aria-label={`第 ${line.key + 1} 行金额`}
                  value={line.allocatedAmount}
                  disabled={ruleDraft}
                  onChange={(event) => updateLine(line.key, { allocatedAmount: event.target.value })}
                  placeholder="0.00000000"
                />
              ),
            },
            {
              title: '币种',
              width: 90,
              render: () => chargeCurrency,
            },
            {
              title: '分摊对象',
              render: (_, line) => (
                <select
                  aria-label={`第 ${line.key + 1} 行分摊对象`}
                  disabled={ruleDraft}
                  value={line.targetId !== null && line.targetType in DIRECTORY_TYPE_BY_LINE
                    ? toOptionValue(DIRECTORY_TYPE_BY_LINE[line.targetType], line.targetId)
                    : ''}
                  onChange={(event) => {
                    const selection = event.target.value ? fromOptionValue(event.target.value) : null
                    updateLine(line.key, selection
                      ? { targetType: selection.targetType, targetId: selection.targetId }
                      : { targetId: null })
                  }}
                >
                  <option value="">选择项目 / 成本中心 / 团队</option>
                  <optgroup label="项目">
                    {targetGroups.PROJECT.map((option) => (
                      <option key={`PROJECT:${option.id}`} value={toOptionValue('PROJECT', option.id)}>{option.label}</option>
                    ))}
                  </optgroup>
                  <optgroup label="成本中心">
                    {targetGroups.COST_CENTER.map((option) => (
                      <option key={`COST_CENTER:${option.id}`} value={toOptionValue('COST_CENTER', option.id)}>{option.label}</option>
                    ))}
                  </optgroup>
                  <optgroup label="团队">
                    {targetGroups.TEAM.map((option) => (
                      <option key={`TEAM:${option.id}`} value={toOptionValue('TEAM', option.id)}>{option.label}</option>
                    ))}
                  </optgroup>
                </select>
              ),
            },
            {
              title: '',
              width: 80,
              render: (_, line) => (
                <Button
                  danger
                  size="small"
                  disabled={ruleDraft}
                  onClick={() => setLines((current) => current.filter((item) => item.key !== line.key))}
                >
                  删除
                </Button>
              ),
            },
          ]}
        />

        <Space>
          {editable && (
            <>
              <Button
                onClick={() =>
                  setLines((current) => [
                    ...current,
                    { key: nextKey(current), allocatedAmount: '', targetType: 'project', targetId: null },
                  ])}
              >
                添加分摊行
              </Button>
              <Button type="primary" disabled={!shapeValid || submitting} loading={submitting} onClick={saveDraft}>
                {draft ? '保存分摊' : '创建分摊草稿'}
              </Button>
              <Button disabled={submitting || draft !== null} loading={submitting} onClick={runProposal}>
                按规则生成
              </Button>
            </>
          )}
          {canEdit && ruleDraft && (
            <Button disabled={!shapeValid || submitting} loading={submitting} onClick={overrideRuleDraft}>
              手动覆盖
            </Button>
          )}
          {canConfirm && (
            <Button
              type="primary"
              danger
              disabled={!confirmEnabled}
              loading={submitting}
              onClick={confirmDecision}
              title={suspectedDuplicate ? '疑似重复成本不可确认分摊' : undefined}
            >
              确认分摊
            </Button>
          )}
        </Space>
      </Space>
    </section>
  )
}

function initialLines(draft: AllocationDecision | null): AllocationEditorLine[] {
  if (!draft) return []
  return draft.lines.map((line, index) => ({
    key: index,
    allocatedAmount: line.allocatedAmount,
    targetType: line.projectId ? 'project' : line.costCenterId ? 'costCenter' : 'team',
    targetId: line.projectId ?? line.costCenterId ?? line.teamId,
  }))
}

function toLineInputs(lines: AllocationEditorLine[], currency: string): AllocationLineInput[] {
  return lines.map((line) => ({
    allocatedAmount: formatDecimal8(parseDecimal8(line.allocatedAmount)),
    currency,
    projectId: line.targetType === 'project' ? line.targetId : null,
    costCenterId: line.targetType === 'costCenter' ? line.targetId : null,
    teamId: line.targetType === 'team' ? line.targetId : null,
  }))
}

function safeParse(value: string): bigint | null {
  try {
    return parseDecimal8(value)
  } catch {
    return null
  }
}

function groupTargets(refs: AllocationTargetRef[]): Record<AllocationTargetType, TargetOption[]> {
  const groups: Record<AllocationTargetType, TargetOption[]> = {
    PROJECT: [],
    COST_CENTER: [],
    TEAM: [],
  }
  for (const ref of refs) {
    if (ref.type in groups) {
      groups[ref.type].push({ id: ref.id, label: ref.name })
    }
  }
  return groups
}

function nextKey(lines: AllocationEditorLine[]): number {
  return lines.reduce((max, line) => Math.max(max, line.key), -1) + 1
}
