/**
 * Newest-first ordering for lists whose backend ids are numeric strings.
 *
 * `localeCompare` compares digit strings lexicographically, so ids from 10
 * upward are ordered incorrectly ("9" sorts after "100"). Compare the parsed
 * value instead; ids are database longs carried as strings, so use BigInt to
 * avoid precision loss. Non-numeric ids fall back to the previous
 * locale-aware descending order rather than throwing.
 */
export function compareNumericIdDesc(left: string, right: string): number {
  const leftId = parseNumericId(left)
  const rightId = parseNumericId(right)
  if (leftId !== null && rightId !== null) {
    if (leftId === rightId) return 0
    return leftId < rightId ? 1 : -1
  }
  return right.localeCompare(left)
}

/** Stable copy of a list sorted by numeric id, newest (largest id) first. */
export function sortByNumericIdDesc<T extends { id: string }>(items: readonly T[]): T[] {
  return [...items].sort((left, right) => compareNumericIdDesc(left.id, right.id))
}

function parseNumericId(value: string): bigint | null {
  return /^\d+$/.test(value) ? BigInt(value) : null
}
