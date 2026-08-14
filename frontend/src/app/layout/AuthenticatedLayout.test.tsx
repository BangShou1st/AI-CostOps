import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { resetMediaMatches, setMediaMatches } from '../../test/setup'
import { AuthenticatedLayout } from './AuthenticatedLayout'

vi.mock('../../features/auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)

const ALL_PERMISSIONS = ['USER_READ', 'ROLE_READ', 'PROJECT_READ', 'TEAM_READ', 'COST_CENTER_READ', 'PROVIDER_ACCOUNT_READ']
const NAV_LABELS = ['用户管理', '角色与权限', '项目管理', '团队管理', '成本中心', '云账号']

function renderLayout(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  return render(
    <MemoryRouter initialEntries={['/settings/users']}>
      <Routes>
        <Route element={<AuthenticatedLayout />}>
          <Route path="/settings/users" element={<h1>Users page</h1>} />
          <Route path="/settings/projects" element={<h1>Projects page</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  resetMediaMatches()
  localStorage.clear()
})

describe('AuthenticatedLayout desktop sidebar', () => {
  it('hidesNavigationWithoutReadPermission', () => {
    renderLayout(['USER_READ'])

    expect(screen.getByRole('menuitem', { name: '用户管理' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: '项目管理' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: '角色与权限' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: '团队管理' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: '成本中心' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: '云账号' })).not.toBeInTheDocument()
  })

  it('shows every settings item with all read permissions', () => {
    renderLayout(ALL_PERMISSIONS)

    for (const label of NAV_LABELS) {
      expect(screen.getByRole('menuitem', { name: label })).toBeInTheDocument()
    }
  })

  it('renders the protected page outlet', () => {
    renderLayout(['USER_READ'])

    expect(screen.getByRole('heading', { name: 'Users page' })).toBeInTheDocument()
  })

  it('keeps logout available', () => {
    renderLayout([])

    fireEvent.click(screen.getByRole('button', { name: '退出登录' }))
    expect(mockedUseAuth.mock.results[0].value.logout).toHaveBeenCalledTimes(1)
  })

  it('sidebarExpandedRendersLabels', () => {
    renderLayout(ALL_PERMISSIONS)

    expect(screen.getByText('AI CostOps')).toBeInTheDocument()
    for (const label of NAV_LABELS) {
      expect(screen.getByRole('menuitem', { name: label })).toBeInTheDocument()
    }
    expect(screen.getByRole('button', { name: '收起侧边栏' })).toBeInTheDocument()
  })

  it('sidebarCollapseHidesLabelsButRetainsNav', () => {
    renderLayout(ALL_PERMISSIONS)

    fireEvent.click(screen.getByRole('button', { name: '收起侧边栏' }))

    expect(screen.getByRole('button', { name: '展开侧边栏' })).toBeInTheDocument()
    // The nav keeps every entry; the rail collapses to icons only (jsdom
    // cannot assert rendered pixels, so the collapsed class is the contract).
    expect(screen.getAllByRole('menuitem')).toHaveLength(NAV_LABELS.length)
    expect(document.querySelector('.settings-shell-collapsed')).not.toBeNull()
    expect(document.querySelector('.ant-menu-inline-collapsed')).not.toBeNull()
  })

  it('collapseButtonAriaLabelToggles', () => {
    renderLayout(ALL_PERMISSIONS)

    const collapse = screen.getByRole('button', { name: '收起侧边栏' })
    fireEvent.click(collapse)
    expect(screen.getByRole('button', { name: '展开侧边栏' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '展开侧边栏' }))
    expect(screen.getByRole('button', { name: '收起侧边栏' })).toBeInTheDocument()
  })

  it('collapsedPreferenceRoundTripsThroughStorage', () => {
    const first = renderLayout(ALL_PERMISSIONS)
    fireEvent.click(screen.getByRole('button', { name: '收起侧边栏' }))

    expect(localStorage.getItem('aicostops:settings-sidebar-collapsed')).toBe('true')

    // A fresh mount restores the preference.
    first.unmount()
    renderLayout(ALL_PERMISSIONS)
    expect(screen.getByRole('button', { name: '展开侧边栏' })).toBeInTheDocument()
  })

  it('malformedPreferenceFallsBackSafely', () => {
    localStorage.setItem('aicostops:settings-sidebar-collapsed', 'not-a-boolean')

    renderLayout(ALL_PERMISSIONS)

    expect(screen.getByRole('button', { name: '收起侧边栏' })).toBeInTheDocument()
    expect(document.querySelector('.settings-shell-collapsed')).toBeNull()
  })

  it('userFooterRemainsStructuralFooter', () => {
    renderLayout(ALL_PERMISSIONS)

    const identity = document.querySelector('.settings-identity')
    expect(identity).not.toBeNull()
    // The footer is a direct child of the sidebar, not of the scroll area.
    expect(identity?.parentElement?.className).toContain('settings-sider')
  })

  it('desktopShellIsHorizontalSplitContract', () => {
    renderLayout(ALL_PERMISSIONS)

    // The desktop root carries the horizontal-shell contract class; the
    // sidebar and the main content remain siblings inside it.
    const root = document.querySelector('.ant-layout.settings-shell')
    expect(root).not.toBeNull()
    const sider = document.querySelector('.settings-sider')
    const content = document.querySelector('.settings-content')
    expect(sider).not.toBeNull()
    expect(content).not.toBeNull()
    expect(sider?.parentElement).toBe(root)
    expect(content?.parentElement).toBe(root)
    expect(document.querySelector('.settings-shell-mobile')).toBeNull()
  })

  it('collapsedClassOnlyTogglesSidebarState', () => {
    renderLayout(ALL_PERMISSIONS)

    fireEvent.click(screen.getByRole('button', { name: '收起侧边栏' }))

    // The collapse class sits on the shell root; the sidebar keeps its own
    // classes and main keeps its Layout.Content classes untouched.
    expect(document.querySelector('.ant-layout.settings-shell.settings-shell-collapsed')).not.toBeNull()
    expect(document.querySelector('.settings-sider')).not.toBeNull()
    expect(document.querySelector('.settings-content')?.className).toContain('ant-layout-content')
  })
})

describe('AuthenticatedLayout mobile navigation', () => {
  it('mobileUsesMenuButtonAndDrawerWithoutPermanentSidebar', () => {
    setMediaMatches({ '(min-width: 1024px)': false })
    renderLayout(ALL_PERMISSIONS)

    expect(document.querySelector('.settings-sider')).toBeNull()
    // The mobile path keeps its own shell root and is unaffected by the
    // desktop horizontal-split contract.
    expect(document.querySelector('.app-shell-mobile')).not.toBeNull()
    expect(screen.getByRole('button', { name: '菜单' })).toBeInTheDocument()
    expect(screen.getByText('AI CostOps')).toBeInTheDocument()
  })

  it('mobileDrawerOpensClosesAndSelectsRoute', async () => {
    setMediaMatches({ '(min-width: 1024px)': false })
    renderLayout(ALL_PERMISSIONS)

    fireEvent.click(screen.getByRole('button', { name: '菜单' }))
    const drawer = await screen.findByRole('dialog')
    expect(drawer).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: '项目管理' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '退出登录' })).toBeInTheDocument()

    // Selecting a route closes the Drawer.
    fireEvent.click(screen.getByRole('menuitem', { name: '项目管理' }))
    expect(screen.getByRole('heading', { name: 'Projects page' })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})
