import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { AppRouter } from './AppRouter'

vi.mock('../../features/auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../../features/evidence/EvidenceListPage', () => ({
  EvidenceListPage: () => <h1>Evidence list page</h1>,
}))
vi.mock('../../features/evidence/EvidenceDetailPage', () => ({
  EvidenceDetailPage: () => <h1>Evidence detail page</h1>,
}))
vi.mock('../../features/imports/ImportListPage', () => ({
  ImportListPage: () => <h1>Import list page</h1>,
}))
vi.mock('../../features/imports/ImportDetailPage', () => ({
  ImportDetailPage: () => <h1>Import detail page</h1>,
}))
vi.mock('../../features/settings/users/UsersPage', () => ({
  UsersPage: () => <h1>Users page</h1>,
}))

const mockedUseAuth = vi.mocked(useAuth)

function renderRouter(permissions: string[], initialPath: string) {
  window.history.pushState({}, '', initialPath)
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  return render(<AppRouter />)
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.pushState({}, '', '/')
})

describe('AppRouter permission gates', () => {
  it('directEvidenceRouteWithoutEvidenceReadRendersForbiddenAndNeverMountsChildPage', () => {
    renderRouter([], '/evidence/5')

    expect(screen.getByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Evidence detail page' })).not.toBeInTheDocument()
  })

  it('directImportRouteWithoutImportReadRendersForbiddenAndNeverMountsChildPage', () => {
    renderRouter(['EVIDENCE_READ'], '/imports/123')

    expect(screen.getByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Import detail page' })).not.toBeInTheDocument()
  })

  it('evidenceListRouteMountsPageWithEvidenceRead', () => {
    renderRouter(['EVIDENCE_READ'], '/evidence')

    expect(screen.getByRole('heading', { name: 'Evidence list page' })).toBeInTheDocument()
  })

  it('importListRouteMountsPageWithImportRead', () => {
    renderRouter(['IMPORT_READ'], '/imports')

    expect(screen.getByRole('heading', { name: 'Import list page' })).toBeInTheDocument()
  })

  it('evidenceDetailWithoutImportReadStillMountsDetailPage', () => {
    renderRouter(['EVIDENCE_READ'], '/evidence/5')

    expect(screen.getByRole('heading', { name: 'Evidence detail page' })).toBeInTheDocument()
  })

  it('importDetailWithoutEvidenceReadStillMountsDetailPage', () => {
    renderRouter(['IMPORT_READ'], '/imports/123')

    expect(screen.getByRole('heading', { name: 'Import detail page' })).toBeInTheDocument()
  })
})

describe('AppRouter application landing', () => {
  it('appRootLandsOnEvidenceForEvidenceReadOnlyUser', () => {
    renderRouter(['EVIDENCE_READ'], '/app')

    expect(screen.getByRole('heading', { name: 'Evidence list page' })).toBeInTheDocument()
  })

  it('appRootLandsOnImportsForImportReadOnlyUser', () => {
    renderRouter(['IMPORT_READ'], '/app')

    expect(screen.getByRole('heading', { name: 'Import list page' })).toBeInTheDocument()
  })

  it('appRootLandsOnFirstSettingsRouteForSettingsOnlyUser', () => {
    renderRouter(['USER_READ'], '/app')

    expect(screen.getByRole('heading', { name: 'Users page' })).toBeInTheDocument()
  })

  it('authenticatedWildcardReturnsToAppLanding', () => {
    renderRouter(['EVIDENCE_READ'], '/unknown-route')

    expect(screen.getByRole('heading', { name: 'Evidence list page' })).toBeInTheDocument()
  })
})
