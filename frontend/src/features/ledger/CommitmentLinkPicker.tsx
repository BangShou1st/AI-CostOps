import { useQueries, useQuery } from '@tanstack/react-query'
import { Select, Space, Spin, Typography } from 'antd'
import type { AllocationLine } from '../allocation/api/allocationApi'
import { billingPeriodApi, billingPeriodKeys } from '../budgets/api/billingPeriodApi'
import { budgetApi, budgetKeys, type BudgetResponse } from '../budgets/api/budgetApi'
import { commitmentApi, commitmentKeys, type CommitmentResponse } from '../budgets/api/commitmentApi'
import type { CommitmentLinkRequest } from './api/ledgerApi'
import type { PageResponse } from '../../api/pagination'

interface CommitmentLinkPickerProps {
  lines: AllocationLine[]
  effectiveAt: string | null
  value: CommitmentLinkRequest[]
  onChange: (links: CommitmentLinkRequest[]) => void
}

type Target = { scopeType: 'PROJECT' | 'COST_CENTER' | 'TEAM'; scopeId: string }

function targetOf(line: AllocationLine): Target | null {
  if (line.projectId) return { scopeType: 'PROJECT', scopeId: line.projectId }
  if (line.costCenterId) return { scopeType: 'COST_CENTER', scopeId: line.costCenterId }
  if (line.teamId) return { scopeType: 'TEAM', scopeId: line.teamId }
  return null
}

function isPositive(amount: string): boolean {
  return !amount.trim().startsWith('-') && /[1-9]/.test(amount)
}

function periodContains(periodStart: string, periodEnd: string, effectiveAt: string): boolean {
  const point = new Date(effectiveAt).getTime()
  const start = new Date(periodStart).getTime()
  const end = new Date(periodEnd).getTime()
  return Number.isFinite(point) && Number.isFinite(start) && Number.isFinite(end)
    && start <= point && point < end
}

function budgetForLine(line: AllocationLine, budgets: BudgetResponse[]): BudgetResponse | null {
  const target = targetOf(line)
  if (!target) return null
  const currencyBudgets = budgets.filter((budget) => budget.status === 'ACTIVE' && budget.currency === line.currency)
  return currencyBudgets.find((budget) => budget.scopeType === target.scopeType && budget.scopeId === target.scopeId)
    ?? currencyBudgets.find((budget) => budget.scopeType === 'ORG')
    ?? null
}

function commitmentsFor(query: { data?: PageResponse<CommitmentResponse> }): CommitmentResponse[] {
  return query.data?.items.filter((commitment) =>
    commitment.status === 'ACTIVE' || commitment.status === 'PARTIALLY_CONSUMED') ?? []
}

/**
 * Optional commitment links for posting. The selection is only a UI aid:
 * posting services re-read periods, budgets, allocation lines and commitment
 * state under their transaction locks before accepting any link.
 */
export function CommitmentLinkPicker({ lines, effectiveAt, value, onChange }: CommitmentLinkPickerProps) {
  const positiveLines = lines.filter((line) => isPositive(line.allocatedAmount) && targetOf(line))
  const periods = useQuery({
    queryKey: billingPeriodKeys.list(),
    queryFn: billingPeriodApi.list,
    enabled: positiveLines.length > 0 && effectiveAt !== null,
  })
  const period = periods.data?.find((candidate) =>
    candidate.status === 'OPEN' && effectiveAt !== null
      && periodContains(candidate.periodStart, candidate.periodEnd, effectiveAt)) ?? null
  const budgets = useQuery({
    queryKey: budgetKeys.list({ page: 0, size: 200, billingPeriodId: period?.id }),
    queryFn: () => budgetApi.list({ page: 0, size: 200, billingPeriodId: period!.id }),
    enabled: period !== null,
  })
  const lineBudgets = positiveLines.map((line) => ({ line, budget: budgetForLine(line, budgets.data?.items ?? []) }))
  const visibleLines = lineBudgets.filter((item): item is { line: AllocationLine; budget: BudgetResponse } => item.budget !== null)
  const uniqueBudgetIds = [...new Set(visibleLines.map((item) => item.budget.id))]
  const commitmentQueries = useQueries({
    queries: uniqueBudgetIds.map((budgetId) => ({
      queryKey: commitmentKeys.byBudget(budgetId),
      queryFn: () => commitmentApi.list({ page: 0, size: 100, budgetId }),
      enabled: visibleLines.length > 0,
    })),
  })
  const commitmentsByBudgetId = new Map(uniqueBudgetIds.map((budgetId, index) => [
    budgetId,
    commitmentsFor(commitmentQueries[index]),
  ]))

  if (positiveLines.length === 0 || periods.isLoading || budgets.isLoading) {
    return positiveLines.length > 0 && (periods.isLoading || budgets.isLoading) ? <Spin size="small" /> : null
  }
  if (visibleLines.length === 0) return null

  const updateLine = (allocationLineId: string, commitmentId: string | undefined) => {
    const next = value.filter((link) => link.allocationLineId !== allocationLineId)
    if (commitmentId) next.push({ allocationLineId, commitmentId })
    onChange(next)
  }

  return (
    <Space orientation="vertical" size={2} style={{ alignItems: 'flex-start' }}>
      <Typography.Text type="secondary">可选承诺（逐行）</Typography.Text>
      {visibleLines.map(({ line, budget }) => {
        const selected = value.find((link) => link.allocationLineId === line.id)?.commitmentId
        const options = (commitmentsByBudgetId.get(budget.id) ?? []).map((commitment) => ({
          value: commitment.id,
          label: `#${commitment.id} · 剩余 ${commitment.remainingAmount ?? commitment.approvedAmount ?? commitment.requestedAmount}`,
        }))
        return (
          <Select
            key={line.lineIndex}
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={`第 ${line.lineIndex + 1} 行 · ${budget.scopeType === 'ORG' ? 'ORG' : budget.scopeType}`}
            value={selected}
            options={options}
            loading={commitmentQueries[uniqueBudgetIds.indexOf(budget.id)]?.isLoading}
            onChange={(commitmentId: string | undefined) => updateLine(line.id, commitmentId)}
            style={{ width: 260 }}
          />
        )
      })}
    </Space>
  )
}
