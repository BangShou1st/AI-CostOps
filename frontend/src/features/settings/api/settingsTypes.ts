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
