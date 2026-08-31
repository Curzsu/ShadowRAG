# ShadowRAG Agentic RAG 路由：简历与面试准备

> 适用岗位：AI 应用工程师、RAG/Agent 工程师、Java 后端 + 大模型应用岗位  
> 准备目标：能在 30 秒、2 分钟和深挖追问三个层次讲清楚这项改造  
> 代码基线：ShadowRAG 当前 `master` 分支，2026-08-30

## 0. 先记住这一条边界

当前仓库已经实现的是：

- 把“每轮先检索、再调用模型”改成“第一次模型调用携带搜索 Tool，由模型决定直接回答还是调用搜索”。
- 搜索路径执行 KNN + BM25、RRF 融合和可选 Rerank，再把结果作为 Tool Result 交给模型生成最终答案。
- 通用问题不经过 Embedding、Elasticsearch 和 Reranker，直接从第一次模型调用流式输出。

当前仓库已经形成的评测证据是：

- 锁定的 120 条独立路由测试集，`SEARCH/DIRECT = 60/60`，含多轮指代、困难正例和困难负例。
- `glm-5-3-flash` 首轮 Function Calling 原始预测、混淆矩阵和分标签报告：120/120 路由正确，SEARCH Recall 60/60。
- 60 条通用问题的强制检索与 Agentic 原始 TTFT 记录：P50 从 13,187 ms 降至 8,659 ms，降低 34.34%。
- 模型、端点、Prompt、数据 SHA-256、预热和执行顺序等运行元数据。

尚未完成的是：测试集真人逐条复核、代码级高精度规则兜底、置信度阈值和显式澄清 Tool。因此当前可以使用真实数字，但在真人复核前不能把测试集称为“人工标注”。

---

## 1. 最终简历文案

### 1.1 当前可直接投递版（推荐，指标已实测）

> **Agentic RAG 路由：** 将每轮强制检索改造为基于 **Function Calling** 的按需检索，使模型在通用回答与知识库搜索之间自主决策；通过 **Tool Description 优化与正反边界样例**降低误触发和漏检索，在 **120 条独立路由测试集**上达到 **100% 路由准确率、100% 知识库问题召回率**，相较强制检索基线将通用问答 **P50 TTFT 降低 34.3%**。

这版有原始预测、混淆矩阵和逐条 TTFT 记录支撑。`100%` 是当前锁定集上的单次实测值，不代表线上长期零错误；面试时主动说明测试集类别平衡且尚待真人复核。

### 1.2 真人逐条复核后使用的版本

> **Agentic RAG 路由：** 将每轮强制检索改造为基于 **Function Calling** 的按需检索，使模型在通用回答与知识库搜索之间自主决策；通过 **Tool Description 优化与正反边界样例**降低误触发和漏检索，在 **120 条人工标注的独立测试集**上达到 **100% 路由准确率、100% 知识库问题召回率**，相较强制检索基线将通用问答 **P50 TTFT 降低 34.3%**。

只有项目作者逐条盲审并把 `annotationStatus` 改为 `human_reviewed` 后，才把“独立路由测试集”改成“人工标注的独立测试集”。现有数字已经具备以下材料：

1. 锁定的 120 条测试集及标签规范。
2. 原始预测结果和混淆矩阵。
3. 强制检索与 Agentic 两种模式的原始 TTFT 记录。
4. 相同模型、Prompt、并发和运行环境的对照说明。

### 1.3 30 秒口述版

> 原来的链路不管用户问什么，都会先做 Embedding、ES 检索和 Rerank，再调用大模型。这样闲聊、常识和编程问题也会产生无效检索与上下文噪声。我把搜索封装成 `search_knowledge_base` Tool，第一次模型调用只携带对话和工具定义；模型可以直接回答，也可以生成 Tool Call。只有调用 Tool 时，后端才执行混合检索，并把结果按标准 Tool 消息协议交给第二次模型调用。这样保留了文档问答能力，同时让通用问题跳过整条检索链路。

---

## 2. 改造前后分别是什么

### 2.1 改造前：每轮强制 RAG

```text
用户问题
  → 读取历史
  → Query Embedding
  → Elasticsearch KNN + BM25
  → RRF 融合
  → Cross-Encoder Rerank
  → 拼接检索上下文
  → LLM 流式回答
```

主要问题：

- “你好”“解释 HashMap”“1+1 等于多少”也会触发检索。
- 增加 Embedding、ES 和 Rerank 延迟及资源开销。
- 无关文档可能进入 Prompt，反而干扰通用问题回答。
- 检索上下文占用 Token，增加调用成本。

### 2.2 改造后：Function Calling 按需 RAG

```text
用户问题 + 历史 + System Prompt + search_knowledge_base Tool
                         ↓
                   第一次 LLM 调用
                  /                 \
        没有 Tool Call            产生 Tool Call
              ↓                         ↓
       通用回答直接流式输出      解析 arguments.query
                                        ↓
                              KNN + BM25 → RRF → Rerank
                                        ↓
                              assistant(tool_calls)
                                  + tool(result)
                                        ↓
                                  第二次 LLM 调用
                                        ↓
                              基于知识库的最终回答
```

这个设计的核心不是“多调用一次模型”，而是把检索从固定前置步骤变成模型可选择的工具。

---

## 3. 当前代码中的核心实现

### 3.1 搜索 Tool 定义

位置：`src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`

核心 Tool 的语义是：

```json
{
  "type": "function",
  "function": {
    "name": "search_knowledge_base",
    "description": "搜索知识库文档。当用户问题涉及已上传的文档、文件、知识库内容时调用；通用知识、闲聊和数学计算不需要搜索。",
    "parameters": {
      "type": "object",
      "properties": {
        "query": {
          "type": "string",
          "description": "用于知识库检索的搜索查询"
        }
      },
      "required": ["query"]
    }
  }
}
```

为什么 Tool Description 重要：模型并不是按照 Java `if/else` 选择工具，而是结合 System Prompt、Tool 名称、描述、参数 Schema 和对话上下文完成决策。描述中的正边界和负边界会直接影响误触发与漏检索。

### 3.2 第一次模型调用

`ChatHandler.processMessage()` 完成以下工作：

1. 获取或创建 `conversationId`。
2. 从 Redis 读取工作集，Redis miss 时从 MySQL 恢复。
3. 构建 `system + history + current user` 消息。
4. 计算上下文预算，为 Tool Schema 和模型输出预留 Token。
5. 调用 `DeepSeekClient.streamWithTools(messages, SEARCH_TOOL, ...)`。

第一次调用不注入知识库检索结果。否则模型在决定是否搜索前就已经产生检索成本，也失去了按需路由的意义。

### 3.3 流式 Tool Call 解析

位置：`src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java`

OpenAI-compatible 流式响应中的 Tool arguments 可能被拆成多个片段，例如：

```text
chunk 1: {"query":"基金
chunk 2: 最大回撤
chunk 3: 是多少"}
```

当前代码分别处理：

- `delta.content`：模型直接回答时的文本片段。
- `delta.tool_calls[0].id`：Tool Call ID。
- `delta.tool_calls[0].function.arguments`：分片参数，持续追加到 `StringBuilder`。

流结束后：

- 如果没有收到 Tool arguments，则认为模型选择直接回答。
- 如果收到 Tool arguments，则解析其中的 `query` 并执行搜索。
- 参数解析失败时回退到原始用户问题，保证链路可以继续。

### 3.4 检索执行

Tool Call 触发后调用：

```java
searchService.searchWithPermission(searchQuery, userId, 10)
```

检索链路包括：

1. 对查询生成 BGE-M3 Embedding。
2. Elasticsearch KNN 向量召回。
3. Elasticsearch BM25 关键词召回。
4. 在两路召回阶段使用相同的用户、公开文档和组织权限过滤。
5. Java 端使用 RRF，按排名而非原始分数融合两路结果。
6. 可用时调用 Cross-Encoder Reranker；失败时保留 RRF 结果降级。

### 3.5 标准 Tool 消息协议

搜索后不是把结果随意拼到用户问题里，而是构造标准消息序列：

```json
{
  "role": "assistant",
  "tool_calls": [{
    "id": "call_xxx",
    "type": "function",
    "function": {
      "name": "search_knowledge_base",
      "arguments": "{\"query\":\"基金最大回撤\"}"
    }
  }]
}
```

```json
{
  "role": "tool",
  "tool_call_id": "call_xxx",
  "content": "[1] (基金评估报告.pdf) ……"
}
```

第二次模型调用读取这两条消息，理解“刚才调用了哪个 Tool、Tool 返回了什么”，再生成最终回答。

### 3.6 为什么第二次调用不再携带 Tools

当前设计是一个可控的两阶段流程：

```text
route/direct → search once → final answer
```

优点是调用次数和成本容易预测，也不会出现无限 Tool Loop。局限是模型看到检索结果不足时，不能自动改写 Query 再搜索；这也是为什么当前项目应称为“单工具两阶段 Agentic RAG”，而不是通用多工具 Agent Runtime。

---

## 4. Tool Description、边界样例和规则兜底怎么理解

### 4.1 当前已实现：描述中的正负边界

当前 Tool Description 已说明：

- 涉及上传文档、文件、知识库时调用搜索。
- 通用知识、闲聊、数学计算不调用搜索。

System Prompt 也包含对应规则。这可以在不增加独立分类模型的情况下影响 Tool Selection。

### 4.2 边界样例应该长什么样

如果未来进行系统化优化，Few-shot 应优先放边界案例，而不是明显的简单案例：

```text
“根据我上传的年报总结风险” → SEARCH
“企业年报通常包含哪些章节” → DIRECT

历史：“正在分析《基金评估报告》”
当前：“它的最大回撤是多少” → SEARCH

“PDF 和 Word 有什么区别” → DIRECT
“我上传的 PDF 第三页说了什么” → SEARCH
```

这些成对样例比“搜索文档 → SEARCH”更能帮助模型学习决策边界。

### 4.3 高精度规则兜底应怎么做

代码级规则只能覆盖非常明确的情况，例如：

```text
“根据/查看/总结/对比” + “我上传的/知识库中的/这份文档/第 N 页”
```

命中后直接进入搜索，避免模型漏调 Tool。规则必须追求 Precision，而不是覆盖率；不能看到“PDF”“报告”“文档”就强制检索，否则“PDF 是什么格式”会产生大量误触发。

重要边界：当前主分支尚未实现这层代码级规则。如果没有补代码，就不要在投递版简历中写“高精度规则兜底”。

---

## 5. 120 条测试集与路由评测

### 5.1 标签定义

- `SEARCH`：答案必须依赖用户上传文档、组织知识库或当前对话明确引用的文档。
- `DIRECT`：无需访问用户知识库即可回答，并且用户没有要求依据文档。

建议保持 60 条 `SEARCH` 和 60 条 `DIRECT`，防止类别不平衡把 Accuracy 虚高。

### 5.2 数据结构

```json
{"id":"search-001","history":[],"query":"根据我上传的基金报告，第一大重仓股是谁？","expectedRoute":"SEARCH","tags":["explicit_document"]}
{"id":"search-002","history":[{"role":"user","content":"帮我分析《基金评估报告》"}],"query":"它的最大回撤是多少？","expectedRoute":"SEARCH","tags":["multi_turn","pronoun"]}
{"id":"direct-001","history":[],"query":"最大回撤是什么意思？","expectedRoute":"DIRECT","tags":["general_knowledge"]}
{"id":"direct-002","history":[],"query":"PDF 和 Word 格式有什么区别？","expectedRoute":"DIRECT","tags":["hard_negative"]}
```

### 5.3 如何投入评测

评测器使用和生产环境相同的 System Prompt、历史消息及 Tool Schema：

```text
读取用例
  → 调用真实路由链路
  → 出现 search_knowledge_base Tool Call = SEARCH
  → 未出现 Tool Call = DIRECT
  → 对比 expectedRoute
  → 输出混淆矩阵和 Bad Case
```

不能在评测脚本中另写一套关键词分类器，否则测到的不是生产路由。

### 5.4 指标公式

```text
Accuracy = 正确路由数量 / 全部用例数量

知识库问题 Recall
       = 实际成功路由到 SEARCH 的知识库问题数量
         / 所有应该路由到 SEARCH 的问题数量
```

为什么 Accuracy 和 Recall 都要报：如果模型一律选择 DIRECT，在通用问题占比很高的数据上 Accuracy 可能仍然好看，但知识库问题会大量漏检索。Recall 专门约束这个风险。

### 5.5 开发集和测试集隔离

- 开发集用于修改 Tool Description、样例和规则。
- 锁定测试集只在方案冻结后用于正式报告。
- 如果看完测试集错误再继续调 Prompt，这 120 条就已经变成开发集，不能继续称为独立测试集。
- 由 LLM 生成的同义句需要人工审核、去重，并避免训练 Case 与测试集只有表面词语差异。

---

## 6. TTFT 如何定义和比较

### 6.1 定义

TTFT（Time To First Token）定义为：

```text
后端收到用户请求
    → 用户收到第一个真正回答内容 Token
```

“正在思考”“正在搜索”或第一次模型调用产生的过渡语不能算最终回答 Token，否则会人为美化数据。

### 6.2 公平对照

对照组和实验组必须满足：

- 同一个模型和 API 服务。
- 相同 System Prompt、历史、最大输出 Token 和温度。
- 相同机器、网络、并发和预热策略。
- 唯一变量是“每轮强制检索”还是“Function Calling 按需检索”。

本次使用锁定测试集中的 60 条通用问题，每条运行 1 次，单并发、2 对预热并交替执行两组顺序：

```text
强制检索：P50 TTFT = 13,187 ms，P95 = 23,041.25 ms
Agentic 路由：P50 TTFT = 8,659 ms，P95 = 20,641.3 ms
P50 降幅 = (13,187 - 8,659) / 13,187 × 100% = 34.34%
```

为什么通用问答会降低：Agentic 模式的通用问题只进行第一次模型调用；强制检索模式在模型调用前还需要完成 Query Embedding、ES 双路召回、RRF 和 Rerank。

### 6.3 结果记录表

| 指标 | 强制检索基线 | Agentic 路由 | 变化 |
|---|---:|---:|---:|
| 路由 Accuracy | 不适用 | 100%（120/120） | - |
| SEARCH Recall | 不适用 | 100%（60/60） | - |
| 通用问答 P50 TTFT | 13,187 ms | 8,659 ms | -34.34% |
| 通用问答 P95 TTFT | 23,041.25 ms | 20,641.3 ms | -10.42% |

原始记录位于 `docs/eval/agent_routing/results/`，运行条件与 SHA-256 见 `evidence-bundle-v2-volc.json`。

---

## 7. P0 面试题：必须熟练回答

### P0-1：你做的 Agentic RAG 改造到底是什么？

**参考回答：**

原来每个问题都会先检索知识库，再把上下文交给大模型。我将知识库搜索封装成 OpenAI-compatible Function Tool。第一次模型调用携带 Tool Schema，但不携带检索结果；模型可以直接输出通用答案，也可以返回 Tool Call。只有 Tool Call 出现时，后端才执行混合检索，把结果作为标准 Tool Message 追加到上下文，再进行第二次模型调用。核心价值是把检索从固定步骤变成按需动作。

### P0-2：为什么不继续每轮强制检索？

**参考回答：**

强制检索实现简单，而且不会漏掉文档问题，但通用问题也会付出 Embedding、ES、Rerank 和上下文 Token 成本。无关检索结果还可能干扰答案。Function Calling 可以让通用问题直接回答，减少无效检索；代价是新增了 Tool 误触发和漏触发风险，因此需要 Tool Description、边界样例和离线路由评测。

### P0-3：为什么称为 Agentic，而不是普通 RAG？

**参考回答：**

区别在于控制权。普通 RAG 的检索由固定程序决定；当前链路把搜索暴露为 Tool，由模型结合用户问题和历史决定是否调用，并生成结构化参数。不过我会明确它只是单工具、最多两阶段的 Agentic RAG，不是可以无限规划和多工具编排的通用 Agent。

### P0-4：第一次和第二次模型调用分别做什么？

**参考回答：**

第一次负责路由，也可以直接完成通用回答。若模型产生 Tool Call，后端执行搜索；第二次读取 assistant Tool Call 和 Tool Result，严格基于文档生成最终答案。通用路径只有一次模型调用，检索路径最多两次。

### P0-5：模型怎么知道什么时候调用搜索？

**参考回答：**

主要依据 System Prompt、Tool 名称、Tool Description、JSON Schema 和当前对话。Description 明确了正边界——上传文档、文件和知识库内容；也明确了负边界——常识、闲聊和数学计算。模型不是读取 Java 业务规则，而是在生成阶段选择普通文本或 Tool Call。

### P0-6：搜索 Query 从哪里来？

**参考回答：**

模型在 `search_knowledge_base` 的 arguments 中生成 `query`。因为第一次模型调用能看到对话历史，所以它可以把部分口语化或带指代的问题改写成更适合搜索的 Query。后端累积流式 arguments 并解析 JSON；解析失败或 Query 为空时回退到原始用户问题。

### P0-7：为什么还需要第二次 LLM，不能直接返回搜索结果？

**参考回答：**

搜索返回的是多个文档片段，不是直接面向用户的答案。第二次模型调用负责综合、去重、组织语言和引用来源，同时遵守“只能基于 Tool Result 回答”的规则。直接返回片段体验差，也无法处理多个片段之间的关系。

### P0-8：检索链路具体是什么？

**参考回答：**

首先使用 BGE-M3 生成 Query Embedding；Elasticsearch 分别进行 KNN 向量召回和 BM25 关键词召回，两路使用一致的权限过滤；Java 端采用 RRF 按排名融合，避免直接比较两种不可比的原始分数；最后可选 Cross-Encoder Rerank，失败时降级为 RRF 结果。

### P0-9：为什么使用 RRF？

**参考回答：**

BM25 分数和向量相似度不在同一量纲，直接加权需要归一化和参数调优。RRF 只使用候选在各路结果中的排名，公式类似 `Σ1/(k+rank)`，实现简单且对分数量纲鲁棒。代价是它丢失绝对置信度，需要通过离线 Recall、MRR 或 nDCG 评测参数。

### P0-10：路由准确率怎么测？

**参考回答：**

先固定 `SEARCH/DIRECT` 标签标准，再准备类别均衡、与 Prompt 示例隔离的人工审核测试集。评测使用生产相同的 Prompt 和 Tool Schema；出现 `search_knowledge_base` Tool Call 就预测为 SEARCH，否则为 DIRECT。除了总体 Accuracy，我还会报告 SEARCH Recall 和混淆矩阵，因为漏检索比单纯的总准确率更值得关注。

### P0-11：知识库问题 Recall 为什么重要？

**参考回答：**

Accuracy 会受类别比例影响。例如通用问题占 80% 时，模型全部选择 DIRECT 也能获得 80% Accuracy，但所有文档问题都失败。SEARCH Recall 衡量应该检索的问题中有多少真正触发检索，可以直接反映漏检索风险。

### P0-12：TTFT 怎么测，为什么会下降？

**参考回答：**

TTFT 从一轮请求开始，到流式响应中第一个非空答案内容到达为止，不把状态消息或空增量算进去。对照组每轮真实完成 bge-m3 Embedding、ES KNN + BM25、RRF 和 Cross-Encoder Rerank，再注入 top 10 上下文；Agentic 模式下通用问题直接进入第一次 LLM 的流式输出。本次同模型、同 Prompt、单并发、2 对预热，60 条样本交替执行两组顺序，P50 从 13,187 ms 降至 8,659 ms，降低 34.34%。

### P0-13：误触发和漏检索分别是什么？

**参考回答：**

误触发是通用问题错误调用搜索，例如“PDF 是什么格式”；影响延迟和上下文质量。漏检索是文档问题被模型直接回答，例如“根据我上传的合同，违约金是多少”；可能导致模型用通用知识编造。优化时需要同时看 SEARCH Precision 和 Recall，不能只追求更高 Tool 调用率。

### P0-14：为什么不直接写一个关键词 Router？

**参考回答：**

关键词规则对明确表达很有效，但很难处理多轮指代、口语化表达和难负例。例如“PDF 是什么”和“我上传的 PDF 说了什么”都包含 PDF，语义却不同。合理做法是让模型承担泛化路由，只用少量高精度规则兜住非常明确的文档请求，而不是用规则替代语义判断。

### P0-15：当前实现最大的限制是什么？

**参考回答：**

它只有一个搜索 Tool，流式解析只处理首个 Tool Call，搜索后第二次调用不再携带 Tools，因此不能根据结果自动改写 Query 或继续调用其他工具。当前 120 条路由集由模型辅助构造，尚待真人盲审；TTFT 每条只测 1 次，模型服务长尾仍明显。因此我把它定义为“单工具两阶段 Agentic RAG”，后续优先补人工复核和重复测量，再考虑完整 Tool Call 状态机与有界 Agent Loop。

---

## 8. P1 面试题：深挖与压力追问

### P1-1：模型可能同时输出 content 和 Tool Call，怎么处理？

**参考回答：**

协议层必须把“路由阶段文本”和“最终回答文本”分开处理。理想方案是在检测到 Tool Call 后丢弃或转成独立状态事件，不把它追加到最终 assistant 历史；否则用户可能先看到“我来搜索”，随后又看到正式回答，历史也会被过渡语污染。当前代码仍有进一步收口空间，这是我能明确指出的实现边界。

### P1-2：流式 Tool arguments 为什么不能按单个 chunk 解析 JSON？

**参考回答：**

arguments 可能按任意位置分片，单个 chunk 往往不是合法 JSON。必须按 Tool Call index 聚合 `id/name/arguments`，流完成后再统一反序列化和 Schema 校验。当前单 Tool 版本使用 `StringBuilder` 累积第一个 Tool Call；多工具版本需要按 index 维护状态机。

### P1-3：如果 Tool Call ID 缺失怎么办？

**参考回答：**

第二次请求要求 assistant Tool Call 的 `id` 与 Tool Result 的 `tool_call_id` 对应。供应商兼容实现偶尔可能缺失 ID，稳妥做法是生成本地唯一占位 ID，并保证两条消息一致；同时记录兼容性告警，避免第二次请求因消息协议不合法返回 400。

### P1-4：多轮指代怎么处理？

**参考回答：**

第一次模型调用会携带经过预算裁剪的对话历史，因此模型可以将“它的最大回撤呢”改写为包含主题的搜索 Query。历史过长时由 Token 预算和摘要控制。更严格的方案是只组合最近用户轮与当前 Query 做独立改写，但会增加一次调用；当前选择复用 Function Calling 的参数生成，优先控制延迟。

### P1-5：为什么不加 HyDE？

**参考回答：**

HyDE解决的是进入检索后的语义召回，不解决是否应该检索的路由问题，而且通常增加一次 LLM 调用，会抬高检索路径延迟。当前更高优先级是证明 BM25、KNN、RRF 和 Rerank 的基线收益。如果抽象问题或语义鸿沟问题仍明显漏召回，我会把 HyDE作为低相关或无结果时的条件式二次检索，而不是每次默认执行。

### P1-6：为什么不用独立的小模型做意图分类？

**参考回答：**

当前只有 SEARCH/DIRECT 两类，独立分类模型会新增一次服务调用、训练数据和版本维护，并可能与 Tool Schema 漂移。Function Calling 同时完成决策和搜索参数生成，工程链路更短。当工具数量增长、路由边界复杂且评测证明 Tool Calling 不稳定时，再考虑轻量分类器或 Intent Case RAG。

### P1-7：置信度阈值从哪里来？

**参考回答：**

当前 Function Calling API没有提供可以直接信任的 Tool 选择概率，因此不能让模型自报一个 confidence 就当真实置信度。若引入 Intent Case RAG，可以使用 Top-1 相似度、Top1/Top2 margin 和 Top-K 标签一致性作为可校准信号，阈值只在开发集上确定，再到独立测试集验证。当前主分支尚未实现这部分。

### P1-8：知识库没有结果怎么办？

**参考回答：**

Tool 返回明确的“未找到相关文档”，第二次模型调用只能说明当前知识库缺少相应信息，不能用通用知识伪装成文档结论。搜索服务失败则应区分 NO_RESULT 与 ERROR：无结果是正常业务状态，服务错误需要显式降级或提示重试，不能混为一谈。

### P1-9：如何防止知识库文档里的 Prompt Injection？

**参考回答：**

文档内容属于不可信数据。固定 System Prompt 必须明确 Tool Result 只能作为事实参考，不能覆盖系统规则；Tool Result 使用清晰边界，限制长度；权限在服务端检索层执行，不能由模型 arguments 指定 userId 或组织；输出引用只能来自真实返回的文档元数据。还需要用直接、间接、跨轮和跨租户注入样例做红队评测。

### P1-10：权限为什么必须在 KNN 和 BM25 两路都过滤？

**参考回答：**

RRF 会合并两路候选，只要其中一路遗漏权限过滤，越权文档就可能进入融合结果。权限不是生成阶段的提示词，而是检索层不变量；用户 ID、组织标签和公开范围由服务端上下文注入，不能相信模型传入的参数。

### P1-11：模型温度会不会让路由不稳定？

**参考回答：**

会。因此离线评测应该使用与生产一致的模型和参数，并对部分或全部用例重复运行，统计同一问题发生路由翻转的比例。若路由稳定性优先，可以降低温度，但因为第一次调用也承担通用回答，仍需观察回答质量。不能只报告一次运行的最好结果。

### P1-12：为什么用 P50/P95，而不是平均 TTFT？

**参考回答：**

模型 API 和检索服务延迟通常是长尾分布，平均值容易被少量异常请求扭曲。P50代表典型用户体验，P95代表长尾体验；同时保留原始样本和失败率，避免只选择最有利的分位数。若有并发压测，还应报告不同并发下的吞吐和 P99。

### P1-13：120 条测试集是不是太少？

**参考回答：**

对生产级质量证明肯定不够，但对当前两类路由的项目级离线回归可以作为第一版基线。关键是类别均衡、边界样例充分、标签经过审核且与 Prompt Case 隔离。后续应从匿名化线上 Bad Case 增量扩展，并给置信区间或重复运行稳定性。简历中应明确是“离线测试集”，不能包装成线上大规模实验。

### P1-14：如果增加更多 Tool，现有实现怎么演进？

**参考回答：**

首先把 Tool 定义、参数校验和执行抽成 Tool Registry；流式解析按 Tool Call index 聚合完整的 id、name 和 arguments；每轮执行 Tool 后继续携带 Tools 调用模型，形成有界 Agent Loop。同时设置最大迭代、最大调用数、超时、重复调用检测、Token 预算和结构化错误，避免无限循环与成本失控。

### P1-15：阿里云文章里的 Intent RAG 适合这个项目吗？

**参考回答：**

可以作为后续增强，但不是当前最高优先级。它适合工具或意图分支较多、口语化 Bad Case 稳定出现的场景：将人工审核的“历史、Query、预期 Tool 和 arguments”建立独立案例索引，在线召回 Top-K 动态 Few-shot。ShadowRAG 当前只有一个搜索 Tool，应该先建立路由基线；只有 A/B 证明 Intent RAG 能提升准确率且延迟可接受时再启用。

### P1-16：停止生成是否真的停止上游模型？

**参考回答：**

当前实现的停止标志主要阻止继续向 WebSocket 发送 chunk，没有保存并取消 WebClient 的上游订阅，因此不能保证停止计费；而且固定延迟清理标志存在时序问题。正确方案是保存 `Disposable` 或使用 Reactor cancellation，在停止时真正取消 HTTP 流，并在 complete/error/cancel 三个终态统一清理状态。

---

## 9. 面试官常见质疑及安全回答

### 质疑 1：“你这不就是加了一个 Function Calling 吗？”

**回答：**

Function Calling 本身并不复杂，工程难点是把它嵌入真实 RAG 链路：保持标准 Tool 消息协议、处理流式 arguments、维护历史与上下文预算、执行权限感知的混合检索、区分直接和检索两条流式路径，并建立 Tool Selection 与延迟评测。我不会把单 Tool 包装成复杂自主 Agent，但这项改造确实改变了系统的控制流和成本结构。

### 质疑 2：“你的准确率数字怎么来的？”

**安全回答模板：**

> 我先固定 SEARCH/DIRECT 标签标准，准备类别均衡、与 Prompt 示例隔离的人工审核数据；评测调用生产同一 Router，以是否产生 `search_knowledge_base` Tool Call 作为预测结果，输出混淆矩阵、Accuracy 和 SEARCH Recall。开发集只用于调 Prompt，最终测试集锁定。如果尚未跑出正式报告，我不会在简历中写具体数字。

### 质疑 3：“TTFT 是不是把‘正在搜索’算成首 Token 了？”

**回答：**

不能这么算。TTFT 的终点必须是用户可见的第一个真正答案 Token。搜索状态事件和第一次路由调用的过渡语应单独记录。对照实验需要同模型、同 Prompt、同预热和并发，只切换强制检索与按需检索。

### 质疑 4：“为什么 Hybrid 评测不一定比 BM25 好？”

**回答：**

混合检索不是天然必胜，效果取决于数据分布、标注质量、召回池、RRF 参数和 Rerank 候选范围。当前已有的小规模检索评测中 BM25 的 Hit 指标并不弱，因此正确做法是做消融和 Bad Case 分析，而不是因为架构更复杂就宣称一定提升。

### 质疑 5：“这是线上指标吗？”

**回答：**

如果只做了本地或离线测试，就明确说是离线指标；不要说线上 A/B、生产流量或用户规模。面试官通常更接受清晰边界，而不是无法核验的规模包装。

---

## 10. 两分钟 STAR 回答

### Situation

系统最初采用固定 RAG：所有问题都先进行 Embedding、ES 混合检索和 Rerank，再调用模型。对于闲聊、常识和编程问题，这些步骤没有价值，还增加 TTFT、Token 和上下文噪声。

### Task

目标是在不牺牲知识库问答能力和权限隔离的前提下，让系统只在需要文档事实时检索，同时继续支持 WebSocket 流式输出和现有会话历史。

### Action

我将知识库搜索抽象为 OpenAI-compatible Function Tool。第一次模型调用只携带 System Prompt、经过预算裁剪的历史、当前问题和 Tool Schema。模型无 Tool Call 时直接流式回答；产生 Tool Call 时，后端聚合流式 arguments、解析 Query，调用权限感知的 KNN + BM25、RRF 和 Rerank，再构造标准 assistant Tool Call 与 Tool Result 消息，发起第二次模型调用生成最终答案。Tool Description 同时定义文档问题的正边界和通用问题的负边界。

### Result

**当前事实版：**

> 在 120 条与 Prompt Case 隔离的独立路由测试集上，路由 Accuracy 为 100%（120/120）、知识库问题 Recall 为 100%（60/60）；相较同环境强制检索基线，通用问题 P50 TTFT 从 13,187 ms 降至 8,659 ms，下降 34.34%。测试集由模型辅助构造并已锁定，真人复核完成前不称为人工标注。

---

## 11. 两天速记计划

### 第一天：讲清楚代码和设计

1. 背熟第 1.3 节的 30 秒回答。
2. 不看文档画出第 2.2 节的两分支链路。
3. 阅读 `ChatHandler.processMessage()`、`executeToolAndRespond()` 和 `prepareToolResponseMessages()`。
4. 阅读 `DeepSeekClient.streamWithTools()` 与 `processToolChunk()`。
5. 能手写 assistant Tool Call 与 Tool Result 两条消息。
6. 口述 P0-1 到 P0-8，每题控制在 60 秒内。

### 第二天：指标、限制和压力追问

1. 背熟 Accuracy、SEARCH Recall 和 TTFT 的定义。
2. 口述 P0-9 到 P0-15。
3. 从 P1 中至少熟练准备：P1-1、P1-2、P1-5、P1-7、P1-9、P1-14、P1-16。
4. 做两遍两分钟 STAR 回答并录音。
5. 熟记 100% / 100% / 34.3% 的口径和证据路径，不把它解释成线上长期零错误。

---

## 12. 投递前最后检查

- [ ] 我把当前系统称为“单工具两阶段 Agentic RAG”，没有包装成多 Agent。
- [ ] 我能解释为什么通用问题只调用一次 LLM，检索问题最多调用两次。
- [ ] 我能画出 Function Calling、Tool 执行和第二次生成的消息序列。
- [ ] 我能解释 RRF、Rerank 和两路权限过滤。
- [ ] 我能区分路由 Accuracy、SEARCH Precision 和 SEARCH Recall。
- [ ] 我知道 TTFT 不包含搜索状态或过渡语。
- [ ] 我能解释 120/120、60/60、13,187 ms、8,659 ms 和 34.34% 的计算口径。
- [ ] 真人复核完成前，我不会把测试集称为“人工标注”。
- [ ] 面试官问到局限时，我主动说明单 Tool、无 Agent Loop、流式解析和真实取消等边界。

## 13. 代码导航

- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
  - `SEARCH_TOOL`
  - `processMessage()`
  - `buildMessagesForAgenticRAG()`
  - `executeToolAndRespond()`
  - `prepareToolResponseMessages()`
- `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java`
  - `streamWithTools()`
  - `buildToolsRequest()`
  - `processToolChunk()`
- `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`
  - `searchWithPermission()`
  - KNN/BM25/RRF/Rerank 链路
- `src/main/resources/application.yml`
  - `ai.prompt.rules`
  - `ai.generation`
  - `ai.context`
- `docs/Agentic_RAG_评估报告.md`
  - 当前两阶段流程、已识别 Bug 与演进建议
- `docs/superpowers/specs/2026-08-20-multi-tool-agent-intent-rag-design.md`
  - 多工具 Agent Loop 与 Intent RAG 的设计边界，当前不是已实现能力
