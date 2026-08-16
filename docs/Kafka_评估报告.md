# ShadowRAG Kafka 链路评估报告

> 评估时间：2026-08-06
> 评估范围：`KafkaConfig` / `FileProcessingConsumer` / `UploadController.mergeFile` / `VectorizationService` / `ParseService` / `docker-compose.yaml`（Kafka 部分）
> 结论：**Kafka 基础设施配置尚可（KRaft 单节点、3 partition、幂等生产者、事务、DLT+重试），但消费侧存在若干严重问题：重试不幂等导致 DB+ES 数据膨胀、死信队列无人消费、僵尸 topic、长任务超时风险、跨系统事务割裂。**

---

## 一、当前链路概览

```
UploadController.mergeFile (@Transactional DB)
  ├─ uploadService.mergeChunks()           # MinIO 合并分片 → objectUrl
  └─ kafkaTemplate.executeInTransaction(   # Kafka 事务（独立于 DB 事务）
        kt.send(file-processing-topic1, FileProcessingTask)
     )

FileProcessingConsumer.processTask (@KafkaListener + @Transactional)
  ├─ updateParseStatus(fileMd5, 1)         # 解析中
  ├─ downloadFileFromStorage(filePath)     # MinIO presigned URL / 本地路径
  ├─ 解析（MinerU 优先 / 纯文本 / Tika 回退）→ saveChildChunks → DB document_vectors
  ├─ vectorizationService.vectorize()      → ES bulkIndex（doc id = UUID.randomUUID）
  └─ updateParseStatus(fileMd5, 2)         # 解析完成
     catch → updateParseStatus(3) → 抛异常 → DefaultErrorHandler
              FixedBackOff(3s, 4) 重试 4 次 → 仍失败 → DeadLetterPublishingRecoverer → file-processing-dlt
```

### Kafka 基础设施（docker-compose）
- 单节点 KRaft（controller+broker 合一），`PLAINTEXT://localhost:9092`
- 自动创建 3 个 topic，各 3 partitions、replication-factor=1：
  - `file-processing-topic1`（主任务）
  - `file-processing-dlt`（死信）
  - `vectorization`（**僵尸，无生产者无消费者**）

### 生产者配置（KafkaConfig）
- `acks=all` + `enable.idempotence=true` + `retries=3` + 事务前缀 `file-upload-tx-` ✅
- 投递用 `executeInTransaction` 包单条 send

### 消费者配置
- `concurrency=3`（与 3 partition 对齐）
- `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `FixedBackOff(3000ms, 4)`（首试 + 4 次重试 = 共 5 次）
- `auto-offset-reset=earliest`
- `JsonDeserializer` + `trusted.packages=*`

---

## 二、Bug / 问题清单（按严重度）

### 🔴 Bug 1：重试不幂等 → DB + ES 双重数据膨胀（最严重）

**位置**：`FileProcessingConsumer.processTask` + `ParseService.saveChildChunks` + `VectorizationService.vectorize`

**问题**：
- `DocumentVector` 主键是自增 `vectorId`，`fileMd5 + chunkId` **没有唯一约束**（只是普通索引）。
- `saveChildChunks` 每次 `new DocumentVector()` + `save()`，**不先删除旧 chunks** → 重试时 DB `document_vectors` 表新增重复行（相同 fileMd5/chunkId/content）。
- `vectorize` 用 `UUID.randomUUID().toString()` 作 ES 文档 id，**不先删除旧 ES 文档** → 重试时 ES 写入新 id 的重复文档。
- 当向量化失败抛异常 → `updateParseStatus(3)` → 重抛 → `DefaultErrorHandler` 重试 4 次 → 每次都重新下载、重新解析、重新向量化。

**影响**：
1. DB `document_vectors` 表行数 = (重试次数+1) × chunk 数。
2. ES `knowledge_base` 索引文档数同样翻倍。
3. **检索时同一内容被重复召回**，直接污染 Agentic RAG 的上下文（`buildContext` 会拼出重复片段），LLM 看到重复内容，回答质量下降。
4. MinerU/GPU 资源被重复消耗（大 PDF 解析很贵）。

**建议修复**（幂等化三件套）：
1. `processTask` 入口先 `parseStatus==2` 则直接 return（已完成不重复处理）。
2. 解析前 `documentVectorRepository.deleteByFileMd5(fileMd5)` + ES 按 `fileMd5` 删除旧文档。
3. ES 文档 id 用确定性 id：`fileMd5 + "_" + chunkId`（而非 UUID），天然 upsert。
4. 给 `document_vectors` 加 `@UniqueConstraint(fileMd5, chunkId)` 兜底。

---

### 🔴 Bug 2：死信队列（DLT）无消费者，失败即"黑洞"

**位置**：`KafkaConfig`（配置了 DLT recoverer）+ 全局搜索无 `@KafkaListener(topics=...dlt)`

**问题**：`file-processing-dlt` topic 有创建、有 recoverer 投递，但**没有任何消费者**。重试 5 次仍失败的消息进入 DLT 后：
- 无监听、无告警、无补偿、无人工介入入口。
- 用户的文件永远停在 `parseStatus=3`，无人知晓。
- 只能靠运维 `kafka-console-consumer` 手动捞。

**影响**：静默失败，文件处理丢失无感知。

**建议修复**：
- 加一个 DLT `@KafkaListener`：记录失败任务、更新 `parseStatus` 为更明确的"死信"状态（如 4）、发告警（日志/通知）、提供后台手动重投接口。
- 或至少在 DLT 消费者里把失败原因和 task 落库到一张 `failed_tasks` 表，供排查。

---

### 🔴 Bug 3：`vectorization` topic 是僵尸 topic

**位置**：`docker-compose.yaml` 创建了 `vectorization` topic，但代码里无生产者无消费者

**问题**：向量化在 `FileProcessingConsumer.processTask` 里**同步调用** `vectorize()` 完成，根本没走 Kafka。这个 topic 是历史遗留或废弃设计。

**影响**：误导排查；占用资源（虽小）。

**建议**：
- 方案 A（清理）：从 docker-compose 删除该 topic。
- 方案 B（架构升级，推荐）：把向量化拆成独立的 Kafka 阶段——`parse-topic → 解析消费者 → vectorization-topic → 向量化消费者`。好处：解析和向量化解耦，向量化失败可独立重试（不重复解析），GPU 资源可独立伸缩。这也能直接缓解 Bug 1。

---

### 🔴 Bug 4：长任务超过 `max.poll.interval.ms` → rebalance → 重复处理

**位置**：`FileProcessingConsumer` + 消费者默认 `max.poll.interval.ms=300000`（5 分钟）

**问题**：MinerU 解析大 PDF（几十兆、含大量图片）单次可能数分钟；若叠加 GPU 排队，极易超过 5 分钟。超时后：
- 消费者被踢出消费组 → 触发 rebalance → 分区重新分配 → **消息重新投递**给其他消费者。
- 原消费者可能还在处理（没真正挂），处理完提交 offset 失败 → 新消费者又处理一遍。
- 叠加 Bug 1（不幂等）→ 数据再翻倍。

**影响**：大文件处理几乎必然触发重复 + 数据膨胀。

**建议修复**：
- 调大 `max.poll.interval.ms`（如 600000/10min，甚至更长）+ `max.poll.records=1`。
- 或把 MinerU 调用异步化（提交任务给 MinerU 后轮询，释放消费者线程）。
- 根本解法还是 Bug 3 的方案 B（拆分阶段）。

---

### 🟠 Bug 5：`mergeFile` 跨系统事务割裂，可产生孤儿文件

**位置**：`UploadController.mergeFile`（`@Transactional` DB + MinIO + `executeInTransaction` Kafka）

**问题**：
- MinIO 合并（`mergeChunks`）成功后，Kafka 发送若失败：文件已合并到 MinIO，但无解析任务 → **孤儿文件**，永远不被解析。
- DB 事务（`@Transactional`）与 Kafka 事务（`executeInTransaction`）是**两套独立事务**，不联动：
  - DB 提交成功 + Kafka 发送失败 → DB 显示合并完成，但任务丢失。
  - Kafka 发送成功 + DB 回滚 → 消费者拿到任务但 DB 无记录，下载/解析异常。
- 方法内 MinIO 操作完全不在任何事务保护内。

**影响**：文件状态不一致，用户看到"合并成功"但永远搜不到内容。

**建议修复**：
- 用"本地消息表/outbox pattern"：DB 事务里写一条 `outbox` 记录，事务提交后异步发 Kafka，发送成功后标记 outbox；定时任务补偿未发送的 outbox。
- 或至少 Kafka 发送失败时回滚 DB + 删除 MinIO 合并文件（补偿）。

---

### 🟠 Bug 6：`downloadFileFromStorage` 吞异常返回 null

**位置**：`FileProcessingConsumer.downloadFileFromStorage`

**问题**：
- catch 块 `return null;`，真实错误（403/超时/URL 格式错）只记日志后被吞。
- `processTask` 里 `if (fileStream == null) throw new IOException("流为空")`，丢失了原始错误信息。
- presigned URL 过期是典型场景：MinIO presigned URL 有时效，任务积压后 URL 失效 → 403 → 但重试 4 次都是同样的过期 URL，必然失败，白白消耗 4 次 × 3 秒 + 资源。

**建议修复**：
- 不要 return null，直接抛出带原始原因的异常。
- Kafka 消息里存 MinIO `bucket + object key`，消费者用 MinIO SDK 稳定下载，不用有时效的 presigned URL。

---

### 🟠 Bug 7：`auto-offset-reset=earliest` + 无幂等 = 历史重放灾难

**位置**：`application.yml` + Bug 1

**问题**：消费者组首次启动或 offset 丢失后，`earliest` 会从 topic 最早消息开始消费。叠加不幂等（Bug 1），所有历史任务会被重新解析+向量化一遍，DB/ES 数据量爆炸。

**建议**：先修幂等（Bug 1），否则至少把 `auto-offset-reset` 改 `latest`（但 `latest` 有"新组丢消息"风险，治标不治本）。

---

### 🟠 Bug 8：`concurrency=3` 争抢 GPU

**位置**：`KafkaConfig.kafkaListenerContainerFactory` `setConcurrency(3)`

**问题**：MinerU 解析和 Ollama embedding 都依赖 GPU。3 个消费者线程并发跑，会争抢 GPU 显存/算力，可能 OOM 或严重排队，反而比串行更慢。

**建议**：GPU 密集型任务 `concurrency=1`，或用 `Semaphore` 限制同时跑 MinerU/embedding 的并发数（如 1~2）。

---

### 🟡 Bug 9：`processTask` 的 `@Transactional` 基本无效且具误导性

**位置**：`FileProcessingConsumer.processTask`

**问题**：方法内全是 MinIO 下载、MinerU/Tika 解析、ES 写入等远程操作，**都不在 DB 事务内**。`@Transactional` 实际只覆盖 `updateParseStatus`（单条 update），几乎无意义，却让人误以为有事务保护。

**建议**：移除或改为只在真正涉及多表写的地方加事务；解析/向量化不应在事务内。

---

### 🟡 Bug 10：解析成功 + 向量化失败的失败粒度过粗

**位置**：`processTask` 只有一个 `parseStatus`（0待解析/1解析中/2完成/3失败）

**问题**：解析成功后向量化失败，`parseStatus` 被设成 3（失败），重试时**从解析重新开始**（重复解析，见 Bug 1）。实际上解析结果已可用，只需重试向量化。

**建议**：状态机细化：`0待解析 → 1解析中 → 2解析完成 → 3向量化中 → 4全部完成 → 5失败`。向量化失败只重试向量化（配合 Bug 3 方案 B 更自然）。

---

### 🟡 改进点

| # | 问题 | 建议 |
|---|------|------|
| 11 | `FileProcessingTask` 无 traceId/创建时间/重试次数 | 加字段，便于追踪排障 |
| 12 | presigned URL 时效 vs 任务积压矛盾 | 消息存 bucket+object key，用 MinIO SDK 下载 |
| 13 | `executeInTransaction` 包单条 send 事务开销大于收益 | 事务价值在跨系统原子性，单条 send 无需事务（除非配合 outbox） |
| 14 | send 未指定 key（fileMd5） | 指定 `fileMd5` 为 key，同文件任务落同 partition，保序 |
| 15 | 缺幂等生产者去重 | 已开 `enable.idempotence`，但业务层无"已投递"标记，重复点合并仍可能 |

---

## 三、优先级建议

| 优先级 | 事项 | 类型 |
|--------|------|------|
| P0 | Bug 1 幂等化（先删旧 + 确定性 ES id + 完成态短路） | bug |
| P0 | Bug 2 DLT 加消费者 + 告警 + 死信状态 | bug |
| P0 | Bug 4 调大 max.poll.interval.ms / 异步化 MinerU | bug |
| P1 | Bug 3 vectorization topic 拆分独立阶段（或先删除） | 架构 |
| P1 | Bug 5 mergeFile 用 outbox pattern | bug |
| P1 | Bug 6 下载异常不吞 + 存 object key | bug |
| P2 | Bug 7/8/10 auto-offset、concurrency、状态机 | bug |
| P3 | Bug 9 + 改进点 11~15 | 工程 |

---

## 四、与 Agentic RAG 的关联

Bug 1（重试导致 ES 重复文档）会**直接污染 Agentic RAG 的检索结果**：`HybridSearchService` 会召回同一内容的多个副本，`buildContext` 拼出重复片段，LLM 基于重复上下文回答。修 Kafka 幂等 = 修 RAG 数据质量。
