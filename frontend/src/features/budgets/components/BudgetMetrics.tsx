import { Descriptions, Tag } from 'antd'
import { formatMoney } from '../../../lib/money'
import type { BudgetResponse } from '../api/budgetApi'

/**
 * The five frozen budget metrics. Every value is rendered verbatim from the
 * server response: the backend computes availableAmount and overBudget, and
 * this component never re-derives financial truth from total/actual/committed.
 */
export function BudgetMetrics({ budget }: { budget: BudgetResponse }) {
  return (
    <Descriptions column={2} size="small" bordered>
      <Descriptions.Item label="总额">{formatMoney(budget.totalAmount, budget.currency)}</Descriptions.Item>
      <Descriptions.Item label="实际发生">{formatMoney(budget.actualAmount, budget.currency)}</Descriptions.Item>
      <Descriptions.Item label="未结承诺">{formatMoney(budget.committedAmount, budget.currency)}</Descriptions.Item>
      <Descriptions.Item label="可用额度">{formatMoney(budget.availableAmount, budget.currency)}</Descriptions.Item>
      <Descriptions.Item label="超支状态">
        {budget.overBudget ? <Tag color="error">超支</Tag> : <Tag>未超支</Tag>}
      </Descriptions.Item>
    </Descriptions>
  )
}
