import { useCallback, useRef } from 'react'

/** Same action and payload reuse the key after an uncertain response.
 * Only one mutation may run at a time in this page. Nothing is persisted to storage. */
export function useExpenseCommand() {
  const attempts = useRef(new Map<string, { payload: string; key: string }>())
  const running = useRef<{ action: string; payload: string; result: Promise<unknown> } | null>(null)

  return useCallback(<T,>(action: string, body: unknown, send: (key: string) => Promise<T>): Promise<T> => {
    const payload = JSON.stringify(body)
    if (running.current) {
      if (running.current.action === action && running.current.payload === payload) return running.current.result as Promise<T>
      return Promise.reject(new Error('请等待当前操作完成'))
    }
    let attempt = attempts.current.get(action)
    if (!attempt || attempt.payload !== payload) {
      attempt = { payload, key: crypto.randomUUID() }
      attempts.current.set(action, attempt)
    }
    const key = attempt.key
    const result = Promise.resolve().then(() => send(key)).then(value => {
      attempts.current.delete(action)
      return value
    }).finally(() => { running.current = null })
    running.current = { action, payload, result }
    return result
  }, [])
}
