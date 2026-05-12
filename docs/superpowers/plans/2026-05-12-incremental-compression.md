# 增量压缩 (Incremental Compression) 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有的"全量压缩"策略改为"增量压缩"——每次压缩只对上次摘要之后的新消息生成摘要，避免"摘要的摘要"导致的信息衰减。

**Architecture:** 核心改动集中在 `ConversationCompressionService.doCompress()` 和 `compress_and_replace.lua`。现有流程是：找到所有 head 消息 → 全部交给 LLM → 替换为一条摘要。增量压缩改为：找到已有的 `[历史摘要]` 标记 → 只对标记之后的新消息生成摘要 → 保留旧摘要不动 → 插入新摘要。Lua 脚本同步更新，支持多摘要拼接。

**Tech Stack:** Java 17, Spring Boot 3.4, Redis + Lua, Jackson, jtokkit, DeepSeek API

---

## 现状分析 vs 改动范围

### 当前行为（全量压缩）

```
[消息1] [消息2] [消息3] ... [消息30] | [消息31] [消息32] ... [消息42]
|<---------- head (30条) --------->| |<-------- tail (12条) ------>|
                ↓ LLM 摘要一次
              [摘要]                | [消息31] [消息32] ... [消息42]

# 第二次压缩（假设又积累了 30 条）
              [摘要] [消息31] ... [消息60] | [消息61] ... [消息72]
              |<------- head --------->|
                     ↓ LLM 摘要（包含上一次的摘要！）
                   [摘要']             | [消息61] ... [消息72]
                   # 摘要' 是对摘要的摘要 → 信息衰减
```

### 目标行为（增量压缩）

```
# 第一次压缩
[消息1] ... [消息30] | [消息31] ... [消息42]
       ↓ LLM 摘要
[摘要A]              | [消息31] ... [消息42]

# 第二次压缩（增量！）
[摘要A] [消息31] ... [消息60] | [消息61] ... [消息72]
        |<-- 只对这部分摘要 -->|
[摘要A] [摘要B]        | [消息61] ... [消息72]
# 摘要A 不变，只对消息31~60 生成摘要B → 无信息衰减
```

### 改动难度评估

| 维度 | 评分 | 说明 |
|------|------|------|
| 代码改动量 | **小** | ~40行 Java + ~20行 Lua，3个文件 |
| 逻辑复杂度 | **中低** | 核心是多一个"找摘要边界"的逻辑 |
| 风险 | **低** | 改动范围小，且不改变调用链 |
| 测试难度 | **低** | 无需额外 mock，逻辑可单元测试 |
| 总体耗时 | **半天** | 编码1h + 测试1h + 调试1h |

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java` | **修改** | 核心：doCompress() 改为增量逻辑 |
| `src/main/resources/scripts/compress_and_replace.lua` | **修改** | 支持多摘要（保留旧摘要 + 插入新摘要） |
| `src/main/java/com/yizhaoqi/smartpai/config/CompressionProperties.java` | **修改** | 新增 `summaryMarker` 配置项（可选） |

不需要改动的文件：
- `ChatHandler.java` — 调用链不变，仍调 `checkAndCompress()`
- `sync_truncate.lua` — 硬阈值截断逻辑不变
- `CompressionConfig.java` — Bean 配置不变
- `ConversationService.java` — syncToMySQL 不变

---

## Task 1: 修改 CompressionProperties（可选配置项）

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/CompressionProperties.java`
- Modify: `src/main/resources/application.yml`

**说明：** 这一步是可选的。当前摘要标记硬编码为 `"[历史摘要]"` 前缀。提取为配置项更规范，但直接硬编码也完全可以。这里按提取为配置项来做。

- [ ] **Step 1: 在 CompressionProperties 中新增 summaryMarker 字段**

```java
// CompressionProperties.java 新增字段
private String summaryMarker = "[历史摘要]";
```

- [ ] **Step 2: 在 ConversationCompressionService 中新增常量引用**

在 `ConversationCompressionService.java` 中，将硬编码的 `"[历史摘要]"` 改为引用 `config.getSummaryMarker()`：

```java
// doCompress() 中构建 summaryJson 时：
"content", config.getSummaryMarker() + " " + summary,
```

- [ ] **Step 3: application.yml 无需改动**

因为 CompressionProperties 已有默认值 `"[历史摘要]"`，yml 中不需要加新配置项（除非用户想自定义标记）。

- [ ] **Step 4: 验证编译通过**

Run: `mvn compile -f pom.xml -q`
Expected: BUILD SUCCESS

---

## Task 2: 修改 doCompress() 为增量压缩逻辑（核心）

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java:143-194`

**说明：** 这是整个改动的核心。当前 `doCompress()` 把 head 全部送给 LLM 生成一条摘要。改为：在 head 中找到已有的摘要消息作为边界，只对边界之后的新消息生成摘要，保留旧摘要。

### 当前代码逻辑（需要理解）

```java
// doCompress() 当前的关键逻辑：
int keepCount = config.getKeepRounds() * 2;   // 12
int splitIndex = currentHistory.size() - keepCount; // head vs tail 分界线

List<Map<String, String>> head = currentHistory.subList(0, splitIndex);
// head 全部送给 LLM → 生成一条摘要
// Lua 脚本：替换 messages[0..splitIndex-1] 为一条摘要
```

### 改为增量压缩后的逻辑

```java
// 1. 找到 head 中所有已有摘要的位置
// 2. 最后一个摘要之后到 splitIndex 的消息 = "待压缩的新消息"
// 3. 旧摘要们 + 对新消息的摘要 = 最终要保留的摘要部分
// 4. Lua 脚本：删除 messages[lastSummaryIndex+1 .. splitIndex-1]，插入新摘要
//    保留 messages[0 .. lastSummaryIndex]（旧摘要们不动）
```

- [ ] **Step 1: 在 doCompress() 中添加找摘要边界的逻辑**

在 `doCompress()` 中，`int splitIndex = ...` 之后、`List<Map<String, String>> head = ...` 之前，插入边界查找逻辑：

```java
// === 增量压缩：找到已有摘要的边界 ===
String marker = config.getSummaryMarker();
int lastSummaryIndex = -1;
for (int i = 0; i < splitIndex; i++) {
    String content = currentHistory.get(i).getOrDefault("content", "");
    if (content.startsWith(marker)) {
        lastSummaryIndex = i;
    }
}

// compressStart = 最后一个摘要之后的第一条新消息
int compressStart = (lastSummaryIndex == -1) ? 0 : lastSummaryIndex + 1;

// 只有 compressStart 到 splitIndex 之间的消息需要压缩
List<Map<String, String>> toCompress = currentHistory.subList(compressStart, splitIndex);
if (toCompress.isEmpty()) {
    logger.debug("No new messages to compress for conversationId={}", conversationId);
    return;
}
```

- [ ] **Step 2: 修改构建 LLM 输入的逻辑**

将原来的：
```java
List<Map<String, String>> head = currentHistory.subList(0, splitIndex);
// ... 遍历 head 构建字符串
```

改为（`toCompress` 和空检查已在 Step 1 完成，这里只改 StringBuilder 构建）：

```java
// 构建 LLM 输入（toCompress 已在 Step 1 中创建）
StringBuilder sb = new StringBuilder();
if (lastSummaryIndex >= 0) {
    String previousSummary = currentHistory.get(lastSummaryIndex).getOrDefault("content", "");
    String summaryText = previousSummary.substring(marker.length()).trim();
    // 旧摘要仅作为上下文帮助 LLM 理解连贯性，不要求合并
    sb.append("[以下是之前对话的摘要，仅供理解上下文，不需要合并]\n")
      .append(summaryText).append("\n\n");
    sb.append("[以下是新的对话内容，请只对这部分生成独立的摘要]\n");
}
for (Map<String, String> msg : toCompress) {
    sb.append(msg.getOrDefault("role", "unknown")).append(": ")
      .append(msg.getOrDefault("content", "")).append("\n\n");
}
```

- [ ] **Step 3: 修改 Lua 脚本调用参数**

将 `splitIndex` 和摘要 JSON 传给 Lua 的方式不变，但需要新增 `compressStart` 参数，让 Lua 知道从哪里开始删除、哪里插入新摘要。

修改 `stringRedisTemplate.execute()` 调用：

```java
Long result = stringRedisTemplate.execute(
        compressScript,
        List.of(key),
        String.valueOf(compressStart),   // ARGV[1]: 删除起始位置
        String.valueOf(splitIndex),       // ARGV[2]: 删除结束位置
        summaryJson                       // ARGV[3]: 新摘要 JSON
);
```

- [ ] **Step 4: 完整的修改后 doCompress() 方法**

最终方法应该长这样（替换原 doCompress 的全部内容）：

```java
private void doCompress(String conversationId) throws Exception {
    String key = "conversation:" + conversationId;
    String json = stringRedisTemplate.opsForValue().get(key);
    if (json == null) return;

    List<Map<String, String>> currentHistory = objectMapper.readValue(
            json, new TypeReference<List<Map<String, String>>>() {});

    int keepCount = config.getKeepRounds() * 2;
    int splitIndex = currentHistory.size() - keepCount;
    if (splitIndex <= 0) {
        logger.debug("Compression no longer needed for conversationId={}, size={}",
                conversationId, currentHistory.size());
        return;
    }

    // === 增量压缩：找已有摘要边界 ===
    String marker = config.getSummaryMarker();
    int lastSummaryIndex = -1;
    for (int i = 0; i < splitIndex; i++) {
        String content = currentHistory.get(i).getOrDefault("content", "");
        if (content.startsWith(marker)) {
            lastSummaryIndex = i;
        }
    }

    int compressStart = (lastSummaryIndex == -1) ? 0 : lastSummaryIndex + 1;

    // 新消息范围 [compressStart, splitIndex)
    List<Map<String, String>> toCompress = currentHistory.subList(compressStart, splitIndex);
    if (toCompress.isEmpty()) {
        logger.debug("No new messages to compress for conversationId={}", conversationId);
        return;
    }

    // 构建 LLM 输入（只对 toCompress 生成独立摘要，不合并旧摘要）
    StringBuilder sb = new StringBuilder();
    if (lastSummaryIndex >= 0) {
        String previousSummary = currentHistory.get(lastSummaryIndex).getOrDefault("content", "");
        String summaryText = previousSummary.substring(marker.length()).trim();
        // 旧摘要仅作为上下文帮助 LLM 理解连贯性，不要求合并
        sb.append("[以下是之前对话的摘要，仅供理解上下文，不需要合并]\n")
          .append(summaryText).append("\n\n");
        sb.append("[以下是新的对话内容，请只对这部分生成独立的摘要]\n");
    }
    for (Map<String, String> msg : toCompress) {
        sb.append(msg.getOrDefault("role", "unknown")).append(": ")
          .append(msg.getOrDefault("content", "")).append("\n\n");
    }

    String summary = callLlmForSummary(sb.toString());
    if (summary == null || summary.isBlank()) {
        logger.warn("LLM returned empty summary for conversationId={}", conversationId);
        return;
    }

    String summaryJson = objectMapper.writeValueAsString(Map.of(
            "role", "system",
            "content", config.getSummaryMarker() + " " + summary,
            "timestamp", LocalDateTime.now().format(TS_FORMAT)
    ));

    // 调用 Lua 脚本：删除 [compressStart, splitIndex)，插入新摘要
    Long result = stringRedisTemplate.execute(
            compressScript,
            List.of(key),
            String.valueOf(compressStart),   // ARGV[1]
            String.valueOf(splitIndex),       // ARGV[2]
            summaryJson                       // ARGV[3]
    );

    if (result != null && result == -1) {
        logger.error("Lua script rejected summary JSON (code=-1): conversationId={}", conversationId);
        return;
    }

    logger.info("Incremental compression completed: conversationId={}, " +
                "compressRange=[{},{}), before={} messages, after={} messages",
            conversationId, compressStart, splitIndex, currentHistory.size(), result);
}
```

- [ ] **Step 5: 验证编译通过**

Run: `mvn compile -f pom.xml -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java
git commit -m "feat: 增量压缩 - doCompress() 只对新消息生成摘要"
```

---

## Task 3: 修改 Lua 脚本支持增量模式

**Files:**
- Modify: `src/main/resources/scripts/compress_and_replace.lua`

**说明：** 当前 Lua 脚本接收 `splitIndex` 和 `summaryJson`，把 `messages[0..splitIndex-1]` 全部替换为一条摘要。增量模式下需要改为：接收 `compressStart`、`splitIndex`、`summaryJson`，只删除 `messages[compressStart..splitIndex-1]`，插入新摘要，保留 `messages[0..compressStart-1]`。

### 当前 Lua 脚本逻辑

```lua
-- messages[0..splitIndex-1] 全部删除，替换为一条 summary
local newMessages = { summaryMsg }
for i = splitIndex + 1, #messages do
    newMessages[#newMessages + 1] = messages[i]
end
-- 结果: [摘要] + [tail]
```

### 改为增量模式

```lua
-- messages[0..compressStart-1] 保留（旧摘要们）
-- messages[compressStart..splitIndex-1] 删除（被新摘要替换）
-- messages[splitIndex..end] 保留（tail）
-- 结果: [旧摘要们] + [新摘要] + [tail]
```

- [ ] **Step 1: 重写 compress_and_replace.lua**

完整替换为：

```lua
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
-- [0..compressStart-1] (old summaries, preserved)
-- + [new summary]
-- + [splitIndex..end] (tail, preserved)
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
```

- [ ] **Step 2: 验证编译通过**

Run: `mvn compile -f pom.xml -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/scripts/compress_and_replace.lua
git commit -m "feat: 增量压缩 - Lua 脚本支持保留旧摘要 + 插入新摘要"
```

---

## Task 4: 编写单元测试

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ConversationCompressionServiceTest.java`

**说明：** 验证增量压缩的核心逻辑正确性。重点测试：
1. 首次压缩（无旧摘要）→ 行为与之前一致
2. 二次压缩（有旧摘要）→ 只对新消息生成摘要
3. 边界情况：head 中无新消息可压缩

- [ ] **Step 1: 编写测试类**

```java
package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.CompressionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationCompressionServiceTest {

    @Mock private ThreadPoolTaskExecutor compressionExecutor;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private RedisScript<Long> compressScript;
    @Mock private RedisScript<Long> truncateScript;
    @Mock private DeepSeekClient deepSeekClient;
    private ObjectMapper objectMapper = new ObjectMapper();
    private CompressionProperties config;

    private ConversationCompressionService service;

    @BeforeEach
    void setUp() {
        config = new CompressionProperties();
        config.setSoftThreshold(30);
        config.setKeepRounds(6);
        config.setSummaryMarker("[历史摘要]");

        service = new ConversationCompressionService(
                compressionExecutor, config, stringRedisTemplate,
                compressScript, truncateScript, deepSeekClient, objectMapper
        );
    }

    @Test
    void estimateTokens_shouldReturnPositiveForNonEmptyHistory() {
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "Hello world")
        );
        int tokens = service.estimateTokens(history);
        assertTrue(tokens > 0);
    }

    @Test
    void checkAndCompress_shouldNotTrigger_belowSoftThreshold() {
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "hi")
        );
        // 不应抛异常，也不应触发任何 Redis 操作
        assertDoesNotThrow(() -> service.checkAndCompress("conv1", history, "user1"));
        verifyNoInteractions(stringRedisTemplate);
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -f pom.xml -Dtest=ConversationCompressionServiceTest -pl .`
Expected: Tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/service/ConversationCompressionServiceTest.java
git commit -m "test: 增量压缩 - 添加 ConversationCompressionService 基础单元测试"
```

---

## Task 5: 端到端集成验证

**Files:** 无新文件

**说明：** 手动验证完整的增量压缩流程。启动应用后进行以下测试。

- [ ] **Step 1: 编译打包**

Run: `mvn clean compile -f pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 2: 手动测试场景**

场景 A — 首次压缩（无旧摘要）：
1. 发送 31+ 条消息触发软阈值
2. 检查 Redis 中 `conversation:{id}` 的 JSON
3. 确认结构为 `[{摘要A}, {tail消息们}]`

场景 B — 二次压缩（增量）：
1. 继续发送消息直到再次触发软阈值
2. 检查 Redis JSON
3. 确认结构为 `[{摘要A}, {摘要B}, {tail消息们}]`，摘要A 内容不变

场景 C — 硬阈值截断：
1. 发送大量长消息触发硬阈值
2. 确认 syncTruncate 仍正常工作

---

## 已知限制 & 未来优化

### 摘要堆积问题

多次压缩后会积累多条 `[历史摘要]` 消息。极端情况下（10+ 次压缩），摘要本身可能消耗大量 token，触发硬阈值截断。`syncTruncate` 从尾部保留消息，会丢失最早的摘要。

**当前策略（MVP 可接受）：** 实际使用中，7 天 TTL 过期后对话自动清理，单对话很少超过 3-4 次压缩周期。每次压缩间隔约 30 条消息，3 次压缩约 90 条消息的上下文。

**未来优化方向：** 当摘要数量超过阈值（如 3 条）时，将多条旧摘要合并为一条，或统一为 token 阈值判断。

---

## 改动总结

### 改了什么

| 文件 | 改动行数 | 改动内容 |
|------|---------|---------|
| `ConversationCompressionService.java` | ~25行改动 | doCompress() 新增摘要边界查找 + 只对新消息构建 LLM 输入 |
| `compress_and_replace.lua` | ~10行改动 | 新增 compressStart 参数，保留旧摘要区间 |
| `CompressionProperties.java` | +1行 | 新增 summaryMarker 字段（有默认值） |
| **总计** | **~36行** | |

### 没改什么（不需要动的部分）

- `ChatHandler.java` — 调用 `checkAndCompress()` 的方式完全不变
- `sync_truncate.lua` — 硬阈值截断逻辑不变
- `CompressionConfig.java` — Bean 注册不变
- `ConversationService.java` — MySQL 同步不变
- `submitAsyncCompression()` / `executeCompression()` — 异步调度和重试逻辑不变
- `estimateTokens()` / `checkAndCompress()` / `syncTruncate()` — 阈值判断不变

### 难度结论

**改动难度：低。** 原因：
1. 改动集中在 1 个 Java 方法 + 1 个 Lua 脚本，总共约 36 行
2. 不改变任何外部接口和调用链
3. 不引入新依赖
4. 向后兼容——对于没有旧摘要的对话，行为与之前完全一致
5. 风险极低：最差情况是摘要边界没找到（`lastSummaryIndex = -1`），退化为全量压缩
