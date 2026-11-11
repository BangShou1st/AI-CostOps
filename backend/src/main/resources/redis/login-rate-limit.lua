local key = KEYS[1]
local remaining_ms = tonumber(ARGV[1])

local count = redis.call('INCR', key)
-- PTTL < 0 means the key is new, or an interrupted INCR without EXPIRE left
-- it without a TTL. Heal both in the same atomic script; never extend a TTL
-- that is already counting down.
if redis.call('PTTL', key) < 0 then
  redis.call('PEXPIRE', key, remaining_ms)
end
return count
