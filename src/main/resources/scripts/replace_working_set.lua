-- Atomically rebuild a conversation working set from the durable source.
-- KEYS[1] = conversation:{conversationId}
-- KEYS[2] = conversation:{conversationId}:version
-- ARGV[1] = expected version read before the database snapshot
-- ARGV[2] = TTL seconds
-- ARGV[3] = complete working-set JSON array
local historyKey = KEYS[1]
local versionKey = KEYS[2]
local expectedVersion = tonumber(ARGV[1])
local ttlSeconds = tonumber(ARGV[2])

if expectedVersion == nil or ttlSeconds == nil or ttlSeconds <= 0 then return -1 end

local currentVersion = tonumber(redis.call('GET', versionKey) or '0')
if currentVersion ~= expectedVersion then return -2 end

local ok, history = pcall(cjson.decode, ARGV[3])
if not ok or type(history) ~= 'table' then return -1 end

local nextVersion = currentVersion + 1
redis.call('SET', historyKey, cjson.encode(history), 'EX', ttlSeconds)
redis.call('SET', versionKey, nextVersion, 'EX', ttlSeconds)
return nextVersion
