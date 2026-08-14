import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'
import type {
  Invitation, MasterDataRecord, MasterDataStatus, OrganizationMemberRecord, Permission, ProviderAccount, Role, RoleAssignment, ScopeType, User, UserStatus,
} from './settingsTypes'

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
  async listProjects(page: number, size: number, status?: MasterDataStatus) {
    return (await apiClient.get<PageResponse<MasterDataRecord>>('/projects', { params: { page, size, status } })).data
  },
  async createProject(code: string, name: string) {
    return (await apiClient.post<MasterDataRecord>('/projects', { code, name })).data
  },
  async updateProject(id: string, update: { name?: string; status?: MasterDataStatus }) {
    return (await apiClient.patch<MasterDataRecord>(`/projects/${encodeURIComponent(id)}`, update)).data
  },
  async listProjectMembers(id: string, page: number, size: number) {
    return (await apiClient.get<PageResponse<OrganizationMemberRecord>>(`/projects/${encodeURIComponent(id)}/members`, { params: { page, size } })).data
  },
  async addProjectMember(id: string, organizationMemberId: string) {
    return (await apiClient.post<OrganizationMemberRecord>(`/projects/${encodeURIComponent(id)}/members`, { organizationMemberId })).data
  },
  async removeProjectMember(id: string, memberId: string) {
    await apiClient.delete(`/projects/${encodeURIComponent(id)}/members/${encodeURIComponent(memberId)}`)
  },
  async listTeams(page: number, size: number, status?: MasterDataStatus) {
    return (await apiClient.get<PageResponse<MasterDataRecord>>('/teams', { params: { page, size, status } })).data
  },
  async createTeam(code: string, name: string) {
    return (await apiClient.post<MasterDataRecord>('/teams', { code, name })).data
  },
  async updateTeam(id: string, update: { name?: string; status?: MasterDataStatus }) {
    return (await apiClient.patch<MasterDataRecord>(`/teams/${encodeURIComponent(id)}`, update)).data
  },
  async listTeamMembers(id: string, page: number, size: number) {
    return (await apiClient.get<PageResponse<OrganizationMemberRecord>>(`/teams/${encodeURIComponent(id)}/members`, { params: { page, size } })).data
  },
  async addTeamMember(id: string, organizationMemberId: string) {
    return (await apiClient.post<OrganizationMemberRecord>(`/teams/${encodeURIComponent(id)}/members`, { organizationMemberId })).data
  },
  async removeTeamMember(id: string, memberId: string) {
    await apiClient.delete(`/teams/${encodeURIComponent(id)}/members/${encodeURIComponent(memberId)}`)
  },
  async listCostCenters(page: number, size: number, status?: MasterDataStatus) {
    return (await apiClient.get<PageResponse<MasterDataRecord>>('/cost-centers', { params: { page, size, status } })).data
  },
  async createCostCenter(code: string, name: string) {
    return (await apiClient.post<MasterDataRecord>('/cost-centers', { code, name })).data
  },
  async updateCostCenter(id: string, update: { name?: string; status?: MasterDataStatus }) {
    return (await apiClient.patch<MasterDataRecord>(`/cost-centers/${encodeURIComponent(id)}`, update)).data
  },
  async listProviderAccounts(page: number, size: number, status?: MasterDataStatus) {
    return (await apiClient.get<PageResponse<ProviderAccount>>('/provider-accounts', { params: { page, size, status } })).data
  },
  async createProviderAccount(input: {
    providerCode: string
    displayName: string
    externalAccountRef?: string
    metadata?: Record<string, unknown>
  }) {
    return (await apiClient.post<ProviderAccount>('/provider-accounts', input)).data
  },
  async updateProviderAccount(id: string, update: {
    displayName?: string
    externalAccountRef?: string
    status?: MasterDataStatus
    metadata?: Record<string, unknown>
  }) {
    return (await apiClient.patch<ProviderAccount>(`/provider-accounts/${encodeURIComponent(id)}`, update)).data
  },
}
