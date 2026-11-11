/**
 * Fixed-concurrency closed-loop load generator. Each virtual user performs
 * one request at a time, so `concurrency` is real in-flight pressure and
 * throughput is observed (business completions / measurement window), never
 * assumed from the configured client count.
 */
import http from 'node:http'
import { responseSnippet, stats } from './lib.mjs'

function parseErrorCode(body) {
  if (!body) return null
  try {
    const parsed = JSON.parse(body)
    return parsed?.error?.code ?? parsed?.error?.type ?? null
  } catch {
    return null
  }
}

function requestOnce({ port, rawKey, idempotencyKey, stream, maxCompletionTokens, timeoutMs, agent, cancelAfterFirstChunk }) {
  return new Promise(resolve => {
    const started = process.hrtime.bigint()
    const payload = JSON.stringify({
      model: 'hc01-chat',
      messages: [{ role: 'user', content: 'hc01 load probe' }],
      max_completion_tokens: maxCompletionTokens,
      stream,
    })
    let settled = false
    let canceled = false
    const finish = result => {
      if (settled) return
      settled = true
      resolve({
        ...result,
        ms: Number(process.hrtime.bigint() - started) / 1e6,
        canceled,
      })
    }
    const req = http.request({
      host: '127.0.0.1',
      port,
      path: '/v1/chat/completions',
      method: 'POST',
      agent,
      headers: {
        Authorization: `Bearer ${rawKey}`,
        'Idempotency-Key': idempotencyKey,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
        Accept: stream ? 'text/event-stream' : 'application/json',
      },
    }, response => {
      let body = ''
      response.setEncoding('utf8')
      response.on('data', chunk => {
        body += chunk
        if (cancelAfterFirstChunk && !canceled) {
          canceled = true
          req.destroy()
          finish({ status: 499, body, error: 'client-cancel', streamDone: false })
        }
      })
      response.on('end', () => {
        finish({
          status: response.statusCode,
          error: null,
          errorCode: parseErrorCode(body),
          streamDone: !stream || body.includes('[DONE]'),
          body,
        })
      })
      response.on('error', error => {
        if (canceled) return
        finish({ status: -1, error: error.code || error.message, errorCode: null, body })
      })
    })
    req.on('error', error => {
      if (canceled) return
      finish({ status: -1, error: error.code || error.message, errorCode: null, body: '' })
    })
    req.setTimeout(timeoutMs, () => {
      req.destroy(new Error('client-timeout'))
    })
    // A late keep-alive/socket close after a completed or canceled response is
    // not a request failure and must not become an unhandled 'error' event.
    // Keep-alive sockets are shared, so install the guard once per socket.
    req.on('socket', socket => {
      if (!socket.__hc01ErrorGuard) {
        socket.__hc01ErrorGuard = true
        socket.on('error', () => {})
      }
    })
    req.end(payload)
  })
}

/**
 * @returns {Promise<{samples: Array, latencyMs: object, statusCounts: object,
 *   errorCodeCounts: object, successRps: number, totalRps: number,
 *   perSecond: Array, window: object, failures: Array, concurrency: number}>}
 */
/**
 * Fires exactly `count` requests with `concurrency` workers. Used for
 * correctness scenarios (idempotency storm, disconnect, cancellation) where
 * an unbounded closed loop would blur the expected provider-operation count.
 */
export async function runOneShot(options) {
  const {
    ports,
    rawKeys,
    count,
    concurrency = Math.min(count, 100),
    tag,
    stream = false,
    cancelAfterFirstChunk = false,
    maxCompletionTokens = 16,
    timeoutMs = 90_000,
    sameIdempotencyKey = null,
  } = options
  const agent = new http.Agent({ keepAlive: true, maxSockets: Math.max(concurrency, 4) })
  const startedAt = Date.now()
  const results = new Array(count)
  let next = 0
  const workers = Array.from({ length: Math.min(concurrency, count) }, (_, vu) => (async () => {
    while (true) {
      const index = next++
      if (index >= count) return
      const port = ports[(vu + index) % ports.length]
      const rawKey = rawKeys[(vu + index) % rawKeys.length]
      const idempotencyKey = sameIdempotencyKey ?? `hc01-${tag}-oneshot-${vu}-${index}`
      results[index] = await requestOnce({
        port, rawKey, idempotencyKey, stream, maxCompletionTokens, timeoutMs, agent, cancelAfterFirstChunk,
      })
    }
  })())
  await Promise.all(workers)
  agent.destroy()
  const endedAt = Date.now()
  const windowSec = Math.max(0.001, (endedAt - startedAt) / 1000)
  const statusCounts = {}
  const errorCodeCounts = {}
  const latencies = []
  const failures = []
  let successes = 0
  for (const result of results) {
    statusCounts[String(result.status)] = (statusCounts[String(result.status)] ?? 0) + 1
    if (result.errorCode) errorCodeCounts[result.errorCode] = (errorCodeCounts[result.errorCode] ?? 0) + 1
    if (result.status === 200 && !result.canceled && result.streamDone) successes++
    latencies.push(result.ms)
    if (result.status !== 200 && failures.length < 40) {
      failures.push({
        status: result.status,
        error: result.error,
        errorCode: result.errorCode,
        body: responseSnippet(result.body ?? ''),
      })
    }
  }
  return {
    concurrency: Math.min(concurrency, count),
    count,
    window: { startedAt: new Date(startedAt).toISOString(), endedAt: new Date(endedAt).toISOString(), durationSec: windowSec },
    sampleCount: results.length,
    successCount: successes,
    successRps: Math.round((successes / windowSec) * 100) / 100,
    totalRps: Math.round((results.length / windowSec) * 100) / 100,
    statusCounts,
    errorCodeCounts,
    latencyMs: stats(latencies),
    perSecond: [],
    failures,
    results: results.map(result => ({
      status: result.status,
      ms: result.ms,
      error: result.error,
      errorCode: result.errorCode,
      canceled: result.canceled,
      streamDone: result.streamDone,
      body: responseSnippet(result.body ?? '', 200),
    })),
  }
}

export async function runLoad(options) {
  const {
    ports,
    rawKeys,
    concurrency,
    durationSec,
    tag,
    stream = false,
    cancelAfterFirstChunk = false,
    maxCompletionTokens = 16,
    timeoutMs = 90_000,
    collect = true,
    sameIdempotencyKey = null,
    runNonce = Date.now().toString(36),
  } = options
  const agent = new http.Agent({ keepAlive: true, maxSockets: Math.max(concurrency, 4) })
  const startedAt = Date.now()
  const deadline = startedAt + durationSec * 1000
  const samples = []
  const failures = []

  const workers = Array.from({ length: concurrency }, (_, vu) => (async () => {
    let sequence = 0
    while (Date.now() < deadline) {
      const port = ports[(vu + sequence) % ports.length]
      const rawKey = rawKeys[(vu + sequence) % rawKeys.length]
      const idempotencyKey = sameIdempotencyKey
        ?? `hc01-${tag}-${runNonce}-${vu}-${sequence}`
      const result = await requestOnce({
        port, rawKey, idempotencyKey, stream, maxCompletionTokens, timeoutMs, agent, cancelAfterFirstChunk,
      })
      sequence++
      if (!collect) continue
      const sample = {
        at: Date.now(),
        status: result.status,
        error: result.error,
        errorCode: result.errorCode ?? null,
        streamDone: result.streamDone,
        ms: result.ms,
        canceled: result.canceled,
      }
      samples.push(sample)
      if (result.status !== 200 && failures.length < 40) {
        failures.push({ ...sample, body: responseSnippet(result.body ?? '') })
      }
    }
  })())

  await Promise.all(workers)
  agent.destroy()
  const endedAt = Date.now()
  const windowSec = Math.max(0.001, (endedAt - startedAt) / 1000)

  const statusCounts = {}
  const errorCodeCounts = {}
  const perSecond = new Map()
  const latencies = []
  let successes = 0
  for (const sample of samples) {
    const status = String(sample.status)
    statusCounts[status] = (statusCounts[status] ?? 0) + 1
    if (sample.errorCode) errorCodeCounts[sample.errorCode] = (errorCodeCounts[sample.errorCode] ?? 0) + 1
    if (sample.status === 200 && !sample.canceled && sample.streamDone) {
      successes++
      latencies.push(sample.ms)
    } else if (sample.status === 200) {
      latencies.push(sample.ms)
    }
    const second = Math.floor((sample.at - startedAt) / 1000)
    const bucket = perSecond.get(second) ?? { second, success: 0, total: 0 }
    bucket.total++
    if (sample.status === 200 && !sample.canceled && sample.streamDone) bucket.success++
    perSecond.set(second, bucket)
  }

  return {
    concurrency,
    window: { startedAt: new Date(startedAt).toISOString(), endedAt: new Date(endedAt).toISOString(), durationSec: windowSec },
    sampleCount: samples.length,
    successCount: successes,
    successRps: Math.round((successes / windowSec) * 100) / 100,
    totalRps: Math.round((samples.length / windowSec) * 100) / 100,
    statusCounts,
    errorCodeCounts,
    latencyMs: stats(latencies),
    perSecond: [...perSecond.values()].sort((a, b) => a.second - b.second),
    failures,
  }
}
