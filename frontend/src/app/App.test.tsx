import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'

describe('App', () => {
  it('identifies the repository foundation milestone', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'AI CostOps' })).toBeInTheDocument()
    expect(screen.getByText(/repository foundation/i)).toBeInTheDocument()
  })
})
