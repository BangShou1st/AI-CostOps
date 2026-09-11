import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { progressionGeometry, TrendFigure } from './TrendFigure'

const base = {
  currency: 'USD',
  forecastMethod: 'DAMPED_HOLT',
  forecastConfidence: 'MEDIUM',
  historyBucketCount: 29,
  observedThrough: '2026-09-10',
}

describe('progressionGeometry', () => {
  it('keeps immediate and projected independent when projected is lower', () => {
    const geo = progressionGeometry('16820.45', '12430.22', '20000.00')
    expect(geo.immediateRatio).toBeCloseTo(0.841, 3)
    expect(geo.projectedRatio).toBeCloseTo(0.6215, 3)
    expect(geo.overImmediate).toBe(false)
    expect(geo.overProjected).toBe(false)
  })
  it('handles projected above immediate', () => {
    const geo = progressionGeometry('12430.22', '16820.45', '20000.00')
    expect(geo.immediateRatio).toBeLessThan(geo.projectedRatio)
  })
  it('handles equality and over-budget overflow', () => {
    const equal = progressionGeometry('15000.00', '15000.00', '20000.00')
    expect(equal.immediateRatio).toBeCloseTo(equal.projectedRatio, 6)
    const over = progressionGeometry('21000.00', '22000.00', '20000.00')
    expect(over.overImmediate).toBe(true)
    expect(over.overProjected).toBe(true)
  })
  it('degrades invalid inputs to zero without throwing', () => {
    expect(progressionGeometry('nope', '100', '200')).toEqual({ immediateRatio: 0, projectedRatio: 0.5, overImmediate: false, overProjected: false })
    expect(progressionGeometry('100', '100', '0').immediateRatio).toBe(0)
  })
})

describe('TrendFigure', () => {
  it('renders two independent measures with the frozen risk scale', () => {
    render(<TrendFigure immediateExposure="16820.45" projectedPeriodEnd="12430.22" budgetTotal="20000.00" {...base} />)
    expect(screen.getByText('Budget · 100%')).toBeInTheDocument()
    expect(screen.getByText('High risk · 90%')).toBeInTheDocument()
    expect(screen.getByText('WATCH · 75%')).toBeInTheDocument()
    expect(screen.getByText('Immediate exposure (solid)')).toBeInTheDocument()
    expect(screen.getByText('Projected period end (dashed outline)')).toBeInTheDocument()
  })
})
