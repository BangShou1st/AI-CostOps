import { act, fireEvent, render, screen } from '@testing-library/react'
import { lazy } from 'react'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RouteContent } from './RouteContent'

afterEach(() => vi.restoreAllMocks())

describe('RouteContent', () => {
  it('shows loading until a deferred page module arrives', async () => {
    let finish!: (module: { default: () => React.JSX.Element }) => void
    const Page = lazy(() => new Promise<{ default: () => React.JSX.Element }>(resolve => { finish = resolve }))
    render(<MemoryRouter><RouteContent><Page /></RouteContent></MemoryRouter>)
    expect(screen.getByRole('status')).toHaveTextContent('正在加载页面')
    await act(async () => finish({ default: () => <h1>Loaded page</h1> }))
    expect(screen.getByRole('heading', { name: 'Loaded page' })).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('contains a failed import and recovers when navigating to another page', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const FailedPage = lazy(() => Promise.reject(new Error('Failed to fetch dynamically imported module')))
    render(
      <MemoryRouter initialEntries={['/broken']}>
        <nav><Link to="/healthy">其他页面</Link></nav>
        <RouteContent><Routes>
          <Route path="/broken" element={<FailedPage />} />
          <Route path="/healthy" element={<h1>Healthy page</h1>} />
        </Routes></RouteContent>
      </MemoryRouter>,
    )
    expect(await screen.findByRole('alert')).toHaveTextContent('页面加载失败')
    expect(screen.getByRole('button', { name: '重新加载页面' })).toBeEnabled()
    fireEvent.click(screen.getByRole('link', { name: '其他页面' }))
    expect(screen.getByRole('heading', { name: 'Healthy page' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
