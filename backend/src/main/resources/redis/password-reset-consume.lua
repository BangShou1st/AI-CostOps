local key = KEYS[1]
if redis.call('EXISTS', key) == 0 then return nil end
if redis.call('HGET', key, 'token_hash') ~= ARGV[1] then return nil end
local user_id = redis.call('HGET', key, 'user_id')
redis.call('DEL', key)
return user_id
