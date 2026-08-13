import { Layout, Menu } from 'antd'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { visibleSettingsNav } from '../../features/settings/permissions'

export function AuthenticatedLayout() {
  const auth = useAuth()
  const location = useLocation()
  if (!auth.user) return null

  const entries = visibleSettingsNav(auth.user.permissions)
  const selectedKey = entries.find((entry) => location.pathname.startsWith(entry.path))?.path

  return (
    <Layout className="settings-shell">
      <Layout.Sider width={232} className="settings-sider">
        <div className="settings-brand">AI CostOps</div>
        <Menu
          className="settings-menu"
          mode="inline"
          selectedKeys={selectedKey ? [selectedKey] : []}
          items={entries.map((entry) => ({ key: entry.path, label: <Link to={entry.path}>{entry.label}</Link> }))}
        />
        <div className="settings-identity">
          <span>{auth.user.displayName}</span>
          <button onClick={() => void auth.logout()}>Sign out</button>
        </div>
      </Layout.Sider>
      <Layout.Content className="settings-content"><Outlet /></Layout.Content>
    </Layout>
  )
}
