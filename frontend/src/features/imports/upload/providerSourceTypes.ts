import type { ImportSourceType } from '../api/importTypes'

/**
 * Known M2 source-type UX per provider code. Only explicit mappings exist;
 * unknown provider codes are surfaced as unsupported instead of guessing.
 * Normalization is trim().toUpperCase() only — never heuristic.
 */
export const PROVIDER_SOURCE_TYPES: Record<string, readonly ImportSourceType[]> = {
  DEEPSEEK: ['FILE_EXPORT'],
  MIMO: ['FILE_EXPORT'],
  KIMI: ['FILE_EXPORT'],
  GLM: ['FILE_EXPORT'],
  OPENAI: ['FILE_EXPORT', 'USAGE_API_JSON', 'COSTS_API_JSON'],
}

export function providerSourceTypes(providerCode: string): readonly ImportSourceType[] | undefined {
  return PROVIDER_SOURCE_TYPES[providerCode.trim().toUpperCase()]
}
