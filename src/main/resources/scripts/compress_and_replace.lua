-- KEYS[1] = conversation:{conversationId}
-- ARGV[1] = splitIndex (number of head messages to remove)
-- ARGV[2] = summary message JSON string
local key = KEYS[1]
local splitIndex = tonumber(ARGV[1])
local summaryJson = ARGV[2]

-- Validate summary JSON
local ok, summaryMsg = pcall(cjson.decode, summaryJson)
if not ok or type(summaryMsg) ~= "table" then return -1 end

local current = redis.call('GET', key)
if not current then return 0 end

local messages = cjson.decode(current)
if #messages < splitIndex then return 0 end

-- Preserve existing TTL
local ttl = redis.call('TTL', key)

-- Replace head with summary, keep tail intact
local newMessages = { summaryMsg }
for i = splitIndex + 1, #messages do
    newMessages[#newMessages + 1] = messages[i]
end

local encoded = cjson.encode(newMessages)
if ttl > 0 then
    redis.call('SET', key, encoded, 'EX', ttl)
else
    redis.call('SET', key, encoded)
end
return #newMessages
