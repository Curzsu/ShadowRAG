# Plan: Chat History Redis Degradation (Redis 不可用时 MySQL 兜底)

## Background

### Current Architecture
聊天历史的读写链路：**Redis 为主，MySQL 为辅**

```
用户发消息 → WebSocket → ChatHandler.processMessage()
  ├─ getOrCreateConversationId()  → Redis GET user:{userId}:current_conversation
  ├─ getConversationHistory()     → Redis GET conversation:{conversationId}
  ├─ searchWithPermission()       → ES hybrid search
  ├─ deepSeekClient.streamResponse() → LLM 流式生成
  └─ updateConversationHistory()  → Redis SET conversation:{conversationId}
       └─ conversationService.syncToMySQL() → 异步写 MySQL

前端操作 → ConversationController
  ├─ createConversation()   → MySQL INSERT + Redis SET current_conversation
  ├─ switchConversation()   → MySQL SELECT → Redis SET (加载历史到 Redis)
  ├─ deleteConversation()   → MySQL DELETE + Redis DELETE
  └─ getConversationList()  → MySQL SELECT (不经过 Redis)
```

### Problem
1. 所有 Redis 操作都没有 try-catch。Redis 不可用时，整条聊天链路抛异常断裂
2. **Redis 超时未配置**：`application.yml` 中 `spring.data.redis` 没有设置 `timeout`，默认 Lettuce 超时为 60 秒。Redis "半死不活"时会长时间阻塞 ChatHandler 线程
3. Redis 故障期间写入压力全部转移给 MySQL，可能成为瓶颈

### Goal
Redis 不可用时，聊天系统自动降级到 MySQL，保证核心功能（发消息、收回复、历史不丢）可用。

### 关键发现（代码审查）
- `ConversationService.getCurrentConversationId()` 定义了但 **没有任何调用方**（Controller、前端、ChatHandler 都不调用）。ChatHandler 内部自己维护 `user:{userId}:current_conversation`，不走 ConversationService。原方案中 Task 4 降为"死代码清理"。
- `RedisRepository` 同样无人调用（ChatHandler 直接用 `RedisTemplate<String, String>`），属于死代码。

---

## Design Decisions（采纳/不采纳反馈的分析）

### D1: Task 8 优先级修正 → 采纳，内联到 Task 3
**反馈**: Task 8 是 Task 3 的硬依赖，P1 在 P0 后做是矛盾的。
**决定**: 不单独拆 Task 8。在 Task 3 的实现中直接内联 `syncToMySQLDirect` 逻辑，做完后再抽取为 ConversationService 的独立方法。避免依赖倒挂。

### D2: Task 4 优先级提升 → 不采纳，降级为"清理"
**反馈**: `getCurrentConversationId()` 是前端进入聊天页的第一个调用。
**实际情况**: 经代码审查，该方法 **没有被任何地方调用**。前端聊天页进入时不会调用此接口（ConversationController 里也没有对应的 `@GetMapping` 端点）。前端通过 WebSocket 直接收发消息，ChatHandler 内部自己管理 `current_conversation`。这个方法是死代码，改为清理。

### D3: Redis 超时配置 → 采纳
**反馈**: Redis 半死不活时长时间阻塞线程。
**决定**: 在 `application.yml` 中添加 `spring.data.redis.timeout: 500ms`（Lettuce 命令超时），避免线程被长时间阻塞。这是整个降级方案的前置基础——没有超时，try-catch 根本不会在合理时间内触发。

### D4: Kafka 异步化 MySQL 写入 → 不采纳，用同步直写
**反馈**: 降级时 MySQL 写入压力骤增，用 Kafka 异步化。
**分析**:
- 项目已有 Kafka 用于文件处理，技术栈可行
- 但降级场景下引入 Kafka 会增加复杂度和新故障点（如果 Redis 挂了，Kafka 也不一定稳）
- 当前写入频率低（每条聊天消息一次 MySQL UPDATE），单次写入量小（20 条消息的 JSON）
- MySQL 单机支撑这个写入量毫无压力
**决定**: 降级时同步直写 MySQL。如果未来用户量确实增长到 MySQL 写入瓶颈，再考虑引入 Kafka 异步化。当前不需要过度设计。

### D5: 熔断机制 → 采纳，简化实现
**反馈**: 频繁 try-catch 有开销，用计数器熔断。
**决定**: 在 ChatHandler 中引入一个轻量级 `RedisCircuitBreaker` 内部类。规则：连续 5 次 Redis 操作失败 → 进入"断开"状态 → 接下来 5 分钟直接跳过 Redis 走 MySQL → 5 分钟后尝试一次 Redis（半开）→ 成功则恢复，失败则继续断开 5 分钟。
- 不引入外部依赖（Resilience4j / Sentinel），保持零依赖
- 熔断状态只在内存中维护（进程重启重置），不需要持久化

### D6: AOP / 装饰器模式统一降级 → 不采纳
**反馈**: 用切面或装饰器避免到处写 try-catch。
**分析**:
- ChatHandler 里 Redis 操作只有 3 处（getOrCreate、getHistory、updateHistory），ConversationService 里有 4 处（create、switch、delete、syncToMySQL）
- 共 7 个降级点，每个降级逻辑不同（有的 fallback 到 MySQL 读，有的 fallback 到 MySQL 写，有的只是 ignore）
- AOP 切面需要在注解里表达"失败后做什么"，比直接写 try-catch 更复杂
- 装饰器模式需要为 `RedisTemplate` 写一个 wrapper 类，但降级逻辑依赖业务上下文（需要 conversationId、userId 等），不适合在 wrapper 层处理
**决定**: 直接在方法级别写 try-catch + 熔断检查。7 个降级点的 fallback 逻辑各不相同，AOP/装饰器反而增加间接性。

### D7: 数据一致性 — 删除后再创建 → 采纳
**反馈**: Task 7 降级后 Redis 残留可能导致"删除后再新建"读到旧数据。
**分析**: ChatHandler 的 `getOrCreateConversationId()` 读的是 `user:{userId}:current_conversation`（记录当前活跃会话 ID），而不是 `conversation:{conversationId}`（记录历史消息）。删除操作清理的是 `conversation:{id}` 的历史数据。降级时如果 Redis DELETE 失败，残留的是历史消息 key，下次切换到这个已删除的会话时，Redis 里还有旧历史。但 MySQL 已经删了，`conversationRepository.findByConversationId()` 返回空，ConversationService 的 switch 方法会抛 "会话不存在" 异常。
**决定**: 在 `ChatHandler.getOrCreateConversationId()` 的 Redis 读取后加一层校验——从 Redis 拿到 conversationId 后，查一次 MySQL 确认该会话确实存在。不存在则视为无效，创建新会话。这是防御性编程，成本极低。

### D8: Task 9 Redis 恢复回填 → 采纳改进
**反馈**: 回填时加锁或用 SETNX 防止并发重复。
**决定**: 使用 Redis `SETNX`（`setIfAbsent`）做幂等回填标记。回填前先 SETNX 一个 `conversation:{id}:backfilled` 的 key，只有设置成功的请求才执行回填。

---

## Implementation Tasks

### Task 0: Redis 超时配置（前置基础设施）

**文件**: `src/main/resources/application.yml`

**改动**: 在 `spring.data.redis` 下添加命令超时

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: REDACTED
      timeout: 500ms    # Lettuce 命令超时，Redis 半死不活时快速失败
```

**作用**: 所有 Redis 操作最多阻塞 500ms，超时抛 `RedisCommandTimeoutException`，让 try-catch 能在合理时间内触发降级。没有这个配置，降级代码形同虚设。

---

### Task 1: ChatHandler — 注入 MySQL 依赖 + 熔断器

**文件**: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`

**改动**:
1. 构造器新增 `UserRepository`、`ConversationRepository` 参数
2. 新增内部类 `RedisCircuitBreaker`：

```java
/**
 * 轻量级 Redis 熔断器
 * 连续 5 次失败 → 断开 5 分钟 → 半开尝试一次 → 成功恢复 / 失败继续断开
 */
private static class RedisCircuitBreaker {
    private int failureCount = 0;
    private long circuitOpenUntil = 0; // 断开到期时间戳(ms)
    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_DURATION_MS = TimeUnit.MINUTES.toMillis(5);

    synchronized boolean allowRequest() { ... }
    synchronized void recordFailure() { ... }
    synchronized void recordSuccess() { ... }
}

private final RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker();
```

---

### Task 2: ChatHandler.getOrCreateConversationId() — 降级到 MySQL

**文件**: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`

**降级逻辑**:
```
if 熔断器断开 → 跳过 Redis，直接走 MySQL
else → try Redis
  成功 → 熔断器 recordSuccess，返回结果
  失败 → 熔断器 recordFailure，走 MySQL fallback

MySQL fallback:
  1. userRepository.findByUsername(userId) → 获取 User
  2. conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId()) → 取第一条
  3. 有会话 → 返回 conversationId（同时校验 MySQL 中确实存在，防 Redis 残留脏数据）
  4. 没有会话 → 创建新的 Conversation → MySQL save → 返回 conversationId
```

**防御性校验（D7）**: 从 fallback 路径获得的 conversationId 需要确认 MySQL 中存在。这防止了"Redis 残留已删除会话 ID"的边界情况。

---

### Task 3: ChatHandler.getConversationHistory() — 降级到 MySQL

**文件**: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`

**降级逻辑**:
```
if 熔断器断开 → 跳过 Redis
else → try Redis GET conversation:{conversationId}
  成功 → 返回解析后的历史
  失败 → 熔断器 recordFailure，走 MySQL fallback

MySQL fallback:
  1. conversationRepository.findByConversationId(conversationId)
  2. 解析 conversation.getMessages()（JSON 格式与 Redis 一致）
  3. 返回历史列表
```

---

### Task 4: ChatHandler.updateConversationHistory() — 降级到 MySQL 直写

**文件**: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`

**降级逻辑**:
```
1. 构建 newHistory (内存操作，不依赖 Redis)
2. 序列化 json = objectMapper.writeValueAsString(history)
3. if 熔断器允许 → try Redis SET
     成功 → 熔断器 recordSuccess，然后 conversationService.syncToMySQL(conversationId, userId)
     失败 → 熔断器 recordFailure，走直写 MySQL
4. 直写 MySQL: conversationService.syncToMySQLDirect(conversationId, userId, json)
```

**syncToMySQLDirect 方法（内联实现，D1）**:
```java
// ConversationService 新增方法
public void syncToMySQLDirect(String conversationId, String username, String messagesJson) {
    // 与 syncToMySQL 逻辑相同，但跳过 Redis 读取步骤
    // 直接用传入的 messagesJson 写入 MySQL
    Conversation conversation = conversationRepository.findByConversationId(conversationId).orElse(null);
    if (conversation == null) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) { return; }
        conversation = new Conversation();
        conversation.setConversationId(conversationId);
        conversation.setUser(user);
    }
    conversation.setMessages(messagesJson);
    // 自动提取标题...
    conversationRepository.save(conversation);
}
```

---

### Task 5: ConversationService — create/switch/delete 降级

**文件**: `src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java`

**createConversation()**: Redis SET 用 try-catch 包裹，失败打 warn 日志不抛异常。MySQL 记录已创建成功。

**switchConversation()**: Redis SET (current_conversation) 和 SET (conversation history) 都用 try-catch 包裹，失败打 warn 日志。历史数据从 MySQL 读（已实现），核心返回值不受影响。

**deleteConversation()**: Redis GET 和 DELETE 都用 try-catch 包裹，失败打 warn 日志。MySQL 已删除成功，Redis 残留等 TTL 过期。

**注意**: ConversationService 不共享 ChatHandler 的 `RedisCircuitBreaker`。ConversationService 的 Redis 操作频率低（用户手动操作才触发），直接 try-catch 即可，不需要熔断。

---

### Task 6: 清理死代码

- 删除 `ConversationService.getCurrentConversationId()`（无调用方）
- 删除 `RedisRepository`（无调用方，ChatHandler 和 ConversationService 都直接用 RedisTemplate）

---

### Task 7: Redis 恢复回填（P3，可选）

**场景**: Redis 恢复后，用户下次发消息时自动将 MySQL 历史加载回 Redis。

**实现**: 在 `ChatHandler.getOrCreateConversationId()` 正常路径（Redis 可用）成功获取 conversationId 后，异步检查 Redis 中是否有 `conversation:{id}` 的历史。如果没有，从 MySQL 加载一份到 Redis，用 `setIfAbsent` 防止并发重复回填。

**优先级**: 低。用户切换会话（前端调用 switchConversation）时自然会加载。此 Task 仅优化"Redis 恢复后用户继续在当前会话聊天"的体验。

---

## 优先级排序

| 优先级 | Task | 影响 | 改动量 |
|--------|------|------|--------|
| **P0** | Task 0: Redis 超时配置 | 降级方案的前置基础，没有超时 try-catch 无意义 | 1 行 YAML |
| **P0** | Task 1: ChatHandler 注入依赖 + 熔断器 | Task 2/3/4 的前置 | 中 |
| **P0** | Task 2: getOrCreateConversationId 降级 | 聊天完全不可用 | 中 |
| **P0** | Task 3: getConversationHistory 降级 | 聊天完全不可用 | 小 |
| **P0** | Task 4: updateConversationHistory 降级 | LLM 回复丢失 | 中（含 syncToMySQLDirect） |
| **P1** | Task 5: ConversationService CRUD 降级 | 创建/切换/删除会话失败 | 小 |
| **P1** | Task 6: 清理死代码 | 代码整洁 | 小 |
| **P3** | Task 7: Redis 恢复回填 | 自动恢复缓存 | 小 |

## 降级后的行为总结

| 功能 | Redis 正常 | Redis 不可用 |
|------|-----------|-------------|
| 发消息 | Redis 缓存历史 → LLM 回复 → Redis + MySQL | MySQL 读历史 → LLM 回复 → MySQL 直写 |
| 收到回复 | 正常 | 正常（流式 WebSocket 不经过 Redis） |
| 历史不丢 | 7 天 TTL + MySQL 持久化 | MySQL 持久化 |
| 创建会话 | MySQL + Redis | 仅 MySQL |
| 切换会话 | MySQL 读 + Redis 缓存 | 仅 MySQL 读 |
| 删除会话 | MySQL + Redis | 仅 MySQL（Redis 残留等 TTL 过期） |
| 熔断恢复 | — | 连续 5 次失败后断开 5 分钟，半开探测恢复 |
| 性能 | Redis 热路径，快 | 每次读 MySQL，慢但可用 |

## 不改动的部分

- JWT token 缓存（Redis 不可用时 Spring Security 层面就该报错了，不在聊天降级范围）
- OrgTagCacheService（组织标签缓存降级是独立话题）
- Kafka（降级时不用 Kafka，直接同步写 MySQL，见 D4 分析）
