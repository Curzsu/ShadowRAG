# Interview-Ready Context Compression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将上下文压缩改造成“原始消息追加日志 + Redis 版本化压缩视图 + 调用前 Token 预算”，并交付中文面试回答模板。

**Architecture:** MySQL `conversation_messages` 是原始消息 source of truth，Redis 保存可重建的压缩工作集。聊天消息通过 Lua 原子追加并递增版本，压缩通过 CAS 版本校验提交；所有 LLM 调用在发送前由统一预算器裁剪和校验。

**Tech Stack:** Java 17、Spring Boot 3.4、Spring Data JPA、Spring Data Redis、Redis Lua、jtokkit、JUnit 5、Mockito

**Spec:** `docs/superpowers/specs/2026-08-19-interview-ready-context-compression-design.md`

## Global Constraints

- 不引入新的基础设施或外部服务。
- MySQL 原始消息只追加，Redis 压缩结果不能覆盖它。
- LLM 调用前必须为配置的最大输出 Token 和 2,048 Token 安全余量留出空间。
- 新数据使用显式 `type=summary`，同时只读兼容旧 `[历史摘要]`。
- 未获得 Git 交付授权，本计划不创建 commit 或 push。

---

### Task 1: 统一 Token 估算与调用前预算

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/TokenEstimator.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/service/ContextBudgetService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/AiProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ContextBudgetServiceTest.java`

**Interfaces:**
- Produces: `int TokenEstimator.countText(String)`、`int TokenEstimator.countMessages(List<? extends Map<String, ?>>)`。
- Produces: `List<Map<String,Object>> ContextBudgetService.fit(List<Map<String,Object>>, int reservedOutputTokens, int extraTokens, int protectedTailCount)`。

- [ ] **Step 1: 写失败测试**，覆盖删除最旧历史、保留 system/尾部协议、tool result 缩短和必要消息仍超限时抛错。

```java
@Test
void fit_shouldDropOldestHistoryAndPreserveProtocolTail() {
    List<Map<String, Object>> fitted = service.fit(messages, 100, 0, 3);
    assertEquals("system", fitted.get(0).get("role"));
    assertEquals(List.of("user", "assistant", "tool"),
            fitted.subList(fitted.size() - 3, fitted.size()).stream()
                    .map(m -> m.get("role")).toList());
    assertTrue(estimator.countMessages(fitted) <= 700);
}
```

- [ ] **Step 2: 运行 `mvn -Dtest=ContextBudgetServiceTest test`**，确认因类不存在失败。
- [ ] **Step 3: 实现配置和最小预算算法**，其中输入预算为 `windowTokens - reservedOutputTokens - safetyMarginTokens - extraTokens`。

```java
public List<Map<String, Object>> fit(List<Map<String, Object>> messages,
                                     int reservedOutputTokens,
                                     int extraTokens,
                                     int protectedTailCount) {
    int budget = properties.getContext().getWindowTokens()
            - reservedOutputTokens
            - properties.getContext().getSafetyMarginTokens()
            - extraTokens;
    // 复制输入，从 index=1 开始删除最旧历史；首个 system 和尾部协议消息不可删除。
}
```

- [ ] **Step 4: 重新运行测试**，确认全部通过。

### Task 2: 建立追加式原始消息日志

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/model/ConversationMessage.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/repository/ConversationMessageRepository.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/service/ConversationMessageService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/model/Conversation.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java`
- Modify: `docs/databases/ddl.sql`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ConversationMessageServiceTest.java`

**Interfaces:**
- Produces: `List<Map<String,String>> appendTurn(String conversationId, String username, String userContent, String assistantContent, LocalDateTime timestamp)`。
- Produces: `List<Map<String,String>> getRawHistory(String conversationId)`。

- [ ] **Step 1: 写失败测试**，验证一轮消息事务性保存、返回数据库 ID 作为 `seq`、读取顺序和首次消息标题。

```java
@Test
void appendTurn_shouldPersistRawMessagesAndReturnDatabaseSequence() {
    List<Map<String, String>> result = service.appendTurn(
            "conv-1", "alice", "问题", "回答", LocalDateTime.parse("2026-08-19T18:00:00"));
    assertEquals(List.of("user", "assistant"), result.stream().map(m -> m.get("role")).toList());
    assertEquals(List.of("101", "102"), result.stream().map(m -> m.get("seq")).toList());
    verify(messageRepository).saveAll(anyList());
}
```

- [ ] **Step 2: 运行 `mvn -Dtest=ConversationMessageServiceTest test`**，确认因实现不存在失败。
- [ ] **Step 3: 实现实体、仓库和服务**；`content` 使用 `LONGTEXT`，索引为 `(conversation_id, id)`。

```java
@Entity
@Table(name = "conversation_messages", indexes =
        @Index(name = "idx_conversation_message_order", columnList = "conversation_id,id"))
public class ConversationMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;
    @Column(nullable = false, length = 16) private String role;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String content;
    @Column(nullable = false) private LocalDateTime createdAt;
}
```

- [ ] **Step 4: 修改会话切换和删除**，优先读取消息表，旧 JSON 仅作回退，删除父会话前删除子消息。

```java
List<Map<String, String>> raw = conversationMessageService.getRawHistory(conversationId);
if (raw.isEmpty()) raw = parseLegacyMessages(conversation.getMessages());
writeWorkingSetAndBumpVersion(conversationId, raw);
```

- [ ] **Step 5: 运行消息服务与会话相关测试**，确认通过。

### Task 3: Redis 原子追加与压缩 CAS

**Files:**
- Create: `src/main/resources/scripts/append_messages.lua`
- Modify: `src/main/resources/scripts/compress_and_replace.lua`
- Modify: `src/main/resources/scripts/sync_truncate.lua`
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/CompressionConfig.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ConversationCompressionServiceTest.java`

**Interfaces:**
- `append_messages.lua` 使用 `KEYS[1]=history`、`KEYS[2]=version`、`ARGV[1..n]=message JSON`，返回追加后消息数。
- `compress_and_replace.lua` 新增 expected version；版本冲突返回 `-2`，成功后递增版本。

- [ ] **Step 1: 扩展失败测试**，验证 ChatHandler 不再调用 `syncToMySQL`，压缩传入 expected version，Lua `-2` 被识别为冲突。

```java
when(stringRedisTemplate.execute(eq(compressScript), anyList(), any(String[].class)))
        .thenReturn(-2L);
assertDoesNotThrow(() -> service.compressOnce("conv-1"));
verify(deepSeekClient).callSync(anyString(), any());
```

- [ ] **Step 2: 运行压缩测试并确认失败**。
- [ ] **Step 3: 实现三个 Lua 脚本的版本协议和 Bean 注册**。

```lua
local currentVersion = tonumber(redis.call('GET', KEYS[2]) or '0')
if currentVersion ~= tonumber(ARGV[4]) then return -2 end
-- 构建并写入压缩后的数组
redis.call('INCR', KEYS[2])
```

- [ ] **Step 4: 修改聊天完成链路**，先追加 MySQL 原始日志，再调用 Lua 追加 Redis，最后按最新工作集触发压缩。

```java
List<Map<String, String>> appended = conversationMessageService.appendTurn(
        conversationId, userId, userMessage, response, LocalDateTime.now());
conversationCompressionService.appendMessages(conversationId, appended);
List<Map<String, String>> workingSet = getConversationHistory(conversationId);
conversationCompressionService.checkAndCompress(conversationId, workingSet, userId);
```

- [ ] **Step 5: 运行相关测试并确认通过**。

### Task 4: 结构化摘要与所有 LLM 调用预算接入

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ContextBudgetServiceTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ConversationCompressionServiceTest.java`

**Interfaces:**
- 摘要字段固定为 `type/role/sourceStartSeq/sourceEndSeq/content/timestamp`。
- 首次 LLM 调用 `protectedTailCount=1`；工具返回后的第二次调用 `protectedTailCount=3`。

- [ ] **Step 1: 写失败测试**，验证摘要被抽取到唯一 system 消息，旧 marker 兼容，主历史不含摘要 system 消息。

```java
@Test
void buildMessages_shouldMergeSummaryIntoSingleSystemMessage() {
    List<Map<String, Object>> messages = handler.buildMessagesForAgenticRAG(history, "继续");
    assertEquals(1, messages.stream().filter(m -> "system".equals(m.get("role"))).count());
    assertTrue(messages.get(0).get("content").toString().contains("非可信历史记忆"));
}
```

- [ ] **Step 2: 运行测试并确认失败**。
- [ ] **Step 3: 实现结构化摘要和 system 记忆装配**。

```java
Map<String, String> summaryMessage = Map.of(
        "type", "summary",
        "role", "assistant",
        "sourceStartSeq", sourceStartSeq,
        "sourceEndSeq", sourceEndSeq,
        "content", summary,
        "timestamp", timestamp);
```

- [ ] **Step 4: 在两次 LLM 调用前接入预算器，并把工具定义序列化 Token 作为 extraTokens**。

```java
int toolTokens = tokenEstimator.countText(objectMapper.writeValueAsString(SEARCH_TOOL));
messages = contextBudgetService.fit(messages, aiProperties.getGeneration().getMaxTokens(), toolTokens, 1);
messagesWithTool = contextBudgetService.fit(messagesWithTool,
        aiProperties.getGeneration().getMaxTokens(), 0, 3);
```

- [ ] **Step 5: 运行相关测试并确认通过**。

### Task 5: 文档、配置与完整验证

**Files:**
- Modify: `README.md`
- Create: `docs/interview/上下文压缩面试回答模板.md`
- Test: all backend tests

**Interfaces:**
- 面试文档包含 30 秒、1 分钟、3 分钟回答，以及并发、一致性、Token、降级、摘要失真、扩展性追问模板。

- [ ] **Step 1: 修正 README 中过时的消息条数软阈值说明**。
- [ ] **Step 2: 编写面试回答模板，所有说法必须与最终代码一致**。
- [ ] **Step 3: 运行 `mvn test`，检查总测试数、失败数和错误数**。
- [ ] **Step 4: 运行 `git diff --check` 和 `git status --short`，确认没有格式错误并列出交付文件**。

## Plan Self-Review

- Spec coverage：原始日志、Redis CAS、调用前预算、结构化摘要、测试和面试文档均有对应任务。
- Placeholder scan：无 TBD、TODO 或未定义实现步骤。
- Type consistency：所有跨任务方法签名和配置字段在首次出现处定义，后续引用一致。
