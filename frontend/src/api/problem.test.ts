import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'
import { toProblemDetail } from './problem'

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
      title: 'Request failed',
      status: 0,
      detail: 'The service could not be reached.',
      code: 'NETWORK_ERROR',
      traceId: null,
    })
  })
})
