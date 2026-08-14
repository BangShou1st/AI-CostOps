import { Outlet } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { hasPermission } from '../../features/settings/permissions'
import { ForbiddenPage } from './ForbiddenPage'

export function PermissionRoute({ permission }: { permission: string }) {
  const auth = useAuth()
  if (!hasPermission(auth.user?.permissions, permission)) {
    return <ForbiddenPage />
  }
  return <Outlet />
}
