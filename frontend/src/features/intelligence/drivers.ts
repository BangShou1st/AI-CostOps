/**
 * M18 anomaly driver parsing. Backend shape per driver:
 * { dimension: string, key: string, delta: decimal-string }.
 * Never throws in render paths: invalid JSON or unknown future fields degrade
 * to an explicit graceful state instead of breaking the page.
 */

export interface AnomalyDriver {
  dimension: string
  key: string
  delta: string
}

export type DriversParseResult =
  | { ok: true; drivers: AnomalyDriver[] }
  | { ok: false; reason: 'empty' | 'invalid-json' | 'invalid-shape' }

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function parseDriversJson(raw: string | null | undefined): DriversParseResult {
  if (raw === null || raw === undefined || raw.trim() === '') return { ok: false, reason: 'empty' }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { ok: false, reason: 'invalid-json' }
  }
  if (!Array.isArray(parsed)) return { ok: false, reason: 'invalid-shape' }
  const drivers: AnomalyDriver[] = []
  for (const item of parsed) {
    if (!isRecord(item)) return { ok: false, reason: 'invalid-shape' }
    const { dimension, key, delta } = item
    if (typeof dimension !== 'string' || typeof key !== 'string' || typeof delta !== 'string') {
      return { ok: false, reason: 'invalid-shape' }
    }
    drivers.push({ dimension, key, delta })
  }
  return { ok: true, drivers }
}
