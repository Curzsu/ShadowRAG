-- KEYS[1] = conversation:{conversationId}
-- KEYS[2] = conversation:{conversationId}:version
-- ARGV[1] = keepCount (number of messages to keep from tail)
local key = KEYS[1]
local versionKey = KEYS[2]
local keepCount = tonumber(ARGV[1])

local current = redis.call('GET', key)
if not current then return 0 end

local ok, messages = pcall(cjson.decode, current)
if not ok or type(messages) ~= "table" then return -1 end
if #messages <= keepCount then return #messages end

-- Keep only the last keepCount messages
local trimmed = {}
for i = #messages - keepCount + 1, #messages do
    trimmed[#trimmed + 1] = messages[i]
end

local ttl = redis.call('PTTL', key)
local encoded = cjson.encode(trimmed)
redis.call('SET', key, encoded, 'KEEPTTL')
local currentVersion = tonumber(redis.call('GET', versionKey) or '0')
if ttl > 0 then
    redis.call('SET', versionKey, currentVersion + 1, 'PX', ttl)
else
    redis.call('SET', versionKey, currentVersion + 1)
end
return #trimmed
