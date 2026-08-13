export type MasterDataStatus = 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
export type UserStatus = 'ACTIVE' | 'DISABLED'
export type ScopeType = 'ORG' | 'PROJECT' | 'TEAM' | 'COST_CENTER'

export interface OrganizationMember {
  id: string
  status: UserStatus
  employeeNo: string | null
  defaultCostCenterId: string | null
}

export interface RoleReference {
  id: string
  code: string
  name: string
}

export interface RoleAssignment {
  id: string
  role: RoleReference
  scopeType: ScopeType
  scopeId: string
  createdAt: string
}

export interface User {
  id: string
  email: string
  displayName: string
  status: UserStatus
  securityVersion: string
  organizationMember: OrganizationMember
  roleAssignments: RoleAssignment[]
}

export interface Permission {
  id: string
  code: string
  name: string
}

export interface Role {
  id: string
  code: string
  name: string
  permissions: Permission[]
}

export interface Invitation {
  id: string
  email: string
  initialRoleCode: string
  status: string
  expiresAt: string
  createdAt: string
}

export interface MasterDataRecord {
  id: string
  code: string
  name: string
  status: MasterDataStatus
  createdAt: string
  updatedAt: string
}

export interface OrganizationMemberRecord {
  id: string
  organizationMemberId: string
  userId: string
  email: string
  displayName: string
  userStatus: UserStatus
  status: MasterDataStatus
  joinedAt: string
}

export interface ProviderAccount {
  id: string
  providerCode: string
  displayName: string
  externalAccountRef: string | null
  status: MasterDataStatus
  metadata: Record<string, unknown>
  createdAt: string
  updatedAt: string
}
