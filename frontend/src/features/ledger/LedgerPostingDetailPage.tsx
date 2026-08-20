import { useQuery } from '@tanstack/react-query'
import { Alert, Card, Descriptions, Table, Tag } from 'antd'
import { Link, useParams } from 'react-router-dom'
import { problemDetail as presentProblemDetail, problemSummary, toProblemDetail } from '../../api/problem'
import { ledgerApi, type LedgerEntryResponse } from './api/ledgerApi'
import { ledgerKeys } from './api/ledgerKeys'
import { currencyTotals, LEDGER_ENTRY_LABEL, LEDGER_SOURCE_LABEL } from './presentation'

export function LedgerPostingDetailPage() {
  const { id = '' } = useParams<{ id: string }>()
  const posting = useQuery({ queryKey: ledgerKeys.posting(id), queryFn: () => ledgerApi.getPosting(id), enabled: id.length > 0 })
  if (posting.isLoading) return <main className="settings-page" role="status">正在加载账本发布记录…</main>
  if (posting.error || !posting.data) {
    const problem = posting.error ? toProblemDetail(posting.error) : null
    return <main className="settings-page"><Alert type="error" showIcon title={problem ? problemSummary(problem) : '无法加载账本发布记录'} description={problem ? (presentProblemDetail(problem) ?? undefined) : undefined} /></main>
  }
  const detail = posting.data
  return (
    <main className="settings-page">
      <header className="page-header"><h1>账本发布 #{detail.id}</h1><Tag color={detail.sourceType === 'CORRECTION' ? 'warning' : 'blue'}>{LEDGER_SOURCE_LABEL[detail.sourceType]}</Tag></header>
      <Card size="small">
        <Descriptions column={2} size="small">
          <Descriptions.Item label="发布键">{detail.postingKey}</Descriptions.Item>
          <Descriptions.Item label="状态">{detail.status}</Descriptions.Item>
          <Descriptions.Item label="来源 ID">{detail.sourceId}</Descriptions.Item>
          <Descriptions.Item label="账期">{detail.billingPeriodId}</Descriptions.Item>
          <Descriptions.Item label="发布时间">{detail.postedAt}</Descriptions.Item>
          <Descriptions.Item label="可见合计">{currencyTotals(detail.visibleTotals).join(' / ') || '—'}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Table<LedgerEntryResponse>
        rowKey="id"
        style={{ marginTop: 16 }}
        dataSource={detail.entries}
        pagination={false}
        scroll={{ x: 900 }}
        columns={[
          { title: '序号', dataIndex: 'entryIndex', width: 70 },
          { title: '类型', render: (_: unknown, row: LedgerEntryResponse) => LEDGER_ENTRY_LABEL[row.entryType] ?? row.entryType },
          { title: '金额', render: (_: unknown, row: LedgerEntryResponse) => `${row.amount} ${row.currency}` },
          { title: '目标', render: (_: unknown, row: LedgerEntryResponse) => `${row.targetType} · ${row.targetId}` },
          { title: '预算', dataIndex: 'budgetId', render: (value: string | null) => value ?? '未匹配' },
          { title: '血缘', render: (_: unknown, row: LedgerEntryResponse) => <Link to={`/ledger/entries/${row.id}`}>查看</Link> },
        ]}
      />
    </main>
  )
}
