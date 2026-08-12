local key = KEYS[1]
local presented = ARGV[1]
local replacement = ARGV[2]
local now_ms = tonumber(ARGV[3])
local race_ms = tonumber(ARGV[4])

if redis.call('EXISTS', key) == 0 then
  return 'EXPIRED'
end

local absolute_expires_at = tonumber(redis.call('HGET', key, 'absolute_expires_at_ms') or '0')
if absolute_expires_at <= now_ms then
  redis.call('DEL', key)
  return 'EXPIRED'
end

local current = redis.call('HGET', key, 'current_token_hash') or ''
local previous = redis.call('HGET', key, 'previous_token_hash') or ''
local previous_valid_until = tonumber(redis.call('HGET', key, 'previous_valid_until_ms') or '0')

if current == presented then
  redis.call('HSET', key,
    'previous_token_hash', current,
    'previous_valid_until_ms', now_ms + race_ms,
    'current_token_hash', replacement,
    'last_rotated_at_ms', now_ms)
  return 'ROTATED'
end

if previous ~= '' and previous == presented and now_ms <= previous_valid_until then
  return 'RACE'
end

redis.call('DEL', key)
return 'REPLAY'
