import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { ApplicationLanding } from './ApplicationLanding'

vi.mock('../../features/auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)

function renderAppRoot(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  render(
    <MemoryRouter initialEntries={['/app']}>
      <Routes>
        <Route path="/app" element={<ApplicationLanding />} />
        <Route path="/workbench" element={<h1>Workbench page</h1>} />
        <Route path="/evidence" element={<h1>Evidence page</h1>} />
        <Route path="/imports" element={<h1>Imports page</h1>} />
        <Route path="/expenses" element={<h1>Expenses page</h1>} />
        <Route path="/budgets" element={<h1>Budgets page</h1>} />
        <Route path="/expense-reviews" element={<h1>Expense reviews page</h1>} />
        <Route path="/settings/users" element={<h1>Users page</h1>} />
        <Route path="/login" element={<h1>Sign in page</h1>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('ApplicationLanding', () => {
  it('prefers the evidence business route with evidence read', () => {
    renderAppRoot(['EVIDENCE_READ', 'IMPORT_READ'])

    expect(screen.getByRole('heading', { name: 'Evidence page' })).toBeInTheDocument()
  })

  it('falls back to imports when only import read is present', () => {
    renderAppRoot(['IMPORT_READ'])

    expect(screen.getByRole('heading', { name: 'Imports page' })).toBeInTheDocument()
  })

  it('routes employee-only users to their expenses', () => {
    renderAppRoot(['EXPENSE_READ_OWN'])

    expect(screen.getByRole('heading', { name: 'Expenses page' })).toBeInTheDocument()
  })

  it('routes expense reviewers to the review queue when that is their only business permission', () => {
    renderAppRoot(['EXPENSE_REVIEW'])

    expect(screen.getByRole('heading', { name: 'Workbench page' })).toBeInTheDocument()
  })

  it('routes budget-only users to the budgets page', () => {
    renderAppRoot(['BUDGET_READ'])

    expect(screen.getByRole('heading', { name: 'Workbench page' })).toBeInTheDocument()
  })

  it('prefers expenses over budgets when both are readable', () => {
    renderAppRoot(['BUDGET_READ', 'EXPENSE_READ_OWN'])

    expect(screen.getByRole('heading', { name: 'Workbench page' })).toBeInTheDocument()
  })

  it('does not land on budgets without BUDGET_READ even with manage permissions', () => {
    renderAppRoot(['BUDGET_MANAGE', 'COMMITMENT_APPROVE', 'USER_READ'])

    expect(screen.getByRole('heading', { name: 'Users page' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Budgets page' })).not.toBeInTheDocument()
  })

  it('falls back to the first permitted settings route for settings-only roles', () => {
    renderAppRoot(['USER_READ'])

    expect(screen.getByRole('heading', { name: 'Users page' })).toBeInTheDocument()
  })

  it('renders the authenticated forbidden page with no read permissions at all', () => {
    renderAppRoot([])

    expect(screen.getByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in page' })).not.toBeInTheDocument()
  })
})
