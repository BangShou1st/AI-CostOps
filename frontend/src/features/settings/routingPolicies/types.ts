import type { PageResponse } from '../../../api/pagination'

export type RoutingPolicyStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED'
export type RoutingCandidateStatus = 'ACTIVE' | 'DISABLED'

export interface RoutingPolicyCandidate {
  id: string
  providerAccountId: string
  providerModelId: string
  priority: number
  status: RoutingCandidateStatus
  privacyRegionCode: string | null
}

export interface RoutingPolicy {
  id: string
  organizationId: string
  projectId: string | null
  modelId: string
  version: number
  status: RoutingPolicyStatus
  candidates: RoutingPolicyCandidate[]
}

export interface RoutingCandidateInput {
  providerAccountId: string
  providerModelId: string
  priority: number
  status: RoutingCandidateStatus
  privacyRegionCode?: string | null
}

export interface RoutingPolicyInput {
  projectId?: string | null
  modelId: string
  candidates: RoutingCandidateInput[]
}

export interface RoutingPolicyUpdateInput {
  candidates: RoutingCandidateInput[]
}

export interface RoutingOption {
  providerAccountId: string
  displayName: string
  providerCode: string
  providerModelId: string
  providerModelName: string
  routingEligible: boolean
  credentialReady: boolean
  pricingReady: boolean
  currencies: string[]
}

export type RoutingPolicyPage = PageResponse<RoutingPolicy>
