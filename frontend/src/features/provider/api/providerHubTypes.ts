/** M18 frozen Provider Hub contract. Secrets never appear in any projection. */

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ProviderTemplate {
  code: string
  name: string
  connectionKind: string
  protocolCode: string
  baseUrl: string
  networkPolicy: string
  authType: string
  lockedFields: string[]
}

export type ConnectionStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED' | string

export interface ProviderConnection {
  id: number
  providerAccountId: number
  version: number
  connectionKind: string
  templateCode: string
  protocolCode: string
  baseUrl: string
  completionPath: string
  modelsPath: string
  authType: string
  networkPolicy: string
  connectTimeoutMs: number
  responseTimeoutMs: number
  status: ConnectionStatus
  createdAt: string
  activatedAt: string | null
  retiredAt: string | null
}

export interface CreateConnectionInput {
  providerAccountId?: number
  templateCode?: string
  baseUrl?: string
  completionPath?: string
  modelsPath?: string
  authType?: string
  authHeaderName?: string
  userAgent?: string
  connectTimeoutMs?: number
  responseTimeoutMs?: number
}

export interface ProbeResult {
  status: string
  errorCode: string | null
  checkedAt: string
}

export interface ModelProbeCapabilities {
  pass: boolean
  capabilities: Record<string, string>
  errorCode: string | null
}

export interface DiscoveryRow {
  id: number
  providerModelName: string
  displayName: string
  source: string
  availability: string
  protocolCode: string
  pricingClassification: string
  lastSeenAt: string
  lastProbedAt: string | null
  lastProbeStatus: string | null
  lastProbeErrorCode: string | null
}

export interface PromotionResult {
  logicalModelId: number
  providerModelId: number
  pricingReady: boolean
  routingReady: boolean
}

export interface CredentialRow {
  id: number
  credentialType: string
  safeLabel: string
  status: string
  createdAt: string
  rotatedAt: string | null
  revokedAt: string | null
}
