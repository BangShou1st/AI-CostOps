import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'
import { problemDetail, problemSummary, problemTitle, toProblemDetail } from './problem'

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

  it('localizes known forbidden prose while preserving the technical code', () => {
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
  })
})
