import {
  AccountBookOutlined,
  BookOutlined,
  AuditOutlined,
  CloudOutlined,
  DeploymentUnitOutlined,
  FileTextOutlined,
  FolderOutlined,
  ImportOutlined,
  LeftOutlined,
  LogoutOutlined,
  MenuOutlined,
  PayCircleOutlined,
  FileDoneOutlined,
  RightOutlined,
  SafetyOutlined,
  TeamOutlined,
  UserOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { Button, Drawer, Layout, Menu, Tooltip } from 'antd'
import { useState, type ReactNode } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { visibleSettingsNav } from '../../features/settings/permissions'
import { FINANCE_NAV_PATHS, visibleBusinessNav } from './appNavigation'
import { SETTINGS_COPY } from '../../features/settings/presentation'
import { useMediaQuery } from './useMediaQuery'

const SIDEBAR_COLLAPSED_KEY = 'aicostops:settings-sidebar-collapsed'
const DESKTOP_QUERY = '(min-width: 1024px)'

export const NAV_ICONS: Record<string, ReactNode> = {
  '/evidence': <FileTextOutlined />,
  '/imports': <ImportOutlined />,
  '/costs': <PayCircleOutlined />,
  '/allocation-rules': <DeploymentUnitOutlined />,
  '/expenses': <FileDoneOutlined />,
  '/budgets': <WalletOutlined />,
  '/expense-reviews': <AuditOutlined />,
  '/ledger': <BookOutlined />,
  '/reconciliation': <AuditOutlined />,
  '/period-close': <AccountBookOutlined />,
  '/settings/users': <UserOutlined />,
  '/settings/roles': <SafetyOutlined />,
  '/settings/projects': <FolderOutlined />,
  '/settings/teams': <TeamOutlined />,
  '/settings/cost-centers': <AccountBookOutlined />,
  '/settings/provider-accounts': <CloudOutlined />,
}

function readCollapsedPreference(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === 'true'
  } catch {
    return false
  }
}

function writeCollapsedPreference(collapsed: boolean) {
  try {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(collapsed))
  } catch {
    // Storage unavailable: keep the in-memory preference only.
  }
}

export function AuthenticatedLayout() {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const isDesktop = useMediaQuery(DESKTOP_QUERY)
  const [collapsed, setCollapsed] = useState(readCollapsedPreference)
  const [drawerOpen, setDrawerOpen] = useState(false)
  if (!auth.user) return null

  const businessEntries = visibleBusinessNav(auth.user.permissions)
  const settingsEntries = visibleSettingsNav(auth.user.permissions)
  const entries = [...businessEntries, ...settingsEntries]
  const selectedKey = entries.find((entry) => location.pathname.startsWith(entry.path))?.path
  const currentLabel = entries.find((entry) => entry.path === selectedKey)?.label ?? ''

  const menuEntry = (entry: typeof entries[number]) => ({
      key: entry.path,
      icon: <span aria-hidden="true">{NAV_ICONS[entry.path]}</span>,
      label: entry.label,
    })
  const financeEntries = businessEntries
    .filter((entry) => FINANCE_NAV_PATHS.includes(entry.path as typeof FINANCE_NAV_PATHS[number]))
    .sort((left, right) => FINANCE_NAV_PATHS.indexOf(left.path as typeof FINANCE_NAV_PATHS[number]) - FINANCE_NAV_PATHS.indexOf(right.path as typeof FINANCE_NAV_PATHS[number]))
  const nonFinanceEntries = businessEntries.filter((entry) => !FINANCE_NAV_PATHS.includes(entry.path as typeof FINANCE_NAV_PATHS[number]))
  const businessMenuItems = [
    ...nonFinanceEntries.map(menuEntry),
    ...(nonFinanceEntries.length > 0 && financeEntries.length > 0
      ? [{ type: 'divider' as const, key: 'finance-divider' }]
      : []),
    ...(financeEntries.length > 0
      ? [{ type: 'group' as const, key: 'finance-group', label: 'Finance', children: financeEntries.map(menuEntry) }]
      : []),
  ]
  const menuItems = [
    ...businessMenuItems,
    ...(businessEntries.length > 0 && settingsEntries.length > 0
      ? [{ type: 'divider' as const, key: 'nav-divider' }]
      : []),
    ...settingsEntries.map(menuEntry),
  ]

  const selectRoute = (path: string) => {
    setDrawerOpen(false)
    navigate(path)
  }

  const toggleCollapsed = () => {
    setCollapsed((previous) => {
      const next = !previous
      writeCollapsedPreference(next)
      return next
    })
  }

  const initials = auth.user.displayName.trim().charAt(0).toUpperCase() || 'U'

  if (!isDesktop) {
    return (
      <div className="app-shell-mobile">
        <header className="settings-topbar">
          <Button
            type="text"
            className="settings-topbar-menu"
            aria-label={SETTINGS_COPY.menu}
            icon={<MenuOutlined />}
            onClick={() => setDrawerOpen(true)}
          />
          <span className="settings-topbar-brand">{SETTINGS_COPY.brand}</span>
          <span className="settings-topbar-page">{currentLabel}</span>
        </header>
        <Drawer
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          placement="left"
          size={280}
          title={SETTINGS_COPY.brand}
        >
          <div className="settings-drawer-body">
            <Menu
              className="settings-menu settings-menu-drawer"
              mode="inline"
              selectedKeys={selectedKey ? [selectedKey] : []}
              items={menuItems}
              onClick={({ key }) => selectRoute(key)}
            />
            <div className="settings-identity">
              <span className="settings-identity-user">{auth.user.displayName}</span>
              <button onClick={() => void auth.logout()}>{SETTINGS_COPY.signOut}</button>
            </div>
          </div>
        </Drawer>
        <main className="settings-content"><Outlet /></main>
      </div>
    )
  }

  return (
    <Layout className={`settings-shell${collapsed ? ' settings-shell-collapsed' : ''}`}>
      <aside className="settings-sider">
        <div className="settings-brand">
          {collapsed
            ? <span className="settings-brand-mark">AC</span>
            : <span className="settings-brand-name">{SETTINGS_COPY.brand}</span>}
          <button
            type="button"
            className="settings-collapse"
            aria-label={collapsed ? SETTINGS_COPY.expandSidebar : SETTINGS_COPY.collapseSidebar}
            onClick={toggleCollapsed}
          >
            {collapsed ? <RightOutlined /> : <LeftOutlined />}
          </button>
        </div>
        <div className="settings-nav-scroll">
          <Menu
            className="settings-menu"
            mode="inline"
            inlineCollapsed={collapsed}
            selectedKeys={selectedKey ? [selectedKey] : []}
            items={menuItems}
            onClick={({ key }) => selectRoute(key)}
          />
        </div>
        <div className="settings-identity">
          {collapsed ? (
            <>
              <span className="settings-avatar" aria-hidden="true">{initials}</span>
              <Tooltip title={SETTINGS_COPY.signOut} placement="right">
                <button type="button" className="settings-logout-icon" aria-label={SETTINGS_COPY.signOut} onClick={() => void auth.logout()}>
                  <LogoutOutlined />
                </button>
              </Tooltip>
            </>
          ) : (
            <>
              <span className="settings-identity-user">{auth.user.displayName}</span>
              <button type="button" onClick={() => void auth.logout()}>{SETTINGS_COPY.signOut}</button>
            </>
          )}
        </div>
      </aside>
      <Layout.Content className="settings-content"><Outlet /></Layout.Content>
    </Layout>
  )
}
