-- AI-CostOps Gateway operational daily quota (M12).
-- Redis is runtime coordination only: this script bounds request count per
-- governed credential id per UTC day. It is never an authority for
-- Budget/spend; monetary authorization stays MySQL-authoritative.
--
-- KEYS[1] = aicostops:v2:gateway:quota:{credentialId}:{yyyyMMddUTC}
-- ARGV[1] = limit (requests per UTC day)
-- ARGV[2] = ttl_seconds (seconds until next UTC midnight, bounded)
--
-- Returns {allowed, used}:
--   allowed == 1 -> request permitted within quota (used = new count)
--   allowed == 0 -> rejected; used = current count
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

local used = redis.call('INCR', key)
if used == 1 then
    redis.call('EXPIRE', key, ttl)
end

local allowed = 0
if used <= limit then
    allowed = 1
end

return {allowed, used}
