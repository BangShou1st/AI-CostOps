/**
 * Exact scale-8 money arithmetic for allocation amounts.
 *
 * The UI's authoritative amount state is always the decimal string the
 * backend returned; BigInt minor units exist only for exact calculations and
 * are never sent to the API. Anything that is not an exact
 * {@code ^-?[0-9]+\.[0-9]{8}$} string is rejected.
 */
export type Decimal8 = bigint

const SCALE_FACTOR = 100000000n
const MONEY_PATTERN = /^-?[0-9]+\.[0-9]{8}$/

/** Parses a scale-8 decimal string into minor units; rejects anything else. */
export function parseDecimal8(amount: string): bigint {
  if (typeof amount !== 'string' || !MONEY_PATTERN.test(amount)) {
    throw new Error(`Not an exact scale-8 money string: ${String(amount)}`)
  }
  const [whole, fraction] = amount.split('.')
  const negative = whole.startsWith('-')
  const digits = (negative ? whole.slice(1) : whole) + fraction
  const value = BigInt(digits)
  return negative ? -value : value
}

const USER_MONEY_PATTERN = /^-?[0-9]+(\.[0-9]{1,8})?$/

/**
 * Parses user-typed money (integer or up to 8 fractional digits) into scale-8
 * minor units. The API only accepts exact scale-8 strings, so the UI
 * normalizes typed amounts through this before sending them.
 */
export function parseUserDecimal8(amount: string): bigint {
  if (typeof amount !== 'string' || !USER_MONEY_PATTERN.test(amount)) {
    throw new Error(`Not a parseable money amount: ${String(amount)}`)
  }
  const [whole, fraction = ''] = amount.split('.')
  return parseDecimal8(`${whole}.${fraction.padEnd(8, '0')}`)
}

/** Formats minor units back into a canonical scale-8 decimal string. */
export function formatDecimal8(value: bigint): string {
  const negative = value < 0n
  const absolute = negative ? -value : value
  const whole = absolute / SCALE_FACTOR
  const fraction = (absolute % SCALE_FACTOR).toString().padStart(8, '0')
  return `${negative ? '-' : ''}${whole.toString()}.${fraction}`
}

export function addDecimal8(left: bigint, right: bigint): bigint {
  return left + right
}

export function subtractDecimal8(left: bigint, right: bigint): bigint {
  return left - right
}

export function sumDecimal8(values: readonly bigint[]): bigint {
  return values.reduce((total, value) => total + value, 0n)
}

export function compareDecimal8(left: bigint, right: bigint): number {
  return left < right ? -1 : left > right ? 1 : 0
}

/**
 * Formats an API scale-8 money string for people without converting it to a
 * JavaScript number. Ordinary values keep two display decimals; when any
 * meaningful precision exists beyond cents, the exact scale-8 value is shown
 * with trailing zeroes removed so a real reconciliation difference never
 * becomes a misleading 0.00. The currency renders as an unambiguous code
 * suffix (e.g. "1.25 CNY"), never a symbol.
 */
export function formatMoney(amount: string | null | undefined, currency?: string | null): string {
  if (!amount) return '—'

  let minorUnits: bigint
  try {
    minorUnits = parseDecimal8(amount)
  } catch {
    return '—'
  }

  const negative = minorUnits < 0n
  const absolute = negative ? -minorUnits : minorUnits
  const whole = absolute / SCALE_FACTOR
  const exactFraction = (absolute % SCALE_FACTOR).toString().padStart(8, '0')
  const significantFraction = exactFraction.replace(/0+$/, '')
  const fraction = significantFraction.length > 2
    ? significantFraction
    : significantFraction.padEnd(2, '0')
  const groupedWhole = whole.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const numberText = `${negative ? '-' : ''}${groupedWhole}.${fraction}`
  const normalizedCurrency = currency?.trim().toUpperCase()
  return normalizedCurrency ? `${numberText} ${normalizedCurrency}` : numberText
}
