import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'
import type { Invitation, Permission, Role, RoleAssignment, ScopeType, User, UserStatus } from './settingsTypes'

export const settingsApi = {
  async listUsers(page: number, size: number) {
    return (await apiClient.get<PageResponse<User>>('/users', { params: { page, size } })).data
  },
  async getUser(id: string) {
    return (await apiClient.get<User>(`/users/${encodeURIComponent(id)}`)).data
  },
  async updateUserStatus(id: string, status: UserStatus, expectedVersion: string) {
    return (await apiClient.patch<User>(`/users/${encodeURIComponent(id)}/status`, { status, expectedVersion })).data
  },
  async listRoles() {
    return (await apiClient.get<Role[]>('/roles')).data
  },
  async listPermissions() {
    return (await apiClient.get<Permission[]>('/permissions')).data
  },
  async createRoleAssignment(organizationMemberId: string, roleId: string, scopeType: ScopeType, scopeId: string) {
    return (await apiClient.post<RoleAssignment>('/role-assignments', { organizationMemberId, roleId, scopeType, scopeId })).data
  },
  async revokeRoleAssignment(id: string) {
    await apiClient.delete(`/role-assignments/${encodeURIComponent(id)}`)
  },
  async createInvitation(email: string, initialRoleCode: string, expiresInHours?: number) {
    return (await apiClient.post<Invitation>('/invitations', { email, initialRoleCode, expiresInHours })).data
  },
}
