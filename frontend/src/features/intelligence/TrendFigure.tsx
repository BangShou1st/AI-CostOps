import { percentOf, ratioOf } from './decimal'
import { formatMoney } from './format'

interface TrendFigureProps {
  immediateExposure: string
  projectedPeriodEnd: string
  budgetTotal: string
  currency: string
  forecastMethod: string
  forecastConfidence: string
  historyBucketCount: number
  observedThrough: string
  idPrefix?: string
}

export interface ProgressionGeometry {
  immediateRatio: number
  projectedRatio: number
  overImmediate: boolean
  overProjected: boolean
}

const DOMAIN_MAX = 1.06
const LABEL_ESTIMATE_PX = 118

/**
 * Pure geometry for tests. Ratios are NON-authoritative visual positions only;
 * Immediate and Projected are fully independent measures (either may exceed
 * the other or the budget). Backend amounts and risk stay authoritative.
 */
export function progressionGeometry(immediate: string, projected: string, budget: string): ProgressionGeometry {
  const clamp = (r: number | null) => Math.max(0, r ?? 0)
  const immediateRatio = clamp(ratioOf(immediate, budget))
  const projectedRatio = clamp(ratioOf(projected, budget))
  return {
    immediateRatio,
    projectedRatio,
    overImmediate: immediateRatio > 1,
    overProjected: projectedRatio > 1,
  }
}

/**
 * Period-progression figure. Immediate Exposure and Projected Period End are
 * two independent bars on one shared 0-100% budget scale with WATCH (75%),
 * HIGH (90%) and Budget (100%) markers. Ticks derive from exact decimal math.
 * Frontend never re-derives risk.
 */
export function TrendFigure(props: TrendFigureProps) {
  const { currency } = props
  const geo = progressionGeometry(props.immediateExposure, props.projectedPeriodEnd, props.budgetTotal)
  const W = 760
  const H = 208
  const plotL = 118
  const plotR = 548
  const gutterX = 566
  const scale = (r: number) => plotL + (Math.min(r, DOMAIN_MAX) / DOMAIN_MAX) * (plotR - plotL)
  const rowImmediateY = 66
  const rowProjectedY = 118
  const barH = 22
  const xWatch = scale(0.75)
  const xHigh = scale(0.9)
  const xBudget = scale(1)
  const xImmediate = scale(geo.immediateRatio)
  const xProjected = scale(geo.projectedRatio)
  const gid = props.idPrefix ?? 'v3trend'
  const immediateLabelInside = xImmediate + 8 + LABEL_ESTIMATE_PX > plotR
  const projectedLabelInside = xProjected + 8 + LABEL_ESTIMATE_PX > plotR
  const tickLabel = (percent: number) => formatMoney(percentOf(props.budgetTotal, percent) ?? '', currency)

  const amountLabel = (x: number, inside: boolean, darkOnLight: boolean, text: string) => (
    <text
      className="v3-fig-detail"
      x={inside ? x - 8 : x + 8}
      y={5}
      fontSize={12}
      fontWeight={800}
      fill={inside && !darkOnLight ? '#ffffff' : '#172033'}
      textAnchor={inside ? 'end' : 'start'}
      style={{ fontVariantNumeric: 'tabular-nums' }}
    >
      {text}
    </text>
  )

  return (
    <figure className="v3-figure" aria-labelledby={`${gid}-title`}>
      <span id={`${gid}-title`} className="sr-only">
        {`Period progression in ${currency}: immediate exposure ${props.immediateExposure}, projected period end ${props.projectedPeriodEnd}, budget ${props.budgetTotal}.`}
      </span>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-hidden="true" focusable="false">
        {[25, 50, 75, 100].map((percent) => (
          <g key={percent}>
            <line x1={scale(percent / 100)} y1={40} x2={scale(percent / 100)} y2={rowProjectedY + barH} stroke="#e2e7f0" strokeWidth={1} />
            <text x={scale(percent / 100)} y={rowProjectedY + barH + 34} textAnchor="middle" fontSize={11} className="v3-fig-detail" fill="#8494ab" style={{ fontVariantNumeric: 'tabular-nums' }}>
              {tickLabel(percent)}
            </text>
          </g>
        ))}
        <line x1={xWatch} y1={40} x2={xWatch} y2={rowProjectedY + barH} stroke="#b45309" strokeWidth={1.5} strokeDasharray="2 4" strokeLinecap="round">
          <title>WATCH boundary 75% of budget</title>
        </line>
        <line x1={xHigh} y1={40} x2={xHigh} y2={rowProjectedY + barH} stroke="#b42318" strokeWidth={1.5} strokeDasharray="2 4" strokeLinecap="round">
          <title>HIGH boundary 90% of budget</title>
        </line>
        <line x1={xBudget} y1={40} x2={xBudget} y2={rowProjectedY + barH} stroke="#172033" strokeWidth={2}>
          <title>Budget 100%</title>
        </line>
        <g transform={`translate(0 ${rowImmediateY})`}>
          <text x={plotL - 10} y={5} fontSize={12} fontWeight={700} fill="#475569" textAnchor="end" className="v3-fig-detail">Immediate</text>
          <rect x={plotL} y={-barH / 2} width={Math.max(xImmediate - plotL, 2)} height={barH} rx={6} fill="#1d4ed8">
            <title>{`Immediate exposure ${formatMoney(props.immediateExposure, currency)}`}</title>
          </rect>
          {geo.overImmediate && <text x={plotR + 4} y={6} fontSize={12} fontWeight={800} fill="#b42318" aria-hidden="true">▸</text>}
          {amountLabel(immediateLabelInside ? Math.max(xImmediate, plotL + LABEL_ESTIMATE_PX) : xImmediate, immediateLabelInside, false, formatMoney(props.immediateExposure, currency))}
        </g>
        <g transform={`translate(0 ${rowProjectedY})`}>
          <text x={plotL - 10} y={5} fontSize={12} fontWeight={700} fill="#475569" textAnchor="end" className="v3-fig-detail">Projected</text>
          <rect x={plotL} y={-barH / 2} width={Math.max(xProjected - plotL, 2)} height={barH} rx={6} fill="#e4ebf5" stroke="#8494ab" strokeWidth={2} strokeDasharray="7 5">
            <title>{`Projected period end ${formatMoney(props.projectedPeriodEnd, currency)}`}</title>
          </rect>
          {geo.overProjected && <text x={plotR + 4} y={6} fontSize={12} fontWeight={800} fill="#b42318" aria-hidden="true">▸</text>}
          {amountLabel(projectedLabelInside ? Math.max(xProjected, plotL + LABEL_ESTIMATE_PX) : xProjected, projectedLabelInside, true, formatMoney(props.projectedPeriodEnd, currency))}
        </g>
        <line x1={xBudget} y1={52} x2={gutterX - 6} y2={46} stroke="#cbd4e3" strokeWidth={1} className="v3-fig-detail" />
        <line x1={xHigh} y1={58} x2={gutterX - 6} y2={76} stroke="#cbd4e3" strokeWidth={1} className="v3-fig-detail" />
        <line x1={xWatch} y1={64} x2={gutterX - 6} y2={106} stroke="#cbd4e3" strokeWidth={1} className="v3-fig-detail" />
        <g fontSize={12} className="v3-fig-detail">
          <line x1={gutterX} y1={42} x2={gutterX + 20} y2={42} stroke="#172033" strokeWidth={2.5} />
          <text x={gutterX + 26} y={46} fontWeight={700} fill="#172033">Budget · 100%</text>
          <line x1={gutterX} y1={72} x2={gutterX + 20} y2={72} stroke="#b42318" strokeWidth={2.5} strokeDasharray="2 4" strokeLinecap="round" />
          <text x={gutterX + 26} y={76} fontWeight={700} fill="#b42318">High risk · 90%</text>
          <line x1={gutterX} y1={102} x2={gutterX + 20} y2={102} stroke="#b45309" strokeWidth={2.5} strokeDasharray="2 4" strokeLinecap="round" />
          <text x={gutterX + 26} y={106} fontWeight={700} fill="#b45309">WATCH · 75%</text>
        </g>
      </svg>
      <div className="v3-fig-mobile">
        <div><span>Immediate exposure</span><strong>{formatMoney(props.immediateExposure, currency)}</strong></div>
        <div><span>Projected period end</span><strong>{formatMoney(props.projectedPeriodEnd, currency)}</strong></div>
        <div className="v3-fig-mobile-scale">WATCH ≥ 75% · HIGH ≥ 90% · Budget 100%</div>
        <div className="v3-fig-mobile-method">{props.forecastMethod} · {props.forecastConfidence} · {props.historyBucketCount} buckets · through {props.observedThrough}</div>
      </div>
      <div className="v3-legend" aria-hidden="true">
        <span className="lg-actual"><i />Immediate exposure (solid)</span>
        <span className="lg-forecast"><i />Projected period end (dashed outline)</span>
        <span className="lg-budget"><i />Budget 100%</span>
        <span className="lg-threshold"><i />WATCH 75% / HIGH 90% (dotted)</span>
      </div>
      <figcaption>
        <strong>{`Deterministic ${props.forecastMethod} projection with ${props.forecastConfidence} confidence`}</strong>
        {`, from ${props.historyBucketCount} history buckets observed through ${props.observedThrough}. Immediate and Projected are independent backend measures; reservations are never extrapolated. Forecast is derived evidence, not Ledger truth.`}
      </figcaption>
    </figure>
  )
}
