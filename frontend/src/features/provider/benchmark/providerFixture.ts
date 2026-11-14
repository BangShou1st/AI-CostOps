import type { DiscoveryRow, ModelProbeCapabilities, PromotionResult, ProviderConnection, ProviderTemplate } from '../api/providerHubTypes'

/** DEV-only deterministic provider fixtures. Never imported by production routes. */
export const templateFixture: ProviderTemplate[] = [
  {
    code: 'CUSTOM_OPENAI', name: 'Custom OpenAI-compatible', connectionKind: 'CUSTOM', protocolCode: 'OPENAI_CHAT_COMPLETIONS',
    baseUrl: '', networkPolicy: 'DIRECT_PUBLIC_ONLY', authType: 'BEARER', lockedFields: [],
  },
]

export const connectionFixture: ProviderConnection[] = [
  {
    id: 12, providerAccountId: 6, version: 1, connectionKind: 'CUSTOM', templateCode: 'CUSTOM_OPENAI',
    protocolCode: 'OPENAI_CHAT_COMPLETIONS', baseUrl: 'https://llm.example.com', completionPath: '/v1/chat/completions',
    modelsPath: '/v1/models', authType: 'API_KEY_HEADER', networkPolicy: 'DIRECT_PUBLIC_ONLY', connectTimeoutMs: 5000,
    responseTimeoutMs: 120000, status: 'DRAFT', createdAt: '2026-09-09T00:00:00Z', activatedAt: null, retiredAt: null,
  },
]

export const discoveryFixture: DiscoveryRow[] = [
  {
    id: 31, providerModelName: 'provider-model-a', displayName: 'provider-model-a', source: 'LIVE_DISCOVERY',
    availability: 'AVAILABLE', protocolCode: 'CHAT_COMPLETIONS', pricingClassification: 'UNKNOWN',
    lastSeenAt: '2026-09-10T02:00:00Z', lastProbedAt: '2026-09-10T03:00:00Z', lastProbeStatus: 'PASS', lastProbeErrorCode: null,
  },
  {
    id: 32, providerModelName: 'legacy-embed', displayName: 'legacy-embed', source: 'MANUAL',
    availability: 'AVAILABLE', protocolCode: 'UNKNOWN', pricingClassification: 'VERIFIED_FREE',
    lastSeenAt: '2026-09-09T02:00:00Z', lastProbedAt: null, lastProbeStatus: null, lastProbeErrorCode: null,
  },
  {
    id: 33, providerModelName: 'dead-model', displayName: 'dead-model', source: 'LIVE_DISCOVERY',
    availability: 'UNAVAILABLE', protocolCode: 'UNSUPPORTED', pricingClassification: 'MISSING',
    lastSeenAt: '2026-09-08T02:00:00Z', lastProbedAt: '2026-09-10T03:00:00Z', lastProbeStatus: 'FAIL', lastProbeErrorCode: 'MODEL_NOT_FOUND',
  },
]

export const capabilitiesFixture: Record<number, ModelProbeCapabilities> = {
  31: { pass: true, capabilities: { CHAT_COMPLETIONS: 'VERIFIED', SSE_STREAMING: 'VERIFIED', USAGE: 'UNKNOWN' }, errorCode: null },
}

export const promotionFixture: PromotionResult = { logicalModelId: 11, providerModelId: 77, pricingReady: false, routingReady: false }
