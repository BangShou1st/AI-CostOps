/**
 * Decimal-string presentation primitives (M19).
 * Money stays a decimal-string end to end. Display formatting, sorting and
 * comparison never route through IEEE-754 Number, so float precision can never
 * decide financial ordering. Chart geometry may derive a NON-authoritative
 * visual ratio from these strings (see ratioOf); backend values stay truth.
 */

export interface DecimalParts {
  negative: boolean
  int: string
  frac: string
}

const DECIMAL_RE = /^(-?)(\d+)(?:\.(\d+))?$/

export function parseDecimal(value: string | null | undefined): DecimalParts | null {
  if (value === null || value === undefined) return null
  const text = value.trim()
  if (text === '') return null
  const match = DECIMAL_RE.exec(text)
  if (!match) return null
  const int = match[2].replace(/^0+(?=\d)/, '')
  return { negative: match[1] === '-', int, frac: match[3] ?? '' }
}

function compareMagnitude(left: DecimalParts, right: DecimalParts): number {
  if (left.int.length !== right.int.length) return left.int.length < right.int.length ? -1 : 1
  if (left.int !== right.int) return left.int < right.int ? -1 : 1
  const width = Math.max(left.frac.length, right.frac.length)
  const lf = left.frac.padEnd(width, '0')
  const rf = right.frac.padEnd(width, '0')
  if (lf !== rf) return lf < rf ? -1 : 1
  return 0
}

/** Exact decimal-string comparison. Returns -1 | 0 | 1. Invalid inputs sort as equal-zero (never throw in render paths). */
export function compareDecimal(a: string | null | undefined, b: string | null | undefined): number {
  const left = parseDecimal(a)
  const right = parseDecimal(b)
  if (!left && !right) return 0
  if (!left || !right) return 0
  if (left.negative !== right.negative) return left.negative ? -1 : 1
  const magnitude = compareMagnitude(left, right)
  return left.negative ? -magnitude : magnitude
}

export function absDecimal(value: string): string {
  const parsed = parseDecimal(value)
  if (!parsed) return '0'
  return `${parsed.int}${parsed.frac ? `.${parsed.frac}` : ''}`
}

function groupInt(int: string): string {
  return int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** Round-half-up the frac part to exactly `places` digits, carrying into int. Pure string math. */
function roundFrac(int: string, frac: string, places: number): { int: string; frac: string } {
  const digits = (frac + '0'.repeat(places + 1)).slice(0, places + 1).split('').map(Number)
  let carry = digits[places] >= 5 ? 1 : 0
  const kept = digits.slice(0, places)
  for (let i = kept.length - 1; i >= 0 && carry > 0; i--) {
    const sum = kept[i] + carry
    kept[i] = sum % 10
    carry = sum >= 10 ? 1 : 0
  }
  let outInt = int
  if (carry > 0) {
    const chars = outInt.split('').map(Number)
    let c = 1
    for (let i = chars.length - 1; i >= 0 && c > 0; i--) {
      const sum = chars[i] + c
      chars[i] = sum % 10
      c = sum >= 10 ? 1 : 0
    }
    outInt = `${c > 0 ? '1' : ''}${chars.join('')}`
  }
  return { int: outInt, frac: kept.join('').padEnd(places, '0') }
}

/** Exact display formatting of a decimal-string: grouped int + fixed decimals. No Number involved. */
export function formatDecimal(value: string | null | undefined, places = 2): string | null {
  const parsed = parseDecimal(value)
  if (!parsed) return null
  const rounded = roundFrac(parsed.int, parsed.frac, places)
  const sign = parsed.negative && !(rounded.int === '0' && /^0*$/.test(rounded.frac)) ? '-' : ''
  return `${sign}${groupInt(rounded.int)}.${rounded.frac}`
}

const CURRENCY_SYMBOLS: Record<string, string> = { USD: '$', CNY: '¥', EUR: '€', GBP: '£', JPY: '¥' }

/** Exact money formatting. Returns null when the input is not a decimal-string. */
export function formatMoneyExact(amount: string | null | undefined, currency: string): string | null {
  const body = formatDecimal(amount, 2)
  if (body === null) return null
  const symbol = CURRENCY_SYMBOLS[currency]
  return symbol ? `${symbol}${body}` : `${body} ${currency}`
}

/** NON-authoritative visual ratio for chart geometry only. Never financial truth. */
export function ratioOf(part: string | null | undefined, whole: string | null | undefined): number | null {
  const p = parseDecimal(part)
  const w = parseDecimal(whole)
  if (!p || !w || p.negative || w.negative) return null
  const scale = 12
  const pint = BigInt(p.int + p.frac.padEnd(scale, '0').slice(0, scale))
  const wint = BigInt(w.int + w.frac.padEnd(scale, '0').slice(0, scale))
  if (wint === 0n) return null
  return Number((pint * 10000n) / wint) / 10000
}

/** Decimal-safe descending amount sort for presentation lists. */
export function sortByAmountDesc<T>(rows: readonly T[], pick: (row: T) => string | null | undefined): T[] {
  return [...rows].sort((a, b) => compareDecimal(pick(b), pick(a)))
}


/** Exact value * percent / 100 as decimal-string (truncated, never float). For chart ticks. */
export function percentOf(value: string | null | undefined, percent: number): string | null {
  const parsed = parseDecimal(value);
  if (!parsed || !Number.isInteger(percent) || percent < 0) return null;
  const scale = Math.max(parsed.frac.length, 2);
  const digits = BigInt(parsed.int + parsed.frac.padEnd(scale, '0')) * BigInt(percent);
  const q = digits / 100n;
  const s = q.toString().padStart(scale + 1, '0');
  const intPart = s.slice(0, -scale).replace(/^0+(?=\\d)/, '') || '0';
  return `${parsed.negative ? '-' : ''}${intPart}.${s.slice(-scale)}`;
}
