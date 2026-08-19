const BUSINESS_DATE_PATTERN = /^(\d{4}-\d{2}-\d{2})/

const EVENT_DATE_TIME_FORMATTER = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  calendar: 'gregory',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
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
export function formatEventDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const timestamp = new Date(value)
  if (Number.isNaN(timestamp.getTime())) return value

  const parts = EVENT_DATE_TIME_FORMATTER.formatToParts(timestamp)
  const valueOf = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return `${valueOf('year')}-${valueOf('month')}-${valueOf('day')} ${valueOf('hour')}:${valueOf('minute')}`
}
