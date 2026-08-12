import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { ProtectedRoute } from './ProtectedRoute'

function renderProtectedRoute(isAuthenticated: boolean) {
  render(
    <MemoryRouter initialEntries={['/protected']}>
      <Routes>
        <Route path="/" element={<h1>Public foundation</h1>} />
        <Route element={<ProtectedRoute isAuthenticated={isAuthenticated} />}>
          <Route path="/protected" element={<h1>Protected foundation</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProtectedRoute', () => {
  it('redirects unauthenticated access to the public foundation', () => {
    renderProtectedRoute(false)

    expect(screen.getByRole('heading', { name: 'Public foundation' })).toBeInTheDocument()
  })

  it('renders protected content when an access token is available', () => {
    renderProtectedRoute(true)

    expect(screen.getByRole('heading', { name: 'Protected foundation' })).toBeInTheDocument()
  })
})
