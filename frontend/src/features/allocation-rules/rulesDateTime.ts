import type { Dayjs } from 'dayjs'

/**
 * Serializes the picker's local datetime as an explicit ISO-8601 Instant so
 * the backend {@code Instant} fields always receive a timezone. A timezone-
 * less string like "2026-08-21T00:00:00" is exactly what human acceptance
 * found being rejected as REQUEST_MALFORMED (RC-UAT-02).
 */
export function toInstant(value: Dayjs | null | undefined): string | null {
  return value ? value.toISOString() : null
}

export interface DateTimeFieldContext {
  getFieldValue: (name: string) => Dayjs | null | undefined
}

/**
 * Ant Design field rule: the optional end datetime must be strictly after the
 * start datetime, validated in the form before any request is made.
 */
export function endAfterStart(otherField: string) {
  return ({ getFieldValue }: DateTimeFieldContext) => ({
    validator(_: unknown, value: Dayjs | null | undefined) {
      const start = getFieldValue(otherField)
      if (!value || !start || value.isAfter(start)) return Promise.resolve()
      return Promise.reject(new Error('失效时间必须晚于生效时间'))
    },
  })
}
