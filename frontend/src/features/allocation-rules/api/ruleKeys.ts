import type { RuleListParams } from './rulesApi'

export const ruleKeys = {
  lists: () => ['rules', 'list'] as const,
  list: (params: RuleListParams) => ['rules', 'list', params] as const,
  detail: (ruleId: string) => ['rules', 'detail', ruleId] as const,
}
