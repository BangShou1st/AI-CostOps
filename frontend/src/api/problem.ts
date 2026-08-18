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
  title: 'Request failed',
  status: 0,
  detail: 'The service could not be reached.',
  code: 'NETWORK_ERROR',
  traceId: null,
}

export function toProblemDetail(error: unknown): ProblemDetail {
  if (axios.isAxiosError(error) && isProblemDetail(error.response?.data)) {
    return error.response.data
  }
  return networkProblem
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
