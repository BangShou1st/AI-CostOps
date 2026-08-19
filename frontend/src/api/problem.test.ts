import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'
import { problemDetail, problemSummary, problemTitle, toProblemDetail } from './problem'

const CURRENT_PROBLEM_CODES = [
  'REQUEST_MALFORMED',
  'VALIDATION_FAILED',
  'FORBIDDEN',
  'AUTH_INVALID_CREDENTIALS',
  'AUTH_ACCESS_EXPIRED',
  'AUTH_SESSION_EXPIRED',
  'AUTH_REFRESH_REPLAY',
  'ACCOUNT_DISABLED',
  'AUTH_REFRESH_RACE',
  'AUTH_RATE_LIMITED',
  'REDIS_UNAVAILABLE_FOR_AUTH',
  'STATE_CONFLICT',
  'RESOURCE_NOT_FOUND',
  'DEPENDENCY_TEMPORARILY_UNAVAILABLE',
  'EVIDENCE_TOO_LARGE',
  'MANUAL_ALLOCATION_DRAFT_EXISTS',
  'ALLOCATION_ALREADY_CONFIRMED',
  'ALLOCATION_SUM_MISMATCH',
  'ALLOCATION_NOT_ELIGIBLE',
  'DECISION_NOT_DRAFT',
  'PERIOD_NOT_OPEN',
  'BUDGET_INSUFFICIENT',
  'NETWORK_ERROR',
] as const

describe('toProblemDetail', () => {
  it('preserves a complete server ProblemDetail', () => {
    const config = { headers: {} } as InternalAxiosRequestConfig
    const response: AxiosResponse = {
      config,
      data: {
        type: 'https://aicostops.dev/problems/state-conflict',
        title: 'State conflict',
        status: 409,
        detail: 'The state changed.',
        instance: '/api/v1/resources/1',
        code: 'STATE_CONFLICT',
        traceId: 'trace-1',
        currentState: 'CLOSED',
      },
      headers: {},
      status: 409,
      statusText: 'Conflict',
    }
    const error = new AxiosError('conflict', 'ERR_BAD_RESPONSE', config, undefined, response)

    expect(toProblemDetail(error)).toEqual(response.data)
  })

  it('preserves a server ProblemDetail whose traceId is null', () => {
    const config = { headers: {} } as InternalAxiosRequestConfig
    const response: AxiosResponse = {
      config,
      data: {
        title: 'Manual allocation draft exists',
        status: 409,
        detail: 'A manual allocation draft already exists for this expense.',
        code: 'MANUAL_ALLOCATION_DRAFT_EXISTS',
        traceId: null,
      },
      headers: {},
      status: 409,
      statusText: 'Conflict',
    }
    const error = new AxiosError('conflict', 'ERR_BAD_RESPONSE', config, undefined, response)

    expect(toProblemDetail(error)).toEqual(response.data)
  })

  it('returns a stable fallback for network and unknown errors', () => {
    expect(toProblemDetail(new Error('socket closed'))).toEqual({
      title: '请求失败',
      status: 0,
      detail: '服务暂时无法连接，请稍后重试。',
      code: 'NETWORK_ERROR',
      traceId: null,
    })
  })

  it.each(CURRENT_PROBLEM_CODES)('localizes %s with Chinese presentation prose', (code) => {
    const problem = {
      title: `Backend English title for ${code}`,
      status: 400,
      detail: `Backend English detail for ${code}`,
      code,
      traceId: 'trace-code',
    }

    expect(problemTitle(problem)).not.toMatch(/[A-Za-z]/)
    expect(problemDetail(problem)).not.toBe(problem.detail)
    expect(problemDetail(problem)).not.toMatch(/[A-Za-z]/)
    expect(problemSummary(problem)).toContain(`（${code}）`)
  })

  it('keeps the established FORBIDDEN and NETWORK_ERROR presentation', () => {
    const forbidden = {
      title: 'Forbidden',
      status: 403,
      detail: 'Access to this resource is forbidden.',
      code: 'FORBIDDEN',
      traceId: null,
    }
    expect(problemTitle(forbidden)).toBe('访问被拒绝')
    expect(problemDetail(forbidden)).toContain('没有访问此资源的权限')
    expect(problemSummary(forbidden)).toBe('访问被拒绝（FORBIDDEN）')

    const network = {
      title: 'Request failed',
      status: 0,
      detail: 'The service could not be reached.',
      code: 'NETWORK_ERROR',
      traceId: null,
    }
    expect(problemTitle(network)).toBe('请求失败')
    expect(problemDetail(network)).toBe('服务暂时无法连接，请稍后重试。')
    expect(problemSummary(network)).toBe('请求失败（NETWORK_ERROR）')
  })

  it('uses a Chinese generic fallback for unknown future codes', () => {
    const futureProblem = {
      title: 'Future backend failure',
      status: 499,
      detail: 'A future backend detail must not leak.',
      code: 'FUTURE_CODE',
      traceId: 'trace-future',
      currentState: 'UNKNOWN',
    }

    expect(problemTitle(futureProblem)).toBe('请求处理失败')
    expect(problemDetail(futureProblem)).toBe('请求未能完成，请稍后重试。')
    expect(problemSummary(futureProblem)).toBe('请求处理失败（FUTURE_CODE）')
  })
})
