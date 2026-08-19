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

export function toProblemDetail(error: unknown): ProblemDetail {
  if (axios.isAxiosError(error) && isProblemDetail(error.response?.data)) {
    return error.response.data
  }
  return networkProblem
}

/** Translate known backend prose at the frontend presentation boundary. */
export function problemTitle(problem: ProblemDetail): string {
  if (problem.code === 'FORBIDDEN' || problem.status === 403) return '访问被拒绝'
  if (problem.code === 'NETWORK_ERROR') return '请求失败'
  return problem.title
}

export function problemDetail(problem: ProblemDetail): string | null {
  if (problem.code === 'FORBIDDEN' || problem.status === 403) {
    return '您没有访问此资源的权限。如您认为这是误判，请联系管理员。'
  }
  if (problem.code === 'NETWORK_ERROR') return '服务暂时无法连接，请稍后重试。'
  return problem.detail
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
