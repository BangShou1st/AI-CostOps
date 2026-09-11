import { useQuery } from '@tanstack/react-query'
import {
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  RightOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { Alert } from 'antd'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { toProblemDetail } from '../../api/problem'
import { intelligenceApi } from './api/intelligenceApi'
import type { BudgetRisk, CostAnomaly, CostForecast, IntelligenceSummary, SavingRecommendation } from './api/intelligenceTypes'
import { absDecimal, compareDecimal, sortByAmountDesc } from './decimal';
import { formatMoney, formatPercent } from './format'
import { TrendFigure } from './TrendFigure'

export function OverviewLoadError(props: { error: unknown }) {
  const problem = toProblemDetail(props.error) as { title?: string; detail?: string }
  return (
    <div style={{ marginTop: 16 }} role="alert">
      <Alert type="error" showIcon message={problem.title ?? '加载失败'} description={problem.detail ?? '请稍后重试。'} />
    </div>
  )
}

export function OverviewSkeleton() {
  return (
    <div className="v3-skeleton" role="status" aria-label="正在加载成本智能总览">
      <div className="v3-answer-strip" aria-hidden="true">
        {[0, 1, 2, 3].map((i) => <div key={i} className="v3-answer" style={{ minHeight: 96 }} />)}
      </div>
      <div className="v3-figure" aria-hidden="true" style={{ minHeight: 220 }} />
      <div className="v3-row" aria-hidden="true" style={{ minHeight: 64 }} />
      <div className="v3-row" aria-hidden="true" style={{ minHeight: 64 }} />
    </div>
  )
}

export function grainLabel(a: CostAnomaly): string {
  return `${a.grainType} · ${a.grainKey}`
}

export function AnswerStrip(props: {
  summary: IntelligenceSummary
  anomalies: CostAnomaly[]
  forecast?: CostForecast
  recommendations: SavingRecommendation[]
  currency: string
}) {
  const { summary, anomalies, forecast, recommendations, currency } = props
  const topAnomaly = sortByAmountDesc(anomalies, (a) => absDecimal(a.deltaAmount))[0]
  const openRecs = recommendations.filter((r) => r.status === 'OPEN')
  const topSaving = sortByAmountDesc(openRecs, (a) => a.potentialSaving)[0]
  const answers: Array<{ key: string; question: string; tone: string; icon: ReactNode; body: ReactNode }> = [
    {
      key: 'ok',
      question: 'Are we okay?',
      tone: summary.anomalyCount > 0 ? 'watch' : 'ok',
      icon: summary.anomalyCount > 0 ? <WarningOutlined /> : <CheckCircleOutlined />,
      body: summary.anomalyCount > 0
        ? <><strong>{summary.anomalyCount} 个异常</strong>需要关注，见下方变化。</>
        : <>本周期暂无异常，支出按预期进行。</>,
    },
    {
      key: 'changed',
      question: 'What changed?',
      tone: topAnomaly ? 'info' : 'ok',
      icon: <InfoCircleOutlined />,
      body: topAnomaly
        ? <><strong>{grainLabel(topAnomaly)} {formatPercent(topAnomaly.deltaPercent)}</strong>，观测 {formatMoney(topAnomaly.observedAmount, currency)}。</>
        : <>各粒度与基线基本持平。</>,
    },
    {
      key: 'happen',
      question: 'What will happen?',
      tone: 'info',
      icon: <InfoCircleOutlined />,
      body: forecast
        ? <>周期预计 <strong>{formatMoney(forecast.projectedAmount, currency)}</strong>（{forecast.method}·{forecast.confidence}）</>
        : <>暂无预测，历史粒度不足。</>,
    },
    {
      key: 'do',
      question: 'What can I do?',
      tone: topSaving ? 'ok' : 'info',
      icon: topSaving ? <CheckCircleOutlined /> : <InfoCircleOutlined />,
      body: topSaving
        ? <>最大单笔可节约 <strong>{formatMoney(topSaving.potentialSaving, currency)}</strong>（{formatPercent(topSaving.potentialSavingPercent)}）</>
        : <>{summary.openRecommendations > 0 ? `现有 ${summary.openRecommendations} 条待处理建议。` : '暂无可节约建议。'}</>,
    },
  ]
  return (
    <section className="v3-section" aria-label="四个关键判断">
      <div className="v3-answer-strip">
        {answers.map((a) => (
          <div key={a.key} className={`v3-answer v3-answer-tone-${a.tone}`}>
            <div className={`v3-answer-tone v3-tone-${a.tone}`}><span aria-hidden="true">{a.icon}</span>{a.question}</div>
            <p>{a.body}</p>
          </div>
        ))}
      </div>
    </section>
  )
}

const RISK_LABEL: Record<string, { text: string; pill: string }> = {
  LOW: { text: '稳定', pill: 'v3-pill-ok' },
  WATCH: { text: '观察', pill: 'v3-pill-watch' },
  HIGH: { text: '高风险', pill: 'v3-pill-risk' },
  OVER_BUDGET: { text: '已超支', pill: 'v3-pill-risk' },
}

export function ExposureSection(props: {
  previewRisk?: BudgetRisk | null
  previewMode: boolean
  canReadBudget: boolean
  orgId?: string
  currency: string
  forecast?: CostForecast
}) {
  const { previewMode, previewRisk, canReadBudget, orgId, currency, forecast } = props
  const riskQuery = useQuery({
    queryKey: ['intelligence', 'budget-risk', currency, orgId],
    queryFn: () => intelligenceApi.budgetRisk('ORG', orgId ?? '', currency),
    enabled: !previewMode && canReadBudget && Boolean(orgId),
    retry: false,
    staleTime: 30_000,
  })
  const risk = previewMode ? (previewRisk ?? null) : (riskQuery.data ?? null)
  const riskProblem = !previewMode && riskQuery.error ? (toProblemDetail(riskQuery.error) as { code?: string; detail?: string }) : null
  const riskCode = riskProblem?.code ?? ''
  const notConfigured = riskCode === 'RESOURCE_NOT_FOUND'
  const denied = riskCode === 'FORBIDDEN' || riskCode === 'ACCESS_DENIED'
  const riskMeta = risk ? RISK_LABEL[risk.risk] ?? { text: risk.risk, pill: 'v3-pill-neutral' } : null

  return (
    <section className="v3-section" aria-labelledby="v3-what-happen">
      <div className="v3-section-head">
        <span className="v3-eyebrow">03 · What will happen?</span>
        <h2 id="v3-what-happen">周期走向与预算敞口</h2>
        <p>即时敞口＝实际支出＋已提交＋预占，预测为衍生证据，不是账本真相。</p>
      </div>
      {!previewMode && !canReadBudget && (
        <div className="v3-note">缺少 BUDGET_READ 权限，无法查看预算敞口。预测部分仍可用。</div>
      )}
      {!previewMode && canReadBudget && riskQuery.isLoading && (
        <div className="v3-figure" role="status" aria-label="正在加载预算敞口" style={{ minHeight: 220 }} />
      )}
      {!previewMode && canReadBudget && !riskQuery.isLoading && (notConfigured || denied) && (
        <div className="v3-note" role="status">
          {notConfigured ? '当前范围暂无进行中预算，配置预算后可查看敞口与周期预期。' : '无权查看预算敞口，请联系财务管理员。'}
        </div>
      )}
      {!previewMode && canReadBudget && !riskQuery.isLoading && riskProblem && !notConfigured && !denied && (
        <div role="alert"><Alert type="error" showIcon message="预算敞口加载失败" description={riskProblem.detail ?? '请稍后重试。'} /></div>
      )}
      {risk && riskMeta && forecast && (
        <>
          <TrendFigure
            immediateExposure={risk.immediateExposure}
            projectedPeriodEnd={risk.projectedPeriodEnd}
            budgetTotal={risk.budgetTotal}
            riskThreshold={String(Number(risk.budgetTotal) * 0.9)}
            currency={currency}
            forecastMethod={forecast.method}
            forecastConfidence={forecast.confidence}
            historyBucketCount={forecast.historyBucketCount}
            observedThrough={forecast.observedThrough}
          />
          <div className="v3-rail">
            <div className="v3-rail-item"><div className="k">即时敞口</div><div className="v">{formatMoney(risk.immediateExposure, currency)}</div><div className="s">实际＋已提交＋预占</div></div>
            <div className="v3-rail-item"><div className="k">预计周期末</div><div className="v">{formatMoney(risk.projectedPeriodEnd, currency)}</div><div className="s">{forecast.method} · {forecast.confidence} 置信度</div></div>
            <div className="v3-rail-item"><div className="k">预算风险</div><div className="v"><span className={`v3-pill ${riskMeta.pill}`}>{riskMeta.text}</span></div><div className="s">预算 {formatMoney(risk.budgetTotal, currency)}</div></div>
          </div>
        </>
      )}
      {risk && riskMeta && !forecast && (
        <div className="v3-note">预算敞口已就绪，但历史粒度不足，暂无周期预测。</div>
      )}
    </section>
  )
}

export function severityOf(a: CostAnomaly): { text: string; pill: string; icon: ReactNode } {
  const z = Math.abs(a.robustZScore)
  const pct = absDecimal(a.deltaPercent)
  if (z >= 5 || compareDecimal(pct, '50') >= 0) return { text: '严重', pill: 'v3-pill-risk', icon: <ExclamationCircleOutlined /> }
  if (z >= 3 || compareDecimal(pct, '20') >= 0) return { text: '需关注', pill: 'v3-pill-watch', icon: <WarningOutlined /> }
  return { text: '一般', pill: 'v3-pill-info', icon: <InfoCircleOutlined /> }
}

export function AnomalySection(props: { anomalies: CostAnomaly[]; currency: string }) {
  const { anomalies, currency } = props
  const top = sortByAmountDesc(anomalies, (a) => absDecimal(a.deltaAmount)).slice(0, 3)
  return (
    <section className="v3-section" aria-labelledby="v3-what-changed">
      <div className="v3-section-head">
        <span className="v3-eyebrow">02 · What changed?</span>
        <h2 id="v3-what-changed">需要关注的异常</h2>
        <p>异常由 median／MAD／robust-z 确定性规则检出，只显示最具材料性的前三条。</p>
      </div>
      {top.length === 0 && <div className="v3-note">本周期暂无异常。检测门槛：robust-z ≥ 3，变化幅度 ≥ 20%，且超过币种重要性阈值。</div>}
      {top.length > 0 && (
        <ul className="v3-list">
          {top.map((a) => {
            const sev = severityOf(a)
            return (
              <li key={a.id} className="v3-row">
                <div className="v3-row-main">
                  <p className="v3-row-title"><span className={`v3-pill ${sev.pill}`}><span aria-hidden="true">{sev.icon}</span>{sev.text}</span>{' '}{grainLabel(a)}</p>
                  <p className="v3-row-detail">观测 {formatMoney(a.observedAmount, currency)}，基线 {formatMoney(a.baselineAmount, currency)}，robust-z {a.robustZScore.toFixed(1)}</p>
                </div>
                <div className="v3-row-amount"><div className="a v3-delta-up">{formatMoney(a.deltaAmount, currency)}</div><div className="d v3-delta-up">{formatPercent(a.deltaPercent)}</div></div>
              </li>
            )
          })}
        </ul>
      )}
      <div className="v3-actions"><Link to="/intelligence/anomalies">查看全部异常 <RightOutlined aria-hidden="true" /></Link></div>
    </section>
  )
}

export const REC_STATUS: Record<string, { text: string; pill: string }> = {
  OPEN: { text: '待处理', pill: 'v3-pill-info' },
  ACKNOWLEDGED: { text: '已确认', pill: 'v3-pill-neutral' },
  DISMISSED: { text: '已忽略', pill: 'v3-pill-neutral' },
  APPLIED: { text: '已应用', pill: 'v3-pill-ok' },
  EXPIRED: { text: '已过期', pill: 'v3-pill-neutral' },
}

export function SavingsSection(props: { recommendations: SavingRecommendation[]; currency: string }) {
  const { recommendations, currency } = props
  const actionable = recommendations.filter((r) => r.status === 'OPEN' || r.status === 'ACKNOWLEDGED').slice(0, 2)
  return (
    <section className="v3-section" aria-labelledby="v3-what-do">
      <div className="v3-section-head">
        <span className="v3-eyebrow">04 · What can I do?</span>
        <h2 id="v3-what-do">节约建议脉冲</h2>
        <p>反事实节约测算，只记录人工治理动作，不会自动切换路由。</p>
      </div>
      {actionable.length === 0 && <div className="v3-note">暂无可执行的节约建议。</div>}
      {actionable.length > 0 && (
        <ul className="v3-list">
          {actionable.map((r) => {
            const st = REC_STATUS[r.status] ?? { text: r.status, pill: 'v3-pill-neutral' }
            return (
              <li key={r.id} className="v3-row">
                <div className="v3-row-main">
                  <p className="v3-row-title"><span className={`v3-pill ${st.pill}`}>{st.text}</span>{' '}逻辑模型 #{r.logicalModelId}</p>
                  <p className="v3-row-detail">
                    {formatMoney(r.currentCost, currency)} → {formatMoney(r.candidateCost, currency)}
                    {r.routingChangeRequired ? ' · 需路由变更' : ' · 同路优化'}
                  </p>
                </div>
                <div className="v3-row-amount"><div className="a v3-delta-save">节约 {formatMoney(r.potentialSaving, currency)}</div><div className="d v3-delta-save">{formatPercent(r.potentialSavingPercent)}</div></div>
              </li>
            )
          })}
        </ul>
      )}
      <div className="v3-actions">
        <Link to="/intelligence/savings">去复核节约建议 <RightOutlined aria-hidden="true" /></Link>
        <Link to="/settings/routing-policies">查看路由策略 <RightOutlined aria-hidden="true" /></Link>
      </div>
    </section>
  )
}

export function AdvisorBand() {
  return (
    <section className="v3-section" aria-labelledby="v3-advisor">
      <div className="v3-section-head">
        <span className="v3-eyebrow">Advisor</span>
        <h2 id="v3-advisor">从证据到解释</h2>
        <p>AI Advisor 只解释后端已经计算的事实，不计算金额。</p>
      </div>
      <div className="v3-advisor-flow"><div className="v3-advisor-facts"><span className="v3-pill v3-pill-info">Verified financial facts</span><p>异常、预测、敞口与节约建议全部来自后端确定性计算。</p></div><span className="v3-advisor-arrow" aria-hidden="true">→</span><div className="v3-advisor-ai"><span className="v3-pill v3-pill-neutral">AI-generated explanation</span><p>AI 只把事实讲清楚：原因、影响与可做事项。</p></div></div>      <div className="v3-actions"><Link to="/advisor">用 AI Advisor 解释本页 <RightOutlined aria-hidden="true" /></Link></div>
    </section>
  )
}
