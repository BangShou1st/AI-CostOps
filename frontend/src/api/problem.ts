import axios from 'axios'

export interface ProblemDetail {
  type?: string
  title: string
  status: number
  detail: string | null
  instance?: string | null
  code: string
  traceId: string | null
  currentState?: string | null
}

const networkProblem: ProblemDetail = {
  title: '请求失败',
  status: 0,
  detail: '服务暂时无法连接，请稍后重试。',
  code: 'NETWORK_ERROR',
  traceId: null,
}

interface ProblemPresentation {
  title: string
  detail: string
}

const GENERIC_PROBLEM_PRESENTATION: ProblemPresentation = {
  title: '请求处理失败',
  detail: '请求未能完成，请稍后重试。',
}

const PROBLEM_PRESENTATION: Record<string, ProblemPresentation> = {
  REQUEST_MALFORMED: { title: '请求格式不正确', detail: '请求格式不正确，请检查后重试。' },
  VALIDATION_FAILED: { title: '输入信息无效', detail: '提交的信息未通过校验，请检查后重试。' },
  FORBIDDEN: { title: '访问被拒绝', detail: '您没有访问此资源的权限。如您认为这是误判，请联系管理员。' },
  AUTH_INVALID_CREDENTIALS: { title: '登录信息不正确', detail: '邮箱或密码不正确，请重试。' },
  AUTH_ACCESS_EXPIRED: { title: '登录已过期', detail: '登录凭证已过期，请重新登录。' },
  AUTH_SESSION_EXPIRED: { title: '会话已过期', detail: '会话已过期，请重新登录。' },
  AUTH_REFRESH_REPLAY: { title: '会话已失效', detail: '会话刷新凭证已失效，请重新登录。' },
  ACCOUNT_DISABLED: { title: '账户已停用', detail: '当前账户已停用，请联系管理员。' },
  AUTH_REFRESH_RACE: { title: '会话状态已更新', detail: '登录状态已在其他页面更新，请刷新后重试。' },
  AUTH_RATE_LIMITED: { title: '请求过于频繁', detail: '操作过于频繁，请稍后再试。' },
  REDIS_UNAVAILABLE_FOR_AUTH: { title: '登录服务暂不可用', detail: '登录服务暂时不可用，请稍后重试。' },
  STATE_CONFLICT: { title: '状态冲突', detail: '当前资源状态已变化，请刷新后重试。' },
  RESOURCE_NOT_FOUND: { title: '资源不存在', detail: '请求的资源不存在或已被移除。' },
  DEPENDENCY_TEMPORARILY_UNAVAILABLE: { title: '依赖服务暂不可用', detail: '相关服务暂时不可用，请稍后重试。' },
  EVIDENCE_TOO_LARGE: { title: '文件过大', detail: '上传文件超过大小限制，请选择较小的文件。' },
  MANUAL_ALLOCATION_DRAFT_EXISTS: { title: '已存在手工分摊草稿', detail: '当前费用已存在手工分摊草稿，请先处理现有草稿。' },
  ALLOCATION_ALREADY_CONFIRMED: { title: '分摊已确认', detail: '当前分摊已经确认，不能重复操作。' },
  ALLOCATION_SUM_MISMATCH: { title: '分摊金额不一致', detail: '分摊明细合计与费用金额不一致，请检查后重试。' },
  ALLOCATION_NOT_ELIGIBLE: { title: '不符合分摊条件', detail: '当前费用不满足分摊条件。' },
  DECISION_NOT_DRAFT: { title: '分摊决策不可编辑', detail: '只有草稿状态的分摊决策可以编辑。' },
  PERIOD_NOT_OPEN: { title: '账期未开放', detail: '当前账期未开放，暂不能执行此操作。' },
  BUDGET_INSUFFICIENT: { title: '预算额度不足', detail: '预算可用额度不足，无法完成此操作。' },
  NETWORK_ERROR: { title: '请求失败', detail: '服务暂时无法连接，请稍后重试。' },
}

export function toProblemDetail(error: unknown): ProblemDetail {
  if (axios.isAxiosError(error) && isProblemDetail(error.response?.data)) {
    return error.response.data
  }
  return networkProblem
}

/** Translate backend problem codes at the frontend presentation boundary. */
export function problemTitle(problem: ProblemDetail): string {
  return (PROBLEM_PRESENTATION[problem.code] ?? GENERIC_PROBLEM_PRESENTATION).title
}

export function problemDetail(problem: ProblemDetail): string | null {
  return (PROBLEM_PRESENTATION[problem.code] ?? GENERIC_PROBLEM_PRESENTATION).detail
}

export function problemSummary(problem: ProblemDetail): string {
  return `${problemTitle(problem)}（${problem.code}）`
}

function isProblemDetail(value: unknown): value is ProblemDetail {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const problem = value as Record<string, unknown>
  return typeof problem.title === 'string'
    && typeof problem.status === 'number'
    && typeof problem.code === 'string'
    && (typeof problem.traceId === 'string' || problem.traceId === null)
    && (typeof problem.detail === 'string' || problem.detail === null)
}
