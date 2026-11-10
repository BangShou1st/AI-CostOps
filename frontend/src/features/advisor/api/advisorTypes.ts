/** M18 frozen AI Advisor contract. Narrative-only; the job payload carries no money. */

export type AdvisorJobStatus = 'PENDING' | 'CLAIMED' | 'DISPATCHING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string

export interface AdvisorAttempt {
  attemptNo: number
  gatewayRequestId: number | null
  status: string
  failureCode: string | null
}

export interface AdvisorExplanation {
  summary: string
  driversExplanation: string
  attemptNo: number
}

export interface AdvisorJob {
  id: number
  subjectType: string
  subjectId: number
  status: AdvisorJobStatus
  attemptCount: number
  gatewayRequestId: number | null
  failureCode: string | null
  attempts: AdvisorAttempt[]
  explanation: AdvisorExplanation | null
}

export interface AdvisorProfile {
  id: number
  version: number
  providerModelId: number
  projectId: number
  financialScopeType: string
  financialScopeId: number
  budgetEnforcementMode: string
  status: string
}

export interface AdvisorProfileInput {
  providerModelId: number
  projectId: number
  financialScopeType: string
  financialScopeId: number
  budgetEnforcementMode: string
}

export const ADVISOR_SUBJECT_TYPES = ['ANOMALY', 'FORECAST', 'BUDGET_RISK', 'SAVINGS'] as const

/** 解释对象类型 → 中文标签；表单值与 API 参数仍使用原始枚举。 */
export const ADVISOR_SUBJECT_TYPE_LABELS: Record<string, string> = {
  ANOMALY: '异常',
  FORECAST: '预测',
  BUDGET_RISK: '预算风险',
  SAVINGS: '节省机会',
}

export const ADVISOR_JOB_ORDER: readonly string[] = ['PENDING', 'CLAIMED', 'DISPATCHING', 'RUNNING', 'COMPLETED']
