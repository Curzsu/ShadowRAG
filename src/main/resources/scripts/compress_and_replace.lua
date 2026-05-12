-- KEYS[1] = conversation:{conversationId}
-- ARGV[1] = compressStart (开始删除的位置，0-indexed)
-- ARGV[2] = splitIndex (结束删除的位置，0-indexed，exclusive)
-- ARGV[3] = summary message JSON string
local key = KEYS[1]
local compressStart = tonumber(ARGV[1])
local splitIndex = tonumber(ARGV[2])
local summaryJson = ARGV[3]

-- Validate summary JSON
local ok, summaryMsg = pcall(cjson.decode, summaryJson)
if not ok or type(summaryMsg) ~= "table" then return -1 end

local current = redis.call('GET', key)
if not current then return 0 end

local messages = cjson.decode(current)
if #messages < splitIndex then return 0 end
if compressStart < 0 or compressStart > splitIndex then return 0 end

-- Preserve existing TTL
local ttl = redis.call('TTL', key)

-- Build new message list:
-- [0..compressStart-1] (old summaries, preserved) — Lua is 1-indexed, so messages[1]..messages[compressStart]
-- + [new summary]
-- + [splitIndex..end] (tail, preserved) — messages[splitIndex+1]..messages[#messages]
local newMessages = {}
for i = 1, compressStart do
    newMessages[#newMessages + 1] = messages[i]
end
newMessages[#newMessages + 1] = summaryMsg
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
