-- KEYS[1] = conversation:{conversationId}
-- KEYS[2] = conversation:{conversationId}:version
-- ARGV[1] = TTL seconds
-- ARGV[2..n] = message JSON strings with database-backed seq fields
local historyKey = KEYS[1]
local versionKey = KEYS[2]
local ttlSeconds = tonumber(ARGV[1])

if not ttlSeconds or ttlSeconds <= 0 then return -1 end

local messages = {}
local current = redis.call('GET', historyKey)
if current then
    local ok, decoded = pcall(cjson.decode, current)
    if not ok or type(decoded) ~= 'table' then return -1 end
    messages = decoded
end

local seenSeq = {}
for _, existing in ipairs(messages) do
    if existing['seq'] ~= nil then
        seenSeq[tostring(existing['seq'])] = true
    end
end

local function numericOrder(message)
    if message['seq'] ~= nil then
        return tonumber(message['seq'])
    end
    if message['type'] == 'summary' and message['sourceEndSeq'] ~= nil then
        return tonumber(message['sourceEndSeq'])
    end
    return nil
end

for i = 2, #ARGV do
    local ok, message = pcall(cjson.decode, ARGV[i])
    if not ok or type(message) ~= 'table' then return -1 end
    local seq = message['seq'] ~= nil and tostring(message['seq']) or nil
    if seq == nil or not seenSeq[seq] then
        local newOrder = numericOrder(message)
        local inserted = false
        if newOrder ~= nil then
            for position = 1, #messages do
                local existingOrder = numericOrder(messages[position])
                if existingOrder ~= nil and existingOrder > newOrder then
                    table.insert(messages, position, message)
                    inserted = true
                    break
                end
            end
        end
        if not inserted then
            messages[#messages + 1] = message
        end
        if seq ~= nil then seenSeq[seq] = true end
    end
end

redis.call('SET', historyKey, cjson.encode(messages), 'EX', ttlSeconds)
redis.call('INCR', versionKey)
redis.call('EXPIRE', versionKey, ttlSeconds)
return #messages
