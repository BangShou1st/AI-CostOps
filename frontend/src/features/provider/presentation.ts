/**
 * Provider Hub 展示层标签映射。
 *
 * 原始枚举值仅用于表单值与 API 参数；界面一律渲染中文标签，
 * 未知值回退展示原始枚举，绝不改写 API 语义。
 */

/** 连接模板代码 → 中文标签。 */
export const CONNECTION_TEMPLATE_LABELS: Record<string, string> = {
  CUSTOM_OPENAI_COMPATIBLE: '自定义 OpenAI 兼容',
}

export function connectionTemplateLabel(code: string): string {
  return CONNECTION_TEMPLATE_LABELS[code] ?? code
}

/** 协议代码 → 中文标签。 */
export const PROTOCOL_LABELS: Record<string, string> = {
  OPENAI_CHAT_COMPLETIONS: 'OpenAI 聊天兼容',
  CHAT_COMPLETIONS: '聊天兼容',
  UNSUPPORTED: '不支持',
  UNKNOWN: '未知',
}

export function protocolLabel(code: string): string {
  return PROTOCOL_LABELS[code] ?? code
}

/** 网络策略 → 中文标签。 */
export const NETWORK_POLICY_LABELS: Record<string, string> = {
  DIRECT_ONLY: '仅直连',
  DIRECT_PUBLIC_ONLY: '仅公网直连',
}

export function networkPolicyLabel(policy: string): string {
  return NETWORK_POLICY_LABELS[policy] ?? policy
}

/** 认证方式 → 中文标签。 */
export const AUTH_TYPE_LABELS: Record<string, string> = {
  BEARER: 'Bearer Token',
  API_KEY_HEADER: 'API Key 请求头',
  NONE: '无认证',
}

export function authTypeLabel(authType: string): string {
  return AUTH_TYPE_LABELS[authType] ?? authType
}

/** 连接状态 → 中文标签。 */
export function connectionStatusLabel(status: string): string {
  const labels: Record<string, string> = { DRAFT: '草稿', ACTIVE: '已启用', RETIRED: '已退役' }
  return labels[status] ?? status
}

/** 凭证状态 → 中文标签。 */
export function credentialStatusLabel(status: string): string {
  const labels: Record<string, string> = { ACTIVE: '已启用', REVOKED: '已吊销' }
  return labels[status] ?? status
}

/** 凭证类型 → 中文标签。 */
export function credentialTypeLabel(credentialType: string): string {
  const labels: Record<string, string> = { BEARER_TOKEN: 'Bearer Token', API_KEY: 'API Key' }
  return labels[credentialType] ?? credentialType
}
