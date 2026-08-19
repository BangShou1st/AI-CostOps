import { Descriptions, Tag } from 'antd'
import type { BudgetResponse } from '../api/budgetApi'

/**
 * The five frozen budget metrics. Every value is rendered verbatim from the
 * server response: the backend computes availableAmount and overBudget, and
 * this component never re-derives financial truth from total/actual/committed.
 */
export function BudgetMetrics({ budget }: { budget: BudgetResponse }) {
  return (
    <Descriptions column={2} size="small" bordered>
      <Descriptions.Item label="Total">{budget.totalAmount} {budget.currency}</Descriptions.Item>
      <Descriptions.Item label="Actual">{budget.actualAmount} {budget.currency}</Descriptions.Item>
      <Descriptions.Item label="Outstanding Commitment">{budget.committedAmount} {budget.currency}</Descriptions.Item>
      <Descriptions.Item label="Available">{budget.availableAmount} {budget.currency}</Descriptions.Item>
      <Descriptions.Item label="Over-budget">
        {budget.overBudget ? <Tag color="error">超支</Tag> : <Tag>未超支</Tag>}
      </Descriptions.Item>
    </Descriptions>
  )
}
