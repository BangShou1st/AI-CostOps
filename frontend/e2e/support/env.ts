/**
 * E2E environment contract. Every value here is synthetic CI-only and is never
 * a real credential: the dev bootstrap identity is created by the backend from
 * the same environment the Compose stack was started with.
 */
export const E2E_BASE_URL: string =
  process.env.AICOSTOPS_E2E_BASE_URL ?? 'http://localhost:8080'

export const ADMIN_EMAIL: string =
  process.env.AICOSTOPS_E2E_ADMIN_EMAIL ?? 'admin@example.test'

export const ADMIN_PASSWORD: string =
  process.env.AICOSTOPS_E2E_ADMIN_PASSWORD ?? 'change-me-local-only'

export const API_ROOT: string = `${E2E_BASE_URL}/api/v1`

/** UTC business moment inside the current open billing period (day 2 of month). */
export function currentBusinessDates(): {
  startIso: string
  endIso: string
  expenseDate: string
  month: string
} {
  const now = new Date()
  const yyyy = now.getUTCFullYear()
  const mm = String(now.getUTCMonth() + 1).padStart(2, '0')
  return {
    startIso: `${yyyy}-${mm}-02T00:00:00Z`,
    endIso: `${yyyy}-${mm}-02T01:00:00Z`,
    expenseDate: `${yyyy}-${mm}-02`,
    month: `${yyyy}-${mm}`,
  }
}

export function uniqueSuffix(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}