import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Descriptions, Tag, Typography } from 'antd'
import { Link, useParams } from 'react-router-dom'
import { problemDetail as presentProblemDetail, problemSummary, toProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { costKeys } from './api/costKeys'
import { costsApi } from './api/costsApi'
import { REVIEW_STATUS_LABELS, reviewStatusColor } from './presentation'
import { allocationKeys } from '../allocation/api/allocationKeys'
import { allocationApi } from '../allocation/api/allocationApi'
import { AllocationEditor } from '../allocation/AllocationEditor'
import { AllocationHistory } from '../allocation/AllocationHistory'
import { formatBusinessDateRange } from '../../lib/dateTime'

export function CostDetailPage() {
  const params = useParams()
  const chargeId = params.id ?? ''
  const auth = useAuth()
  const queryClient = useQueryClient()

  const permissions = auth.user?.permissions
  const canReadAllocation = hasPermission(permissions, 'ALLOCATION_READ')
  const canEditAllocation = hasPermission(permissions, 'ALLOCATION_EDIT')
  const canConfirmAllocation = hasPermission(permissions, 'ALLOCATION_CONFIRM')
  const canReviewDuplicates = hasPermission(permissions, 'DUPLICATE_REVIEW')
  const canManageRules = hasPermission(permissions, 'ALLOCATION_RULE_MANAGE')

  const charge = useQuery({
    queryKey: costKeys.detail(chargeId),
    queryFn: () => costsApi.getCharge(chargeId),
    enabled: chargeId.length > 0,
  })

  const decisions = useQuery({
    queryKey: allocationKeys.byCharge(chargeId),
    queryFn: () => allocationApi.listDecisionsByCharge(chargeId),
    enabled: chargeId.length > 0 && canReadAllocation,
  })

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: costKeys.lists() })
    void queryClient.invalidateQueries({ queryKey: costKeys.detail(chargeId) })
    void queryClient.invalidateQueries({ queryKey: allocationKeys.byCharge(chargeId) })
  }

  if (charge.isLoading || decisions.isLoading) {
    return <main className="settings-page" role="status">正在加载成本详情…</main>
  }
  if (charge.error || !charge.data) {
    const problem = charge.error ? toProblemDetail(charge.error) : null
    return (
      <main className="settings-page">
        <Alert
          type="error"
          showIcon
          title="无法加载成本详情"
          description={problem && (
            <>
               <div>{problemSummary(problem)}</div>
               {presentProblemDetail(problem) && <div>{presentProblemDetail(problem)}</div>}
            </>
          )}
        />
      </main>
    )
  }

  const detail = charge.data
  const allDecisions = decisions.data ?? []
  const manualDraft = allDecisions.find(
    (decision) => decision.source === 'MANUAL' && decision.status === 'DRAFT',
  ) ?? null
  const ruleDraft = allDecisions.find(
    (decision) => decision.source === 'RULE' && decision.status === 'DRAFT',
  ) ?? null
  const confirmedDecision = allDecisions.find((decision) => decision.status === 'CONFIRMED') ?? null
  const hasConfirmed = confirmedDecision !== null
  // The editor shows the current decision: an open draft while one exists,
  // otherwise the CONFIRMED decision as read-only truth.
  const draft = manualDraft ?? ruleDraft ?? confirmedDecision
  const suspected = detail.reviewStatus === 'SUSPECTED_DUPLICATE'
  const excluded = detail.reviewStatus === 'EXCLUDED_DUPLICATE' || detail.reviewStatus === 'EXCLUDED_NONCOST'

  return (
    <main className="settings-page">
      <header className="page-header">
        <h1>成本详情 #{detail.id}</h1>
        {canManageRules && <Link to="/allocation-rules">分摊规则管理</Link>}
      </header>

      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="供应商">{detail.providerCode}</Descriptions.Item>
        <Descriptions.Item label="类别">{detail.chargeCategory}</Descriptions.Item>
        <Descriptions.Item label="金额">
          <Typography.Text strong>{detail.amount} {detail.currency}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="周期">
          {formatBusinessDateRange(detail.periodStart, detail.periodEnd)}
        </Descriptions.Item>
        <Descriptions.Item label="审核状态">
          <Tag color={reviewStatusColor(detail.reviewStatus)}>{REVIEW_STATUS_LABELS[detail.reviewStatus]}</Tag>
          {suspected && canReviewDuplicates && (
            <Link to="/costs/duplicates">前往重复审核</Link>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="确认导入谱系">
          {detail.confirmedImport ? '是' : '否'}
        </Descriptions.Item>
      </Descriptions>

      {excluded && (
        <Alert type="info" showIcon style={{ marginTop: 16 }} title="该成本已被排除，不参与分摊。" />
      )}

      {canReadAllocation && !excluded && (
        <>
          <Typography.Title level={4} style={{ marginTop: 24 }}>分摊</Typography.Title>
          {decisions.error ? (
            <Alert
              type="error"
              showIcon
              title="无法加载分摊信息"
              description={(() => {
                const problem = toProblemDetail(decisions.error)
                return (
                  <>
                    <div>{problemSummary(problem)}</div>
                    {presentProblemDetail(problem) && <div>{presentProblemDetail(problem)}</div>}
                  </>
                )
              })()}
            />
          ) : (
            <>
              <AllocationEditor
                chargeId={chargeId}
                subjectType="CHARGE_FACT"
                subjectAmount={detail.amount}
                subjectCurrency={detail.currency}
                reviewStatus={detail.reviewStatus}
                draft={draft}
                canEdit={canEditAllocation}
                canConfirm={canConfirmAllocation}
                hasConfirmed={hasConfirmed}
                onChanged={refresh}
                onProposalApplied={refresh}
              />
              <Typography.Title level={4} style={{ marginTop: 24 }}>分摊历史</Typography.Title>
              <AllocationHistory chargeId={chargeId} />
            </>
          )}
        </>
      )}
    </main>
  )
}
