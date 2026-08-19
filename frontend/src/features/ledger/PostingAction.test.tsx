import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { PostingAction } from './PostingAction'

function renderAction(props: React.ComponentProps<typeof PostingAction>) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><PostingAction {...props} /></QueryClientProvider>)
}

describe('PostingAction', () => {
  it('posts once and invokes the completion callback', async () => {
    const onPost = vi.fn().mockResolvedValue({ id: '900' })
    const onCompleted = vi.fn()
    renderAction({ onPost, onCompleted })

    fireEvent.click(screen.getByRole('button', { name: /记\s*账/ }))

    await waitFor(() => expect(onPost).toHaveBeenCalledTimes(1))
    expect(onCompleted).toHaveBeenCalledTimes(1)
  })

  it('surfaces a server conflict without retrying the financial command', async () => {
    const onPost = vi.fn().mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Period closed', status: 409, code: 'PERIOD_NOT_OPEN', detail: 'The period is closed.', traceId: null } },
    })
    const onCompleted = vi.fn()
    renderAction({ onPost, onCompleted })

    fireEvent.click(screen.getByRole('button', { name: /记\s*账/ }))

    await waitFor(() => expect(screen.getByText('当前账期未开放，暂不能执行此操作。')).toBeInTheDocument())
    expect(onPost).toHaveBeenCalledTimes(1)
    expect(onCompleted).not.toHaveBeenCalled()
  })

  it('keeps a readiness explanation visible when posting is disabled', () => {
    renderAction({ onPost: vi.fn(), onCompleted: vi.fn(), disabled: true, disabledReason: '请先确认分摊' })

    expect(screen.getByRole('button', { name: /记\s*账/ })).toBeDisabled()
    expect(screen.getByText('请先确认分摊')).toBeInTheDocument()
  })
})
