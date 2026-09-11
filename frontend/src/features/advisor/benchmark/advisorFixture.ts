import type { AdvisorJob, AdvisorProfile } from '../api/advisorTypes'

/** DEV-only deterministic advisor fixtures. Never imported by production routes. */
export const advisorProfileFixture: AdvisorProfile = {
  id: 1, version: 3, providerModelId: 77, projectId: 7,
  financialScopeType: 'PROJECT', financialScopeId: 7,
  budgetEnforcementMode: 'REQUIRED', status: 'ACTIVE',
}

export const advisorCompletedFixture: AdvisorJob = {
  id: 101, subjectType: 'ANOMALY', subjectId: 7, status: 'COMPLETED',
  attemptCount: 2, gatewayRequestId: 9002, failureCode: null,
  attempts: [
    { attemptNo: 1, gatewayRequestId: 9001, status: 'FAILED', failureCode: 'PROVIDER_UNAVAILABLE' },
    { attemptNo: 2, gatewayRequestId: 9002, status: 'COMPLETED', failureCode: null },
  ],
  explanation: {
    summary: '9 月 6 日逻辑模型 gpt-5-class 的支出显著高于基线，主要由 Atlas 项目的推理量突增驱动。',
    driversExplanation: '驱动一：project:atlas 当日观测 $1,215.33，较基线 $905.20 偏离 robust-z 4.1。\n驱动二：team:core 贡献约六成增量。\n预测仍在预算 90% 高风险线之下，建议先复核路由候选再决定是否扩容。',
    attemptNo: 2,
  },
}

export const advisorRunningFixture: AdvisorJob = {
  id: 102, subjectType: 'FORECAST', subjectId: 3, status: 'RUNNING',
  attemptCount: 1, gatewayRequestId: 9011, failureCode: null,
  attempts: [{ attemptNo: 1, gatewayRequestId: 9011, status: 'RUNNING', failureCode: null }],
  explanation: null,
}

export const advisorFailedFixture: AdvisorJob = {
  id: 103, subjectType: 'SAVINGS', subjectId: 3, status: 'FAILED',
  attemptCount: 2, gatewayRequestId: null, failureCode: 'PROVIDER_UNAVAILABLE',
  attempts: [
    { attemptNo: 1, gatewayRequestId: 9021, status: 'FAILED', failureCode: 'PROVIDER_UNAVAILABLE' },
    { attemptNo: 2, gatewayRequestId: null, status: 'FAILED', failureCode: 'RATE_LIMITED' },
  ],
  explanation: null,
}
