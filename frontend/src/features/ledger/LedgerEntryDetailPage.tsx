import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Card, Descriptions, Divider } from 'antd'
import { Link, useParams } from 'react-router-dom'
import { problemDetail as presentProblemDetail, problemSummary, toProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { CorrectionAction } from './CorrectionAction'
import { ledgerApi } from './api/ledgerApi'
import { ledgerKeys } from './api/ledgerKeys'
import { LEDGER_ENTRY_LABEL, lineageLabel } from './presentation'
import { formatMoney } from '../../lib/money'

export function LedgerEntryDetailPage() {
  const { id = '' } = useParams<{ id: string }>()
  const auth = useAuth()
  const queryClient = useQueryClient()
  const entry = useQuery({ queryKey: ledgerKeys.entry(id), queryFn: () => ledgerApi.getEntry(id), enabled: id.length > 0 })
  if (entry.isLoading) return <main className="settings-page" role="status">正在加载分录血缘…</main>
  if (entry.error || !entry.data) {
    const problem = entry.error ? toProblemDetail(entry.error) : null
    return <main className="settings-page"><Alert type="error" showIcon title={problem ? problemSummary(problem) : '无法加载分录血缘'} description={problem ? (presentProblemDetail(problem) ?? undefined) : undefined} /></main>
  }
  const detail = entry.data
  const lineage = detail.lineage
  const provider = lineage.chargeFactId !== null
  const expense = lineage.expenseClaimId !== null
  const correction = lineage.correctionGroupId !== null || lineage.reversesEntryId !== null || lineage.correctedByCorrectionGroupId !== null
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ledgerKeys.entry(id) })
    void queryClient.invalidateQueries({ queryKey: ledgerKeys.posting(detail.posting.id) })
    void queryClient.invalidateQueries({ queryKey: ledgerKeys.lists() })
  }
  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>分录血缘 #{detail.entry.id}</h1>
        {hasPermission(auth.user?.permissions, 'LEDGER_CORRECT') && lineage.correctedByCorrectionGroupId === null && <CorrectionAction entry={detail.entry} onCompleted={refresh} />}
      </header>
      <Card size="small">
        <Descriptions column={2} size="small">
          <Descriptions.Item label="类型">{LEDGER_ENTRY_LABEL[detail.entry.entryType]}</Descriptions.Item>
          <Descriptions.Item label="金额">{formatMoney(detail.entry.amount, detail.entry.currency)}</Descriptions.Item>
          <Descriptions.Item label="目标">{detail.entry.targetType} · {detail.entry.targetId}</Descriptions.Item>
          <Descriptions.Item label="发布记录"><Link to={`/ledger/postings/${detail.posting.id}`}>{detail.posting.id}</Link></Descriptions.Item>
        </Descriptions>
      </Card>
      <Card title="来源链路" size="small" style={{ marginTop: 16 }}>
        {provider && <Descriptions column={2} size="small"><Descriptions.Item label="类型">供应商成本</Descriptions.Item><Descriptions.Item label="成本 ID"><Link to={`/costs/${lineage.chargeFactId}`}>{lineage.chargeFactId}</Link></Descriptions.Item><Descriptions.Item label="供应商">{lineageLabel(lineage.chargeProviderCode)}</Descriptions.Item><Descriptions.Item label="审核状态">{lineageLabel(lineage.chargeReviewStatus)}</Descriptions.Item><Descriptions.Item label="导入批次">{lineageLabel(lineage.importBatchId)}</Descriptions.Item><Descriptions.Item label="证据">{lineageLabel(lineage.providerEvidenceId)}</Descriptions.Item></Descriptions>}
        {expense && <Descriptions column={2} size="small"><Descriptions.Item label="类型">报销</Descriptions.Item><Descriptions.Item label="报销 ID"><Link to={`/expense-reviews/${lineage.expenseClaimId}`}>{lineage.expenseClaimId}</Link></Descriptions.Item><Descriptions.Item label="状态">{lineageLabel(lineage.expenseStatus)}</Descriptions.Item><Descriptions.Item label="凭证">{lineageLabel(lineage.expenseEvidenceId)}</Descriptions.Item></Descriptions>}
        {correction && <Descriptions column={2} size="small"><Descriptions.Item label="类型">纠正</Descriptions.Item><Descriptions.Item label="纠正组">{lineageLabel(lineage.correctionGroupId)}</Descriptions.Item><Descriptions.Item label="反转目标">{lineageLabel(lineage.reversesEntryId ?? lineage.correctionTargetEntryId)}</Descriptions.Item><Descriptions.Item label="被纠正组">{lineageLabel(lineage.correctedByCorrectionGroupId)}</Descriptions.Item></Descriptions>}
        {!provider && !expense && !correction && <span>暂无业务来源链路。</span>}
        <Divider plain>分摊链路</Divider>
        <Descriptions column={2} size="small"><Descriptions.Item label="分摊决策">{lineageLabel(lineage.allocationDecisionId)}</Descriptions.Item><Descriptions.Item label="分摊行">{lineageLabel(lineage.allocationLineId)}</Descriptions.Item><Descriptions.Item label="决策状态">{lineageLabel(lineage.allocationDecisionStatus)}</Descriptions.Item></Descriptions>
      </Card>
    </main>
  )
}
