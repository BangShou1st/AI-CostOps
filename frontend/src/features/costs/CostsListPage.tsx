import { useQuery } from '@tanstack/react-query'
import { Alert, Table, Tag } from 'antd'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { problemDetail as presentProblemDetail, problemSummary, toProblemDetail } from '../../api/problem'
import { costKeys } from './api/costKeys'
import { costsApi, type ChargeReviewStatus, type ChargeCostSummary } from './api/costsApi'
import { REVIEW_STATUS_LABELS, reviewStatusColor } from './presentation'
import { formatBusinessDate } from '../../lib/dateTime'
import { formatMoney } from '../../lib/money'

const PAGE_SIZE = 50

export function CostsListPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [reviewStatus, setReviewStatus] = useState<ChargeReviewStatus | undefined>(undefined)

  const list = useQuery({
    queryKey: costKeys.list({ page, size: PAGE_SIZE, reviewStatus }),
    queryFn: () => costsApi.listCharges({ page, size: PAGE_SIZE, reviewStatus }),
  })

  const problem = list.error ? toProblemDetail(list.error) : null

  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>成本明细</h1>
        <div className="filters">
          <select
            aria-label="审核状态筛选"
            value={reviewStatus ?? ''}
            onChange={(event) => {
              setReviewStatus(event.target.value === '' ? undefined : (event.target.value as ChargeReviewStatus))
              setPage(0)
            }}
          >
            <option value="">全部状态</option>
            <option value="CLEAN">正常</option>
            <option value="SUSPECTED_DUPLICATE">疑似重复</option>
            <option value="EXCLUDED_DUPLICATE">重复已排除</option>
            <option value="EXCLUDED_NONCOST">非成本已排除</option>
          </select>
        </div>
      </header>

      {problem && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title="无法加载成本明细"
          description={(
            <>
              <div>{problemSummary(problem)}</div>
              {presentProblemDetail(problem) && <div>{presentProblemDetail(problem)}</div>}
            </>
          )}
        />
      )}

      <Table<ChargeCostSummary>
        rowKey="id"
        loading={list.isLoading}
        dataSource={list.data?.items ?? []}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: list.data?.totalElements ?? 0,
          showSizeChanger: false,
          onChange: (nextPage) => setPage(nextPage - 1),
        }}
        onRow={(row) => ({ onClick: () => navigate(`/costs/${row.id}`) })}
        columns={[
          { title: 'ID', dataIndex: 'id', width: 90 },
          { title: '供应商', dataIndex: 'providerCode', width: 110 },
          { title: '金额', dataIndex: 'amount', width: 160, render: (_, row: ChargeCostSummary) => formatMoney(row.amount, row.currency) },
          { title: '开始时间', dataIndex: 'periodStart', width: 130, render: (value: string | null) => formatBusinessDate(value) },
          { title: '结束时间', dataIndex: 'periodEnd', width: 130, render: (value: string | null) => formatBusinessDate(value) },
          {
            title: '审核状态',
            dataIndex: 'reviewStatus',
            width: 130,
            render: (value: ChargeReviewStatus) => (
              <Tag color={reviewStatusColor(value)}>{REVIEW_STATUS_LABELS[value]}</Tag>
            ),
          },
        ]}
      />
    </main>
  )
}
