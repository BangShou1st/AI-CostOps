import { useQuery } from '@tanstack/react-query'
import { Alert, Input, Select, Table, Tag } from 'antd'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { problemDetail as presentProblemDetail, problemSummary, toProblemDetail } from '../../api/problem'
import { ledgerApi, type LedgerListParams, type LedgerPostingSummaryResponse, type LedgerSourceType } from './api/ledgerApi'
import { ledgerKeys } from './api/ledgerKeys'
import { currencyTotals, LEDGER_SOURCE_LABEL } from './presentation'

const PAGE_SIZE = 50

export function LedgerListPage() {
  const [page, setPage] = useState(0)
  const [sourceType, setSourceType] = useState<LedgerSourceType>()
  const [billingPeriodId, setBillingPeriodId] = useState('')
  const [targetId, setTargetId] = useState('')
  const params = useMemo<LedgerListParams>(() => ({
    page,
    size: PAGE_SIZE,
    sourceType,
    billingPeriodId: billingPeriodId || undefined,
    projectId: targetId || undefined,
    sort: 'postedAt,desc',
  }), [billingPeriodId, page, sourceType, targetId])
  const list = useQuery({
    queryKey: ledgerKeys.postings(params),
    queryFn: () => ledgerApi.listPostings(params),
  })
  const problem = list.error ? toProblemDetail(list.error) : null

  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>账本</h1>
        <span className="ledger-readonly-note">已发布记录不可修改</span>
      </header>
      <div className="settings-toolbar" aria-label="账本筛选">
        <Input aria-label="账期 ID" placeholder="账期 ID" value={billingPeriodId} onChange={(event) => { setPage(0); setBillingPeriodId(event.target.value) }} />
        <Input aria-label="项目 ID" placeholder="项目 ID" value={targetId} onChange={(event) => { setPage(0); setTargetId(event.target.value) }} />
        <Select<LedgerSourceType>
          allowClear
          aria-label="来源类型"
          placeholder="来源类型"
          value={sourceType}
          options={Object.entries(LEDGER_SOURCE_LABEL).map(([value, label]) => ({ value, label }))}
          onChange={(value) => { setPage(0); setSourceType(value) }}
        />
      </div>
      {problem && (
        <Alert
          type="error"
          showIcon
          title={problemSummary(problem)}
          description={presentProblemDetail(problem) ?? undefined}
          style={{ marginBottom: 16 }}
        />
      )}
      <Table<LedgerPostingSummaryResponse>
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
        scroll={{ x: 1000 }}
        columns={[
          { title: '发布时间', dataIndex: 'postedAt', width: 190 },
          { title: '来源', width: 150, render: (_: unknown, row: LedgerPostingSummaryResponse) => LEDGER_SOURCE_LABEL[row.sourceType] ?? row.sourceType },
          { title: '来源 ID', width: 140, render: (_: unknown, row: LedgerPostingSummaryResponse) => <Link to={`/ledger/postings/${row.id}`}>{row.sourceId}</Link> },
          { title: '账期', dataIndex: 'billingPeriodId', width: 120 },
          { title: '金额', width: 190, render: (_: unknown, row: LedgerPostingSummaryResponse) => currencyTotals(row.visibleTotals).join(' / ') || '—' },
          { title: '分录数', dataIndex: 'visibleEntryCount', width: 90 },
          { title: '标记', width: 120, render: (_: unknown, row: LedgerPostingSummaryResponse) => row.sourceType === 'CORRECTION' ? <Tag color="warning">纠正</Tag> : null },
        ]}
      />
    </main>
  )
}
