import { RightOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import type { CostAnomaly } from './api/intelligenceTypes'
import { absDecimal, sortByAmountDesc } from './decimal'
import { parseDriversJson, type AnomalyDriver } from './drivers'
import { formatMoney } from './format'
import { grainLabel } from './OverviewSections'

interface DriverRow extends AnomalyDriver {
  grain: string
}

/**
 * Restrained Top-Drivers evidence block: dimension / key / delta contribution
 * for the current anomalies. Malformed driver payloads degrade to a note and
 * never break the page.
 */
export function TopDriversSection(props: { anomalies: CostAnomaly[]; currency: string }) {
  const { anomalies, currency } = props
  let malformed = false
  const rows = sortByAmountDesc(
    anomalies.slice(0, 3).flatMap((a) => {
      const parsed = parseDriversJson(a.driversJson)
      if (!parsed.ok) {
        if (parsed.reason !== 'empty') malformed = true
        return [] as DriverRow[]
      }
      return parsed.drivers.map((d) => ({ ...d, grain: grainLabel(a) }))
    }),
    (d) => absDecimal(d.delta),
  ).slice(0, 5)

  if (rows.length === 0 && !malformed) return null
  return (
    <section className="v3-section" aria-labelledby="v3-drivers">
      <div className="v3-section-head">
        <span className="v3-eyebrow">02 · What changed?</span>
        <h3 id="v3-drivers" className="v3-h3">变化归因 · Top drivers</h3>
        <p>异常金额归因到维度证据，按贡献绝对值取前五。</p>
      </div>
      {rows.length > 0 && (
        <ul className="v3-list">
          {rows.map((d, i) => (
            <li key={`${d.dimension}-${d.key}-${i}`} className="v3-row v3-row-slim">
              <div className="v3-row-main">
                <p className="v3-row-title">{d.dimension} · {d.key}</p>
                <p className="v3-row-detail">来自 {d.grain}</p>
              </div>
              <div className="v3-row-amount"><div className="a v3-delta-up">{formatMoney(d.delta, currency)}</div></div>
            </li>
          ))}
        </ul>
      )}
      {malformed && <div className="v3-note" role="status">部分驱动证据暂时不可读，不影响异常与金额本身。</div>}
      <div className="v3-actions"><Link to="/intelligence/anomalies">查看驱动证据 <RightOutlined aria-hidden="true" /></Link></div>
    </section>
  )
}
