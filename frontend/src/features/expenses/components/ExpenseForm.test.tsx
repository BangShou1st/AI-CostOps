import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ExpenseForm } from './ExpenseForm'

describe('expense money entry', () => {
  afterEach(async () => {
    // Settle pending rc-component/antd useDelayState timers before test worker teardown
    await new Promise(resolve => setTimeout(resolve, 50))
  })

  it('preserves the DECIMAL(20,8) upper bound when saving an existing value', async () => {
    const submit = vi.fn()
    render(<ExpenseForm editable amount="999999999999.99999999" expenseDate="2026-10-10" onSubmit={submit} />)
    expect(screen.getByRole('spinbutton')).toHaveValue('999999999999.99999999')
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
    await waitFor(() => expect(submit).toHaveBeenCalledWith({ expenseDate: '2026-10-10', amount: '999999999999.99999999', currency: 'CNY' }))
  })

  it.each(['0.00000001', '-12.34567890', '100.00000000'])('sends %s exactly', async amount => {
    const submit = vi.fn()
    render(<ExpenseForm editable amount={amount} expenseDate="2026-10-10" onSubmit={submit} />)
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
    await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ amount })))
  })

  it.each(['1000000000000', '1.123456789'])('rejects %s without rounding or calling the API', async amount => {
    const submit = vi.fn()
    render(<ExpenseForm editable amount={amount} expenseDate="2026-10-10" onSubmit={submit} />)
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
    expect(await screen.findByText('金额最多12位整数、8位小数，请勿使用科学计数法')).toBeInTheDocument()
    expect(submit).not.toHaveBeenCalled()
  })
})
