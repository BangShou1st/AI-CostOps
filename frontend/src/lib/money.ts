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
