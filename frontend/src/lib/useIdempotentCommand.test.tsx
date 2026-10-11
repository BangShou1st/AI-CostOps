import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useIdempotentCommand } from './useIdempotentCommand'

describe('idempotent command retries', () => {
  it('reuses the original key when the response is lost and changes it for a new successful action', async () => {
    const { result } = renderHook(() => useIdempotentCommand())
    const send = vi.fn().mockRejectedValueOnce(new Error('response lost')).mockResolvedValue('created')
    await act(async () => { await expect(result.current('create', { amount: '100' }, send)).rejects.toThrow('response lost') })
    await act(async () => { expect(await result.current('create', { amount: '100' }, send)).toBe('created') })
    expect(send.mock.calls[1][0]).toBe(send.mock.calls[0][0])
    await act(async () => { await result.current('create', { amount: '100' }, send) })
    expect(send.mock.calls[2][0]).not.toBe(send.mock.calls[0][0])
  })

  it('coalesces a double click and waits for the same request to finish', async () => {
    let finish!: (value: string) => void
    const send = vi.fn(() => new Promise<string>(resolve => { finish = resolve }))
    const { result } = renderHook(() => useIdempotentCommand())
    const first = result.current('submit:1', { expectedVersion: 2 }, send)
    const second = result.current('submit:1', { expectedVersion: 2 }, send)
    await Promise.resolve()
    expect(send).toHaveBeenCalledTimes(1)
    finish('submitted')
    expect(await first).toBe('submitted')
    expect(await second).toBe('submitted')
  })

  it('uses a fresh key for corrected input and keeps different actions separate', async () => {
    const { result } = renderHook(() => useIdempotentCommand())
    const send = vi.fn().mockRejectedValue(new Error('failed'))
    await expect(result.current('create', { amount: '100' }, send)).rejects.toThrow()
    await expect(result.current('create', { amount: '105' }, send)).rejects.toThrow()
    await expect(result.current('approve:1', { amount: '105' }, send)).rejects.toThrow()
    expect(new Set(send.mock.calls.map(call => call[0])).size).toBe(3)
  })
})
