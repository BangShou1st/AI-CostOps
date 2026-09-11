import { formatMoney } from './format'

interface TrendFigureProps {
  immediateExposure: string
  projectedPeriodEnd: string
  budgetTotal: string
  riskThreshold: string
  currency: string
  forecastMethod: string
  forecastConfidence: string
  historyBucketCount: number
  observedThrough: string
  idPrefix?: string
}

/**
 * Period-progression figure: immediate exposure (solid) extends to the
 * deterministic forecast (dashed) against budget cap + risk threshold.
 * Budget/threshold annotations live in a right-hand gutter with leader lines
 * so labels can never collide no matter how close the two lines fall.
 * Encoding is line style + label + text summary, never color alone.
 */
export function TrendFigure(props: TrendFigureProps) {
  const { currency } = props
  const toNum = (v: string) => {
    const n = Number(v)
    return Number.isFinite(n) ? n : 0
  }
  const budget = Math.max(toNum(props.budgetTotal), 1)
  const exposure = toNum(props.immediateExposure)
  const projected = toNum(props.projectedPeriodEnd)
  const threshold = toNum(props.riskThreshold)
  const W = 760
  const H = 172
  const plotL = 8
  const plotR = 544
  const gutterX = 562
  const domain = budget * 1.04
  const scale = (v: number) => plotL + (Math.max(0, Math.min(v, domain)) / domain) * (plotR - plotL)
  const barY = 62
  const barH = 26
  const x0 = scale(0)
  const xExposure = scale(exposure)
  const xProjected = scale(Math.max(projected, exposure))
  const xThreshold = scale(threshold)
  const xBudget = scale(budget)
  const gid = props.idPrefix ?? 'v3trend'
  const ticks = [budget * 0.25, budget * 0.5, budget * 0.75, budget]

  return (
    <figure className="v3-figure" aria-labelledby={`${gid}-title`}>
      <span id={`${gid}-title`} className="sr-only">
        {`Period progression in ${currency}: immediate exposure ${props.immediateExposure}, projected period end ${props.projectedPeriodEnd}, budget ${props.budgetTotal}.`}
      </span>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-hidden="true" focusable="false">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={scale(t)} y1={44} x2={scale(t)} y2={barY + barH + 8} stroke="#e2e7f0" strokeWidth={1} />
            <text x={scale(t)} y={barY + barH + 46} textAnchor="middle" fontSize={11} fill="#8494ab" style={{ fontVariantNumeric: 'tabular-nums' }}>
              {formatMoney(String(Math.round(t)), currency)}
            </text>
          </g>
        ))}
        <line x1={xThreshold} y1={44} x2={xThreshold} y2={barY + barH + 8} stroke="#b45309" strokeWidth={1.5} strokeDasharray="2 4" strokeLinecap="round">
          <title>{`Risk threshold ${formatMoney(props.riskThreshold, currency)}`}</title>
        </line>
        <rect x={x0} y={barY} width={Math.max(xBudget - x0, 2)} height={barH} rx={6} fill="#f7f9fc" stroke="#e2e7f0" />
        <rect x={x0} y={barY} width={Math.max(xExposure - x0, 2)} height={barH} rx={6} fill="#1d4ed8">
          <title>{`Immediate exposure ${formatMoney(props.immediateExposure, currency)}`}</title>
        </rect>
        {xProjected > xExposure + 1 && (
          <g>
            <rect x={xExposure} y={barY} width={xProjected - xExposure} height={barH} fill="none" stroke="#8494ab" strokeWidth={2} strokeDasharray="7 5" />
            <title>{`Forecast extension to ${formatMoney(props.projectedPeriodEnd, currency)}`}</title>
          </g>
        )}
        <line x1={xBudget} y1={50} x2={xBudget} y2={barY + barH} stroke="#172033" strokeWidth={2}>
          <title>{`Budget ${formatMoney(props.budgetTotal, currency)}`}</title>
        </line>
        <line x1={xBudget} y1={barY + 6} x2={gutterX - 6} y2={barY - 14} stroke="#cbd4e3" strokeWidth={1} />
        <line x1={xThreshold} y1={barY + barH - 4} x2={gutterX - 6} y2={barY + barH + 16} stroke="#cbd4e3" strokeWidth={1} />
        <text x={plotL} y={30} fontSize={12} fontWeight={700} fill="#475569" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {`Exposed ${formatMoney(props.immediateExposure, currency)}`}
        </text>
        <text x={xProjected} y={barY + barH + 30} fontSize={11} fontWeight={700} fill="#475569" textAnchor="end" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {`Projected ${formatMoney(props.projectedPeriodEnd, currency)}`}
        </text>
        <g fontSize={12}>
          <line x1={gutterX} y1={barY - 18} x2={gutterX + 20} y2={barY - 18} stroke="#172033" strokeWidth={2.5} />
          <text x={gutterX + 26} y={barY - 14} fontWeight={700} fill="#172033" style={{ fontVariantNumeric: 'tabular-nums' }}>
            {`Budget ${formatMoney(props.budgetTotal, currency)}`}
          </text>
          <line x1={gutterX} y1={barY + barH + 12} x2={gutterX + 20} y2={barY + barH + 12} stroke="#b45309" strokeWidth={2.5} strokeDasharray="2 4" strokeLinecap="round" />
          <text x={gutterX + 26} y={barY + barH + 16} fontWeight={700} fill="#b45309" style={{ fontVariantNumeric: 'tabular-nums' }}>
            {`Threshold ${formatMoney(props.riskThreshold, currency)}`}
          </text>
        </g>
      </svg>
      <div className="v3-legend" aria-hidden="true">
        <span className="lg-actual"><i />Actual exposure (solid)</span>
        <span className="lg-forecast"><i />Forecast extension (dashed)</span>
        <span className="lg-budget"><i />Budget cap</span>
        <span className="lg-threshold"><i />Risk threshold (dotted)</span>
      </div>
      <figcaption>
        <strong>{`Deterministic ${props.forecastMethod} projection with ${props.forecastConfidence} confidence`}</strong>
        {`, from ${props.historyBucketCount} history buckets observed through ${props.observedThrough}. Forecast is derived evidence, not Ledger truth.`}
      </figcaption>
    </figure>
  )
}
