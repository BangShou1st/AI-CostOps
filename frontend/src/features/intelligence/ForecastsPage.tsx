import { useQuery } from '@tanstack/react-query'
import { Alert, Empty, Select } from 'antd'
import { useMemo, useState } from 'react'
import { toProblemDetail } from '../../api/problem'
import { intelligenceApi } from './api/intelligenceApi'
import { intelligenceKeys } from './api/intelligenceKeys'
import type { CostForecast } from './api/intelligenceTypes'
import { formatMoney } from './format'
import { OverviewSkeleton } from './OverviewSections'
import './v3-tokens.css'

export interface ForecastsPreview {
  currency: string
  forecasts: CostForecast[]
}

const METHOD_NOTE: Record<string, string> = {
  DAMPED_HOLT: '阻尼 Holt 主方法：历史 ≥ 14 个桶时使用，兼顾趋势与阻尼。',
  RECENT_RUN_RATE: '近期速率兜底：历史 3–13 个桶时使用，仅外推近期水平，不做趋势幻想。',
}

const CONFIDENCE_PILL: Record<string, string> = {
  HIGH: 'v3-pill-ok',
  MEDIUM: 'v3-pill-watch',
  LOW: 'v3-pill-neutral',
}

/** Real M18 forecasts. preview is DEV-benchmark-only. */
export function ForecastsPage(props: { preview?: ForecastsPreview }) {
  const [currency, setCurrency] = useState('USD')
  const [scope, setScope] = useState('ALL')
  const preview = props.preview
  const activeCurrency = preview?.currency ?? currency

  const listQuery = useQuery({
    queryKey: intelligenceKeys.forecasts(activeCurrency),
    queryFn: () => intelligenceApi.forecasts(activeCurrency),
    enabled: !preview,
    staleTime: 30_000,
  })
  const forecasts = preview?.forecasts ?? listQuery.data ?? []
  const isLoading = !preview && listQuery.isLoading
  const loadProblem = !preview && listQuery.error ? (toProblemDetail(listQuery.error) as { title?: string; detail?: string }) : null

  const scopes = useMemo(() => [...new Set(forecasts.map((f) => f.scopeType))].sort(), [forecasts])
  const org = forecasts.find((f) => f.scopeType === 'ORGANIZATION')
  const visible = forecasts.filter((f) => scope === 'ALL' || f.scopeType === scope)

  return (
    <main className="v3-page">
      <header>
        <span className="v3-eyebrow">Cost Intelligence · 成本智能</span>
        <h1>预测</h1>
        <p className="v3-lede">确定性时间序列外推，不是 AI 预测。预测是衍生证据，不是账本真相。</p>
        <dl className="v3-meta">
          <div><dt>币种</dt><dd>{preview ? activeCurrency : (
            <Select value={activeCurrency} onChange={setCurrency} aria-label="结算币种" size="small"
              options={['USD', 'CNY', 'EUR', 'GBP'].map((c) => ({ value: c, label: c }))} />
          )}</dd></div>
          <div><dt>范围数</dt><dd>{forecasts.length}</dd></div>
        </dl>
      </header>
      <section className="v3-section" aria-labelledby="v3-forecast-method">
        <div className="v3-section-head">
          <span className="v3-eyebrow">Method</span>
          <h2 id="v3-forecast-method">方法与口径</h2>
          <p>DAMPED_HOLT 为主方法；历史不足时显式降级为 RECENT_RUN_RATE，不伪装精度。</p>
        </div>
      </section>
      {org && (
        <section className="v3-hero-figure" aria-label="组织级预测">
          <div className="k">组织周期预计</div>
          <div className="v">{formatMoney(org.projectedAmount, activeCurrency)}</div>
          <div className="s">{org.method} · {org.confidence} 置信度 · {org.historyBucketCount} 个历史桶 · 观测至 {org.observedThrough}</div>
        </section>
      )}
      <section className="v3-section" aria-label="筛选">
        <div className="v3-filters">
          <label>范围类型
            <Select value={scope} onChange={setScope} aria-label="范围类型筛选" size="small" style={{ minWidth: 150 }}
              options={[{ value: 'ALL', label: '全部范围' }, ...scopes.map((s) => ({ value: s, label: s }))]} />
          </label>
        </div>
      </section>
      {loadProblem && <div role="alert"><Alert type="error" showIcon message={loadProblem.title ?? '加载失败'} description={loadProblem.detail ?? '请稍后重试。'} /></div>}
      {isLoading && <OverviewSkeleton />}
      {!isLoading && !loadProblem && visible.length === 0 && (
        <div style={{ marginTop: 20 }}><Empty description="暂无预测。历史粒度不足时不会生成预测，也不做 AI 幻想。" /></div>
      )}
      {!isLoading && !loadProblem && visible.length > 0 && (
        <ul className="v3-list" style={{ marginTop: 16 }}>
          {visible.map((f) => (
            <li key={`${f.scopeType}-${f.scopeKey}`} className="v3-row">
              <div className="v3-row-main">
                <p className="v3-row-title"><span className={`v3-pill ${CONFIDENCE_PILL[f.confidence] ?? 'v3-pill-neutral'}`}>{f.confidence} 置信度</span>{' '}{f.scopeType} · {f.scopeKey}</p>
                <p className="v3-row-detail">{f.method} · {METHOD_NOTE[f.method] ?? '确定性方法。'}历史 {f.historyBucketCount} 桶 · 观测至 {f.observedThrough}</p>
              </div>
              <div className="v3-row-amount"><div className="a">{formatMoney(f.projectedAmount, activeCurrency)}</div></div>
            </li>
          ))}
        </ul>
      )}
      <p className="v3-footnote">预测金额由后端 BigDecimal 计算，前端只做呈现；同一逻辑口径内比较，不做跨币种换算。</p>
    </main>
  )
}
