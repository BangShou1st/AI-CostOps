import { compareDecimal, formatDecimal, formatMoneyExact, parseDecimal } from './decimal'

export const EM_DASH = '—'

/** Presentation formatting for decimal-string money. Exact, no IEEE-754 path. */
export function formatMoney(amount: string | null | undefined, currency: string): string {
  return formatMoneyExact(amount, currency) ?? EM_DASH
}

const COMPACT_TIERS = [
  { digits: 10, suffix: 'B' },
  { digits: 7, suffix: 'M' },
  { digits: 4, suffix: 'K' },
] as const

/** Compact money from the integer-part length tier; exact value stays in tooltip/detail. */
export function formatCompactMoney(amount: string | null | undefined, currency: string): string {
  const parsed = parseDecimal(amount)
  if (!parsed) return EM_DASH
  const tier = COMPACT_TIERS.find((t) => parsed.int.length >= t.digits)
  if (!tier) return formatMoney(amount, currency)
  const head = parsed.int.slice(0, parsed.int.length - (tier.digits - 1))
  const tail = parsed.int.slice(parsed.int.length - (tier.digits - 1), parsed.int.length - (tier.digits - 3)) + parsed.frac
  const compact = formatDecimal(`${parsed.negative ? '-' : ''}${head}.${tail}`, 2)
  if (compact === null) return formatMoney(amount, currency)
  const symbol = { USD: '$', CNY: '¥', EUR: '€', GBP: '£', JPY: '¥' }[currency]
  return symbol ? `${symbol}${compact}${tier.suffix}` : `${compact}${tier.suffix} ${currency}`
}

/** Exact percent with uniform 1-decimal precision and sign. Input is a decimal-string. */
export function formatPercent(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return EM_DASH
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return EM_DASH
    const sign = value > 0 ? '+' : ''
    return `${sign}${value.toFixed(1)}%`
  }
  const body = formatDecimal(value, 1)
  if (body === null) return EM_DASH
  const sign = !body.startsWith('-') && compareDecimal(value, '0') > 0 ? '+' : ''
  return `${sign}${body}%`
}

export function formatDateTime(value: string | null | undefined, timeZone?: string): string {
  if (!value) return EM_DASH
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return EM_DASH
  return new Intl.DateTimeFormat('en-GB', {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
    timeZone: timeZone ?? 'UTC', timeZoneName: 'short',
  }).format(date)
}

export function formatDay(value: string | null | undefined): string {
  if (!value) return EM_DASH
  const date = new Date(value.length <= 10 ? `${value}T00:00:00Z` : value)
  if (Number.isNaN(date.getTime())) return EM_DASH
  return new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' }).format(date)
}
