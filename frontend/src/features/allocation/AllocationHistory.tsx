import { Alert, Table, Tag } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { toProblemDetail } from '../../api/problem'
import { allocationKeys } from './api/allocationKeys'
import { allocationApi, type AllocationDecision } from './api/allocationApi'

const SOURCE_LABELS: Record<string, string> = { MANUAL: '手动', RULE: '规则' }
const STATUS_COLORS: Record<string, string> = {
  DRAFT: 'blue',
  CONFIRMED: 'green',
  SUPERSEDED: 'default',
}

export function AllocationHistory({ chargeId }: { chargeId: string }) {
  const history = useQuery({
    queryKey: allocationKeys.byCharge(chargeId),
    queryFn: () => allocationApi.listDecisionsByCharge(chargeId),
  })

  const problem = history.error ? toProblemDetail(history.error) : null

  return (
    <section aria-label="分摊历史">
      {problem && (
        <Alert
          type="error"
          showIcon
          message="无法加载分摊历史"
          description={(
            <>
              <div>{`${problem.title}（${problem.code}）`}</div>
              {problem.detail && <div>{problem.detail}</div>}
            </>
          )}
        />
      )}
      {!problem && (
      <Table<AllocationDecision>
        rowKey="id"
        size="small"
        loading={history.isLoading}
        dataSource={history.data ?? []}
        pagination={false}
        locale={{ emptyText: '尚无分摊记录' }}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 90 },
          {
            title: '来源',
            dataIndex: 'source',
            width: 80,
            render: (source: string) => SOURCE_LABELS[source] ?? source,
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 110,
            render: (status: string) => <Tag color={STATUS_COLORS[status]}>{status}</Tag>,
          },
          {
            title: '规则',
            width: 220,
            render: (_, decision) =>
              decision.allocationRule
                ? `${decision.allocationRule.ruleKey} v${decision.allocationRule.version}（优先级 ${decision.allocationRule.priority}）`
                : '—',
          },
          {
            title: '分摊行',
            render: (_, decision) =>
              decision.lines.map((line) => `${line.allocatedAmount} ${line.currency}`).join(' + ') || '—',
          },
          { title: '创建时间', dataIndex: 'createdAt', width: 190 },
        ]}
      />
      )}
    </section>
  )
}
