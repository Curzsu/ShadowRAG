-- KEYS[1] = conversation:{conversationId}
-- ARGV[1] = keepCount (number of messages to keep from tail)
local key = KEYS[1]
local keepCount = tonumber(ARGV[1])

local current = redis.call('GET', key)
if not current then return 0 end

local messages = cjson.decode(current)
if #messages <= keepCount then return #messages end

-- Keep only the last keepCount messages
local trimmed = {}
for i = #messages - keepCount + 1, #messages do
    trimmed[#trimmed + 1] = messages[i]
end

local ttl = redis.call('TTL', key)
local encoded = cjson.encode(trimmed)
if ttl > 0 then
    redis.call('SET', key, encoded, 'EX', ttl)
else
    redis.call('SET', key, encoded)
end
return #trimmed
