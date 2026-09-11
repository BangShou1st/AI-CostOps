import { describe, expect, it } from 'vitest'
import { NAV_ICONS } from './AuthenticatedLayout'
import { INTELLIGENCE_NAV, visibleIntelligenceNav } from './appNavigation'

describe('V3 intelligence navigation', () => {
  it('exposes the frozen IA in order', () => {
    expect(INTELLIGENCE_NAV.map((e) => e.path)).toEqual([
      '/intelligence/overview',
      '/intelligence/anomalies',
      '/intelligence/forecasts',
      '/intelligence/savings',
      '/advisor',
    ])
  })
  it('gates intelligence entries by read permission', () => {
    expect(visibleIntelligenceNav(undefined)).toEqual([])
    expect(visibleIntelligenceNav(['COST_READ']).map((e) => e.path)).toEqual([
      '/intelligence/overview',
      '/intelligence/anomalies',
      '/intelligence/forecasts',
      '/intelligence/savings',
    ])
    expect(visibleIntelligenceNav(['AI_ADVISOR_USE']).map((e) => e.path)).toEqual(['/advisor'])
  })
  it('provides an icon for every intelligence entry', () => {
    for (const entry of INTELLIGENCE_NAV) {
      expect(NAV_ICONS[entry.path], entry.path).toBeTruthy()
    }
  })
})
