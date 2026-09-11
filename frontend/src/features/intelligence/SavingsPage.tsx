import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Drawer, Empty, InputNumber, Select } from 'antd'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { toProblemDetail, type ProblemDetail } from '../../api/problem'
import { apiClient } from '../auth/authApi'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { useAuthorizationMutation } from '../settings/useAuthorizationMutation'
import { intelligenceApi } from './api/intelligenceApi'
import { intelligenceKeys } from './api/intelligenceKeys'
import type { SavingRecommendation } from './api/intelligenceTypes'
import { sortByAmountDesc } from './decimal'
import { formatMoney, formatPercent } from './format'
import { OverviewSkeleton, REC_STATUS } from './OverviewSections'
import './v3-tokens.css'

export interface SavingsPreview {
  currency: string
  recommendations: SavingRecommendation[]
}

export function SavingsPage(props: { preview?: SavingsPreview }) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [currency, setCurrency] = useState('USD')
  const [status, setStatus] = useState<string>('ACTIONABLE')
  const [selected, setSelected] = useState<SavingRecommendation | null>(null)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const preview = props.preview
  const activeCurrency = preview?.currency ?? currency
  const canGovern = hasPermission(auth.user?.permissions, 'BUDGET_MANAGE')

  const listQuery = useQuery({
    queryKey: intelligenceKeys.recommendations(activeCurrency),
    queryFn: () => intelligenceApi.recommendations(activeCurrency),
    enabled: !preview,
    staleTime: 30_000,
  })
  const recommendations = preview?.recommendations ?? listQuery.data ?? []
  const isLoading = !preview && listQuery.isLoading
  const loadProblem = !preview && listQuery.error ? toProblemDetail(listQuery.error) : null
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: intelligenceKeys.recommendations(activeCurrency) })
    void queryClient.invalidateQueries({ queryKey: intelligenceKeys.summary(activeCurrency) })
  }

  const visible = useMemo(() => {
    const filtered = status === 'ALL'
      ? recommendations
      : status === 'ACTIONABLE'
        ? recommendations.filter((r) => r.status === 'OPEN' || r.status === 'ACKNOWLEDGED')
        : recommendations.filter((r) => r.status === status)
    return sortByAmountDesc(filtered, (r) => r.potentialSaving)
  }, [recommendations, status])

  return (
    <main className="v3-page">
      <header>
        <span className="v3-eyebrow">Cost Intelligence</span>
        <h1>节约建议</h1>
        <p className="v3-lede">反事实节约测算。所有状态流转都是人工治理动作，不会自动切换路由。</p>
        <dl className="v3-meta">
          <div><dt>币种</dt><dd>{preview ? activeCurrency : null}</dd></div>
          <div><dt>建议</dt><dd>{recommendations.length} 条</dd></div>
        </dl>
      </header>
      <div style={{ marginTop: 12 }}>
        {!preview && (
          <Select value={activeCurrency} onChange={setCurrency} aria-label="结算币种" size="small" style={{ minWidth: 110 }}
            options={['USD', 'CNY', 'EUR', 'GBP'].map((c) => ({ value: c, label: c }))} />
        )}
        <span style={{ marginLeft: 12 }}>
          <Select value={status} onChange={setStatus} aria-label="状态筛选" size="small" style={{ minWidth: 140 }}
            options={[{ value: 'ACTIONABLE', label: '可执行' }, { value: 'ALL', label: '全部' }, { value: 'OPEN', label: '待处理' }, { value: 'ACKNOWLEDGED', label: '已确认' }, { value: 'DISMISSED', label: '已忽略' }, { value: 'APPLIED', label: '已应用' }, { value: 'EXPIRED', label: '已过期' }]} />
        </span>
      </div>
      {problem && <div role="alert"><Alert type="error" showIcon closable onClose={() => setProblem(null)} message="治理动作失败" description={problemDetailText(problem)} /></div>}
      {loadProblem && <div role="alert"><Alert type="error" showIcon message="加载失败" description={problemDetailText(loadProblem)} /></div>}
      {!preview && !canGovern && <div className="v3-note" role="status">缺少 BUDGET_MANAGE 权限：可查看建议，治理动作需财务管理员执行。</div>}
      {isLoading && <OverviewSkeleton />}
      {!isLoading && !loadProblem && visible.length === 0 && <div style={{ marginTop: 20 }}><Empty description="当前筛选下暂无节约建议。" /></div>}
      {!isLoading && !loadProblem && visible.length > 0 && (
        <ul className="v3-list" style={{ marginTop: 16 }}>
          {visible.map((r) => {
            const st = REC_STATUS[r.status] ?? { text: r.status, pill: 'v3-pill-neutral' }
            return (
              <li key={r.id} className="v3-row">
                <div className="v3-row-main">
                  <p className="v3-row-title"><span className={`v3-pill ${st.pill}`}>{st.text}</span>{' '}逻辑模型 #{r.logicalModelId}</p>
                  <p className="v3-row-detail">{formatMoney(r.currentCost, activeCurrency)} → {formatMoney(r.candidateCost, activeCurrency)}{r.routingChangeRequired ? ' · 需路由变更' : ' · 同路优化'}</p>
                </div>
                <div className="v3-row-amount"><div className="a v3-delta-save">节约 {formatMoney(r.potentialSaving, activeCurrency)}</div><div className="d v3-delta-save">{formatPercent(r.potentialSavingPercent)}</div></div>
                <div className="v3-row-action"><button type="button" className="v3-link-button" onClick={() => { setProblem(null); setSelected(r) }} aria-label={`复核建议 ${r.id}`}>复核</button></div>
              </li>
            )
          })}
        </ul>
      )}
      <Drawer open={selected !== null} onClose={() => setSelected(null)} title="复核节约建议" width={460}>
        {selected && <RecommendationReview recommendation={selected} currency={activeCurrency} canGovern={canGovern} previewMode={Boolean(preview)} onDone={(next) => { setSelected(next); invalidate() }} onError={setProblem} />}
      </Drawer>
    </main>
  )
}

function problemDetailText(problem: ProblemDetail): string {
  const detail = (problem as { detail?: string }).detail
  const title = (problem as { title?: string }).title
  return detail ?? title ?? '请稍后重试。'
}

function RecommendationReview(props: { recommendation: SavingRecommendation; currency: string; canGovern: boolean; previewMode: boolean; onDone: (next: SavingRecommendation) => void; onError: (problem: ProblemDetail) => void }) {
  const { recommendation: r, currency, canGovern, previewMode, onDone, onError } = props
  const [routingPolicyId, setRoutingPolicyId] = useState<number | null>(typeof r.routingPolicyId === 'number' ? r.routingPolicyId : null)
  const st = REC_STATUS[r.status] ?? { text: r.status, pill: 'v3-pill-neutral' }
  const act = useAuthorizationMutation({
    mutationFn: async (action: 'acknowledge' | 'dismiss' | 'mark-applied') => {
      if (action === 'mark-applied') {
        const params = routingPolicyId === null ? undefined : { routingPolicyId }
        return (await apiClient.post<SavingRecommendation>(`/cost-intelligence/recommendations/${r.id}/mark-applied`, undefined, { params })).data
      }
      return (await apiClient.post<SavingRecommendation>(`/cost-intelligence/recommendations/${r.id}/${action}`)).data
    },
    onSuccess: (next) => onDone(next),
    onError: (error) => onError(toProblemDetail(error)),
  })
  const canAck = r.status === 'OPEN'
  const canDismiss = r.status === 'OPEN' || r.status === 'ACKNOWLEDGED'
  const canApply = r.status === 'ACKNOWLEDGED'
  return (
    <dl className="v3-evidence">
      <div><dt>状态</dt><dd><span className={`v3-pill ${st.pill}`}>{st.text}</span></dd></div>
      <div><dt>当前成本</dt><dd>{formatMoney(r.currentCost, currency)}</dd></div>
      <div><dt>候选成本</dt><dd>{formatMoney(r.candidateCost, currency)}</dd></div>
      <div><dt>预计节约</dt><dd>{formatMoney(r.potentialSaving, currency)}（{formatPercent(r.potentialSavingPercent)}）</dd></div>
      <div><dt>路由变更</dt><dd>{r.routingChangeRequired ? '需要：请人工检查路由策略后再标记应用。' : '不需要：同路优化。'}</dd></div>
      {r.routingChangeRequired && <div><dt>路由治理</dt><dd><Link to={`/settings/routing-policies?recommendationId=${r.id}`}>检查路由策略</Link></dd></div>}
      <div><dt>测算时间</dt><dd>{r.calculatedAt}</dd></div>
      {!previewMode && !canGovern && <div><dt>治理</dt><dd>缺少 BUDGET_MANAGE 权限，动作不可用。</dd></div>}
      {previewMode && <div><dt>治理</dt><dd>预览模式：生产页调用真实接口。</dd></div>}
      {!previewMode && canGovern && (
        <div><dt>动作</dt><dd>
          <div className="v3-govern-actions">
            <button type="button" disabled={!canAck || act.isPending} onClick={() => act.mutate('acknowledge')}>确认</button>
            <button type="button" disabled={!canDismiss || act.isPending} onClick={() => act.mutate('dismiss')}>忽略</button>
            <button type="button" disabled={!canApply || act.isPending} onClick={() => act.mutate('mark-applied')}>标记应用</button>
          </div>
          {canApply && <label className="v3-govern-route">关联路由策略 ID（可选）<InputNumber value={routingPolicyId} onChange={(v) => setRoutingPolicyId(typeof v === 'number' ? v : null)} min={1} precision={0} aria-label="关联路由策略 ID" /></label>}
          {act.isPending && <span role="status">正在提交治理动作…</span>}
        </dd></div>
      )}
    </dl>
  )
}
