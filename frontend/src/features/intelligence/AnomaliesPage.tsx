import { useQuery } from '@tanstack/react-query'
import { Alert, Drawer, Empty, Select } from 'antd'
import { useMemo, useState } from 'react'
import { toProblemDetail } from '../../api/problem'
import { intelligenceApi } from './api/intelligenceApi'
import { intelligenceKeys } from './api/intelligenceKeys'
import type { CostAnomaly, IntelligenceSummary } from './api/intelligenceTypes'
import { absDecimal, compareDecimal, sortByAmountDesc } from './decimal'
import { parseDriversJson } from './drivers'
import { formatMoney, formatPercent } from './format'
import { grainLabel, OverviewSkeleton, severityOf } from './OverviewSections'
import './v3-tokens.css'

export interface AnomaliesPreview {
  currency: string
  anomalies: CostAnomaly[]
  summary: IntelligenceSummary | null
}

type SeverityFilter = 'ALL' | 'CRITICAL' | 'WATCH' | 'INFO'

function severityKey(a: CostAnomaly): SeverityFilter {
  const z = Math.abs(a.robustZScore)
  const pct = absDecimal(a.deltaPercent)
  if (z >= 5 || compareDecimal(pct, '50') >= 0) return 'CRITICAL'
  if (z >= 3 || compareDecimal(pct, '20') >= 0) return 'WATCH'
  return 'INFO'
}

/** Real M18 anomalies workspace. preview is DEV-benchmark-only. */
export function AnomaliesPage(props: { preview?: AnomaliesPreview }) {
  const [currency, setCurrency] = useState('USD')
  const [severity, setSeverity] = useState<SeverityFilter>('ALL')
  const [grain, setGrain] = useState<string>('ALL')
  const [selected, setSelected] = useState<CostAnomaly | null>(null)
  const preview = props.preview
  const activeCurrency = preview?.currency ?? currency

  const listQuery = useQuery({
    queryKey: intelligenceKeys.anomalies(activeCurrency),
    queryFn: () => intelligenceApi.anomalies(activeCurrency),
    enabled: !preview,
    staleTime: 30_000,
  })
  const anomalies = preview?.anomalies ?? listQuery.data ?? []
  const isLoading = !preview && listQuery.isLoading
  const loadProblem = !preview && listQuery.error ? (toProblemDetail(listQuery.error) as { title?: string; detail?: string }) : null

  const grains = useMemo(() => [...new Set(anomalies.map((a) => a.grainType))].sort(), [anomalies])
  const visible = useMemo(() => {
    const filtered = anomalies.filter((a) => (severity === 'ALL' || severityKey(a) === severity) && (grain === 'ALL' || a.grainType === grain))
    return sortByAmountDesc(filtered, (a) => absDecimal(a.deltaAmount))
  }, [anomalies, severity, grain])

  return (
    <main className="v3-page">
      <header>
        <span className="v3-eyebrow">Cost Intelligence · 成本智能</span>
        <h1>异常</h1>
        <p className="v3-lede">确定性检出：median／MAD／robust-z 规则，不是 AI 异常检测。只呈现超过材料性门槛的异常。</p>
        <dl className="v3-meta">
          <div><dt>币种</dt><dd>{preview ? activeCurrency : (
            <Select value={activeCurrency} onChange={setCurrency} aria-label="结算币种" size="small"
              options={['USD', 'CNY', 'EUR', 'GBP'].map((c) => ({ value: c, label: c }))} />
          )}</dd></div>
          <div><dt>检出</dt><dd>{anomalies.length} 条</dd></div>
        </dl>
      </header>
      <section className="v3-section" aria-label="筛选">
        <div className="v3-filters">
          <label>严重程度
            <Select value={severity} onChange={setSeverity} aria-label="严重程度筛选" size="small" style={{ minWidth: 130 }}
              options={[{ value: 'ALL', label: '全部' }, { value: 'CRITICAL', label: '严重' }, { value: 'WATCH', label: '需关注' }, { value: 'INFO', label: '一般' }]} />
          </label>
          <label>粒度
            <Select value={grain} onChange={setGrain} aria-label="粒度筛选" size="small" style={{ minWidth: 160 }}
              options={[{ value: 'ALL', label: '全部粒度' }, ...grains.map((g) => ({ value: g, label: g }))]} />
          </label>
        </div>
      </section>
      {loadProblem && <div role="alert"><Alert type="error" showIcon message={loadProblem.title ?? '加载失败'} description={loadProblem.detail ?? '请稍后重试。'} /></div>}
      {isLoading && <OverviewSkeleton />}
      {!isLoading && !loadProblem && visible.length === 0 && (
        <div style={{ marginTop: 20 }}><Empty description={anomalies.length === 0 ? '本周期暂无异常。检测门槛：robust-z ≥ 3，变化幅度 ≥ 20%，且超过币种重要性阈值。' : '当前筛选下暂无异常。'} /></div>
      )}
      {!isLoading && !loadProblem && visible.length > 0 && (
        <ul className="v3-list" style={{ marginTop: 16 }}>
          {visible.map((a) => {
            const sev = severityOf(a)
            return (
              <li key={a.id} className="v3-row">
                <div className="v3-row-main">
                  <p className="v3-row-title"><span className={`v3-pill ${sev.pill}`}><span aria-hidden="true">{sev.icon}</span>{sev.text}</span>{' '}{grainLabel(a)}</p>
                  <p className="v3-row-detail">观测 {formatMoney(a.observedAmount, activeCurrency)}，基线 {formatMoney(a.baselineAmount, activeCurrency)}，robust-z {a.robustZScore.toFixed(1)}</p>
                </div>
                <div className="v3-row-amount"><div className="a v3-delta-up">{formatMoney(a.deltaAmount, activeCurrency)}</div><div className="d v3-delta-up">{formatPercent(a.deltaPercent)}</div></div>
                <div className="v3-row-action"><button type="button" className="v3-link-button" onClick={() => setSelected(a)} aria-label={`查看证据 ${grainLabel(a)}`}>证据</button></div>
              </li>
            )
          })}
        </ul>
      )}
      <Drawer open={selected !== null} onClose={() => setSelected(null)} title="异常证据" width={440}>
        {selected && <AnomalyEvidence anomaly={selected} currency={activeCurrency} />}
      </Drawer>
    </main>
  )
}

export function AnomalyEvidence(props: { anomaly: CostAnomaly; currency: string }) {
  const { anomaly: a, currency } = props
  const sev = severityOf(a)
  const parsed = parseDriversJson(a.driversJson)
  return (
    <dl className="v3-evidence">
      <div><dt>严重程度</dt><dd><span className={`v3-pill ${sev.pill}`}>{sev.text}</span></dd></div>
      <div><dt>粒度</dt><dd>{grainLabel(a)}</dd></div>
      <div><dt>观测金额</dt><dd>{formatMoney(a.observedAmount, currency)}</dd></div>
      <div><dt>基线金额</dt><dd>{formatMoney(a.baselineAmount, currency)}</dd></div>
      <div><dt>差额</dt><dd>{formatMoney(a.deltaAmount, currency)}（{formatPercent(a.deltaPercent)}）</dd></div>
      <div><dt>robust-z</dt><dd>{a.robustZScore.toFixed(2)}</dd></div>
      <div><dt>驱动归因</dt><dd>
        {!parsed.ok && parsed.reason === 'empty' && '无驱动归因记录。'}
        {!parsed.ok && parsed.reason !== 'empty' && <span role="status">驱动证据暂时不可读，不影响异常与金额本身。</span>}
        {parsed.ok && parsed.drivers.length === 0 && '无驱动归因记录。'}
        {parsed.ok && parsed.drivers.length > 0 && (
          <ul className="v3-evidence-drivers">
            {parsed.drivers.map((d, i) => (
              <li key={`${d.dimension}-${d.key}-${i}`}>{d.dimension} · {d.key}：{formatMoney(d.delta, currency)}</li>
            ))}
          </ul>
        )}
      </dd></div>
      <div><dt>检测规则</dt><dd>median／MAD／robust-z ≥ 3，且变化幅度 ≥ 20%，且超过币种重要性阈值。</dd></div>
    </dl>
  )
}
