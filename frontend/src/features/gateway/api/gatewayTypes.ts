// Wire DTOs of the governed Control Plane surface. Response ids arrive as
// strings (backend serializes ids through ApiId); only request payloads carry
// JSON numbers for numeric identity fields, matching the backend contract.
export interface ServiceIdentity {
  id: string
  code: string
  name: string
  status: 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
  createdAt: string
}

export interface GatewayCredential {
  id: string
  prefix: string
  principalType: 'SERVICE' | 'HUMAN_MEMBER'
  serviceIdentityId: string | null
  organizationMemberId: string | null
  projectId: string
  financialScopeType: 'PROJECT' | 'TEAM' | 'COST_CENTER'
  financialScopeId: string
  budgetEnforcementMode: 'REQUIRED' | 'OPTIONAL'
  status: 'ACTIVE' | 'REVOKED' | 'DISABLED'
  expiresAt: string | null
  createdAt: string
  revokedAt: string | null
}

export interface GatewayCredentialCreated {
  id: string
  prefix: string
  rawKey: string
  principalType: 'SERVICE' | 'HUMAN_MEMBER'
  projectId: string
  financialScopeType: 'PROJECT' | 'TEAM' | 'COST_CENTER'
  financialScopeId: string
  budgetEnforcementMode: 'REQUIRED' | 'OPTIONAL'
  status: 'ACTIVE' | 'REVOKED' | 'DISABLED'
  expiresAt: string | null
  createdAt: string
}

export interface GatewayCredentialCreateInput {
  principalType: 'SERVICE' | 'HUMAN_MEMBER'
  serviceIdentityId?: number
  organizationMemberId?: number
  projectId: number
  financialScopeType: 'PROJECT' | 'TEAM' | 'COST_CENTER'
  financialScopeId: number
  budgetEnforcementMode: 'REQUIRED' | 'OPTIONAL'
  expiresAt?: string | null
  modelIds: number[]
}

export interface ModelCatalogRecord {
  id: string
  modelKey: string
  name: string
  status: string
}

export interface ProviderModelRecord {
  id: string
  providerCode: string
  modelId: string
  providerModelName: string
  status: string
  routingEligible: boolean
}

export interface PricingRate {
  id: string
  dimensionCode: string
  unitQuantity: number
  unitPrice: number
}

export interface PricingVersion {
  id: string
  providerAccountId: string
  providerModelId: string
  version: number
  currency: string
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED'
  effectiveFrom: string
  effectiveTo: string | null
  createdAt: string
  activatedAt: string | null
  rates: PricingRate[]
}

export interface PricingVersionCreateInput {
  providerAccountId: number
  providerModelId: number
  currency: string
  effectiveFrom: string
  effectiveTo?: string | null
  rates: { dimensionCode: string; unitQuantity: number; unitPrice: string }[]
}