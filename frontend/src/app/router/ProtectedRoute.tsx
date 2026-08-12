import { Navigate, Outlet } from 'react-router-dom'

interface ProtectedRouteProps {
  isAuthenticated: boolean
}

export function ProtectedRoute({ isAuthenticated }: ProtectedRouteProps) {
  return isAuthenticated ? <Outlet /> : <Navigate to="/" replace />
}
