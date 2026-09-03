-- AI-CostOps Gateway atomic token bucket rate limit (AIC-087/AIC-099).
-- Redis is runtime coordination only: this script bounds request rate per
-- governed credential id. It is never an authority for Budget/spend.
--
-- KEYS[1] = aicostops:v2:gateway:ratelimit:{credentialId}
-- ARGV[1] = capacity
-- ARGV[2] = refill_per_second
-- ARGV[3] = now_millis
-- ARGV[4] = cost (1 request unit)
--
-- Returns {allowed, remaining, retry_after_millis}:
--   allowed == 1 -> request permitted (retry_after 0)
--   allowed == 0 -> rejected; remaining is the current token count and
--                   retry_after_millis is the bounded wait before the next
--                   token is expected to be available.
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_second = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

local rate = refill_per_second / 1000.0
local tokens_key = key .. ":tokens"
local ts_key = key .. ":ts"

local tokens
local last
local current = redis.call('GET', tokens_key)
local previous = redis.call('GET', ts_key)
if current == false or previous == false then
    tokens = capacity
    last = now
else
    tokens = tonumber(current)
    last = tonumber(previous)
    if now > last then
        tokens = math.min(capacity, tokens + (now - last) * rate)
    end
end

local allowed = 0
local retry_after_ms = 0
if tokens >= cost then
    tokens = tokens - cost
    allowed = 1
else
    retry_after_ms = math.ceil((cost - tokens) / rate)
end

-- Keep the bucket state around (bounded TTL); treating a fully idle credential
-- as a fresh full bucket is safe for a runtime-only limiter.
redis.call('SETEX', tokens_key, 60, tokens)
redis.call('SETEX', ts_key, 60, now)

return {allowed, math.floor(tokens), retry_after_ms}