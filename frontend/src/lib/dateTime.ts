const BUSINESS_DATE_PATTERN = /^(\d{4}-\d{2}-\d{2})/

const EM_DASH = '—'

/**
 * The single technical-instant formatter for the whole application. Technical
 * timestamps are always the UTC wall clock with an explicit zone label, so the
 * same instant renders identically on every page regardless of the viewer.
 */
const UTC_DATE_TIME_FORMATTER = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'UTC',
  calendar: 'gregory',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

const UTC_DAY_FORMATTER = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'UTC',
  day: '2-digit',
  month: 'short',
})

/** Presentation for API values that represent a business date, not an instant. */
export function formatBusinessDate(value: string | null | undefined): string {
  if (!value) return '—'
  return BUSINESS_DATE_PATTERN.exec(value)?.[1] ?? value
}

export function formatBusinessDateRange(
  start: string | null | undefined,
  end: string | null | undefined,
): string {
  return `${formatBusinessDate(start)} ～ ${formatBusinessDate(end)}`
}

const BILLING_PERIOD_STATUS_LABELS: Record<string, string> = {
  OPEN: '开放',
  CLOSING: '结算中',
  CLOSED: '已关闭',
}

export function formatBillingPeriodLabel(
  start: string,
  end: string,
  status: string,
): string {
  const statusLabel = BILLING_PERIOD_STATUS_LABELS[status] ?? status
  return `${formatBusinessDateRange(start, end)}（${statusLabel} · ${status}）`
}

/** Presentation for an event instant. API values remain unchanged at the boundary. */
export function formatUtcDateTime(value: string | null | undefined): string {
  if (!value) return EM_DASH
  const timestamp = new Date(value)
  if (Number.isNaN(timestamp.getTime())) return EM_DASH

  const parts = UTC_DATE_TIME_FORMATTER.formatToParts(timestamp)
  const valueOf = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return `${valueOf('year')}-${valueOf('month')}-${valueOf('day')} ${valueOf('hour')}:${valueOf('minute')} UTC`
}

/**
 * Feature-page name for the technical-instant presentation. Kept as an alias so
 * every call site shares exactly one implementation.
 */
export const formatEventDateTime = formatUtcDateTime

/** UTC calendar-day label (no zone suffix) for date-bucketed charts. */
export function formatUtcDay(value: string | null | undefined): string {
  if (!value) return EM_DASH
  const date = new Date(value.length <= 10 ? `${value}T00:00:00Z` : value)
  if (Number.isNaN(date.getTime())) return EM_DASH
  return UTC_DAY_FORMATTER.format(date)
}
