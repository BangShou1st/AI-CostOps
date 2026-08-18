import { useCallback, useMemo } from 'react'
import { Button, Table, Typography } from 'antd'
import {
  allocationApi,
  type AllocationLineInput,
  type AllocationTargetRef,
  type AllocationTargetType,
} from '../api/allocationApi'
import { allocationKeys } from '../api/allocationKeys'
import {
  compareDecimal8,
  formatDecimal8,
  parseDecimal8,
  parseUserDecimal8,
  subtractDecimal8,
  sumDecimal8,
} from '../../../lib/money'
import { useQuery } from '@tanstack/react-query'

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
  PROJECT: 'project', COST_CENTER: 'costCenter', TEAM: 'team',
}

const DIRECTORY_TYPE_BY_LINE: Record<AllocationEditorLine['targetType'], AllocationTargetType> = {
  project: 'PROJECT', costCenter: 'COST_CENTER', team: 'TEAM',
}

function toOptionValue(type: AllocationTargetType, id: string): string {
  return `${type}:${id}`
}

function fromOptionValue(value: string): Pick<AllocationEditorLine, 'targetType' | 'targetId'> | null {
  const [type, id] = value.split(':')
  if (id === undefined || !(type in TARGET_TYPE_BY_DIRECTORY)) return null
  return { targetType: TARGET_TYPE_BY_DIRECTORY[type as AllocationTargetType], targetId: id }
}

export function initialLines(lines: Array<{ allocatedAmount: string; projectId: string | null; costCenterId: string | null; teamId: string | null }>): AllocationEditorLine[] {
  return lines.map((line, index) => ({
    key: index,
    allocatedAmount: line.allocatedAmount,
    targetType: line.projectId ? 'project' : line.costCenterId ? 'costCenter' : 'team',
    targetId: line.projectId ?? line.costCenterId ?? line.teamId,
  }))
}

export function toLineInputs(lines: AllocationEditorLine[], currency: string): AllocationLineInput[] {
  return lines.map((line) => ({
    // The API only accepts exact scale-8 strings; typed amounts such as 129.5
    // are normalized here so a valid user entry never throws mid-submit.
    allocatedAmount: formatDecimal8(parseUserDecimal8(line.allocatedAmount)),
    currency,
    projectId: line.targetType === 'project' ? line.targetId : null,
    costCenterId: line.targetType === 'costCenter' ? line.targetId : null,
    teamId: line.targetType === 'team' ? line.targetId : null,
  }))
}

function safeParse(value: string): bigint | null {
  try { return parseDecimal8(value) } catch { return null }
}

function groupTargets(refs: AllocationTargetRef[]): Record<AllocationTargetType, TargetOption[]> {
  const groups: Record<AllocationTargetType, TargetOption[]> = { PROJECT: [], COST_CENTER: [], TEAM: [] }
  for (const ref of refs) { if (ref.type in groups) groups[ref.type].push({ id: ref.id, label: ref.name }) }
  return groups
}

function nextKey(lines: AllocationEditorLine[]): number {
  return lines.reduce((max, line) => Math.max(max, line.key), -1) + 1
}

interface AllocationLinesEditorProps {
  sourceAmount: string
  currency: string
  lines: AllocationEditorLine[]
  setLines: React.Dispatch<React.SetStateAction<AllocationEditorLine[]>>
  editable: boolean
}

/** Core line editing UI shared by charge and expense allocation. */
export function AllocationLinesEditor({ sourceAmount, currency, lines, setLines, editable }: AllocationLinesEditorProps) {
  const targets = useQuery({
    queryKey: allocationKeys.targets(),
    queryFn: () => allocationApi.listTargets(),
  })
  const targetGroups = useMemo(() => groupTargets(targets.data ?? []), [targets.data])

  const source = useMemo(() => parseDecimal8(sourceAmount), [sourceAmount])
  const parsedAmounts = useMemo(() => lines.map((line) => safeParse(line.allocatedAmount)), [lines])
  const allocated = useMemo(() => sumDecimal8(parsedAmounts.filter((v): v is bigint => v !== null)), [parsedAmounts])
  const remaining = subtractDecimal8(source, allocated)

  const remainingLabel = compareDecimal8(remaining, 0n) === 0
    ? '精确分配'
    : remaining > 0n ? `未分配金额：${formatDecimal8(remaining)}` : `超额分配：${formatDecimal8(-remaining)}`

  const updateLine = useCallback((key: number, patch: Partial<AllocationEditorLine>) => {
    setLines((current) => current.map((line) => (line.key === key ? { ...line, ...patch } : line)))
  }, [setLines])

  return (
    <>
      <Typography.Text strong>
        来源金额：{sourceAmount} {currency}
        {'　'}已分配：{formatDecimal8(allocated)} {currency}
        {'　'}{remainingLabel} {currency}
      </Typography.Text>
      <Table<AllocationEditorLine>
        rowKey="key" size="small" dataSource={lines} pagination={false}
        locale={{ emptyText: '尚无分摊行' }}
        columns={[
          { title: '金额', width: 200, render: (_, line) => (
            <input aria-label={`第 ${line.key + 1} 行金额`} value={line.allocatedAmount}
              disabled={!editable}
              onChange={(e) => updateLine(line.key, { allocatedAmount: e.target.value })}
              onBlur={() => {
                try {
                  updateLine(line.key, { allocatedAmount: formatDecimal8(parseUserDecimal8(line.allocatedAmount)) })
                } catch {
                  // Keep the raw text; the submit path reports the format error.
                }
              }}
              placeholder="0.00000000" />
          )},
          { title: '币种', width: 90, render: () => currency },
          { title: '分摊对象', render: (_, line) => (
            <select aria-label={`第 ${line.key + 1} 行分摊对象`}
              disabled={!editable}
              value={line.targetId !== null && line.targetType in DIRECTORY_TYPE_BY_LINE
                ? toOptionValue(DIRECTORY_TYPE_BY_LINE[line.targetType], line.targetId) : ''}
              onChange={(e) => {
                const sel = e.target.value ? fromOptionValue(e.target.value) : null
                updateLine(line.key, sel ? { targetType: sel.targetType, targetId: sel.targetId } : { targetId: null })
              }}>
              <option value="">选择项目 / 成本中心 / 团队</option>
              <optgroup label="项目">{targetGroups.PROJECT.map((o) => <option key={`P:${o.id}`} value={toOptionValue('PROJECT', o.id)}>{o.label}</option>)}</optgroup>
              <optgroup label="成本中心">{targetGroups.COST_CENTER.map((o) => <option key={`C:${o.id}`} value={toOptionValue('COST_CENTER', o.id)}>{o.label}</option>)}</optgroup>
              <optgroup label="团队">{targetGroups.TEAM.map((o) => <option key={`T:${o.id}`} value={toOptionValue('TEAM', o.id)}>{o.label}</option>)}</optgroup>
            </select>
          )},
          { title: '', width: 80, render: (_, line) => (
            <Button danger size="small" disabled={!editable}
              onClick={() => setLines((current) => current.filter((item) => item.key !== line.key))}>删除</Button>
          )},
        ]}
      />
      {editable && (
        <Button onClick={() => setLines((current) => [...current, { key: nextKey(current), allocatedAmount: '', targetType: 'project', targetId: null }])}>
          添加分摊行
        </Button>
      )}
    </>
  )
}

