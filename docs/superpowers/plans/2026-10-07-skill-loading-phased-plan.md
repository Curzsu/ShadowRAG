# ShadowRAG Skill 加载层分阶段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 用户明确选择并行代理实施时，可采用 superpowers:subagent-driven-development。

**Goal:** 让 ShadowRAG 先向模型提供简短能力索引，再按需加载 Skill 正文和对应工具定义，并能分阶段验收、上线和回退。

**Architecture:** 后端工具注册目录与每次模型请求的工具开放集合分开。Skill 目录为实例级不可变快照，已加载指南和开放工具为单次用户任务状态；工具执行继续使用服务端认证身份。用现有 Java 知识库检索验证加载闭环，再完成目录发现和参考资料渐进读取。

**Tech Stack:** Java 17、Spring Boot 3.4.2、现有 DeepSeekClient / AgentLoopService / SSE、Jackson、TokenEstimator、JUnit 5 / Mockito / MockModelSseServer。

---

日期：2026-10-07。状态：分阶段实施与验收计划，尚未实现或运行新增测试。

本计划细化 [Skill 渐进式加载设计](../specs/2026-10-06-progressive-skill-loading-design.md)。按最新范围，只完成 Skill 加载层的四个阶段，使用现有知识库工具验收；MCP、Firecrawl 联网和 CLI 执行器均不属于本计划。旧设计中的联网扩展内容作为历史备选保留，不作为开工任务或验收依赖。

## 1. 阶段总览

| 阶段 | 交付 | 独立验收结果 | 依赖 |
| --- | --- | --- | --- |
| 1：工具接口 | 通用注册与分发，保留旧知识库模式 | 不启用 Skill 时现有聊天与权限检索行为保持一致 | 当前代码 |
| 2：Skill 目录 | 配置、解析、快照、可用性检查、简短索引 | 本地文件变成有界、可诊断的能力目录；尚不接入模型请求 | 阶段 1 |
| 3：最小加载闭环 | load_skill、请求内开放集合、原子激活、上下文保护 | 首次请求无知识库 Schema，加载后下一次才出现并能执行检索 | 阶段 1、2 |
| 4：目录与资料扩展 | search_skills、参考读取、目录超限降级、运行诊断 | Skill 增多后不全量注入，参考内容按需展开 | 阶段 3 |

阶段 3 是第一个可以供真实用户使用的 Skill 版本；阶段 4 完成原设计的 Skill 核心范围。各阶段可以单独提交与评审，通过当前阶段验收后再推进下一阶段。不用预先分配虚构工时，实施时记录实际工作量。

## 2. 共同约束

- 默认 `skills.enabled=false`。关闭时不扫描 Skill 目录、不注入简介或正文，保留原有知识库工具、提示词及 3 轮 / 6 次默认预算。
- 四个阶段只加载管理员维护的 Skill，不增加用户上传、租户私有目录、数据库或管理页面。启用/禁用属于实例级管理；不宣称实现了 Skill 的用户角色授权。
- 知识库身份只取 `ChatCommand.username()`，Skill 和模型不能指定另一位用户。
- 后端注册工具不等于模型可见；模型猜出的隐藏工具不能执行。
- 新用户提问创建新的加载状态。已加载 Skill、参考文本和工具开放范围不跨请求共享，不存入 MySQL/Redis 的真实聊天历史。
- 只持久化真实用户问题和最终答案；模型请求中的临时指南、调用记录和资料不伪装成真实用户消息保存。
- 保留现有 SSE 事件类型和 callId 配对，不提前重写 DeepSeekClient 或更换 LLM 框架。
- 前端已有未提交改动，实施时先检查状态；需要修改动态工具展示时在当前改动基础上协调，不能覆盖现有工作。

## 3. 文件职责与阶段归属

下列新增路径为规划内容，当前尚不存在。后续逐阶段实现，不一次性创建空框架。

| 路径 | 阶段 | 职责 |
| --- | --- | --- |
| `E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/tool/` | 1、3、4 | 工具定义、执行上下文、结果、注册表、暴露快照与 Skill 工具 |
| `E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/config/SkillProperties.java` | 2 | 目录、禁用项、工具组和预算配置 |
| `E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/skill/` | 2、3、4 | 文档解析、目录快照、索引、任务状态、激活与发现 |
| `E:/Curzsu/ShadowRAG/skills/knowledge-search/SKILL.md` | 2 | 知识库操作指引与工具组关联 |
| `E:/Curzsu/ShadowRAG/skills/knowledge-search/references/comparison-template.md` | 4 | 按需加载的资料比较指引 |
| `E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/AgentLoopService.java` | 1、3、4 | 通用分发、每轮实际工具集合、指南提交和调用预算 |
| `E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java` | 3 | 原始问题与历史准备，移除固定知识库 Schema 的提前预算 |
| `E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ContextBudgetService.java` | 3 | 用显式任务保护范围进行上下文裁剪 |
| `E:/Curzsu/ShadowRAG/src/main/resources/application.yml` | 2、3、4 | 默认关闭配置，启用时的提示词、预算与工具组 |

## 4. 阶段 1：工具注册与分发，保持现有行为

**目标：** 让 Agent 不再在代码里只认识 KnowledgeBaseSearchTool，但模型仍只获得当前知识库能力。此阶段不提供 `load_skill`。

### Task 1.1：提取最小工具契约

- [ ] 在 `tool/` 创建 `ToolDefinition`、`ToolExecutor`、`ToolOutcome`、`ToolExecutionContext`、`ToolRegistry` 和 `KnowledgeBaseToolExecutor`，职责沿用原设计第 12 节。阶段 1 的上下文只携带认证命令、请求资源与来源状态；Skill 状态在阶段 3 增加，不提前引入 MCP 或 CLI 类型。
- [ ] 知识库适配器调用现有 `KnowledgeBaseSearchTool.execute`，保留正文裁剪、文件来源、异常脱敏与 `executed` 统计含义，不重新实现检索。
- [ ] 注册表拒绝重名工具，未知名称返回受控错误；工具定义只从后端执行器产生。
- [ ] 先写 `E:/Curzsu/ShadowRAG/src/test/java/com/yizhaoqi/smartpai/tool/ToolRegistryTest.java`，覆盖已知名称分发、重名注册拒绝、未知工具不执行、认证身份传递。

### Task 1.2：Agent 改用通用分发

- [ ] 修改 `AgentLoopService`，通过注册表取得知识库工具定义并执行调用。
- [ ] `tool_progress.data.tool` 使用实际工具名，继续保留 roundId、callId、started/finished。
- [ ] 重复调用键允许各执行器保留原有语义。关闭 Skill 的旧模式仍保留知识库 query 归一化和原预算行为；不要在本阶段改变现有测试期望。
- [ ] 扩展 `AgentLoopServiceTest` 的请求捕获与分发断言，并通过下面的回归命令。

验证命令：

```powershell
mvn '-Dtest=ToolRegistryTest,AgentLoopServiceTest,KnowledgeBaseSearchToolTest,ChatHandlerStreamingTest' test
```

**完成标准：** 原来能完成的知识库多轮检索、取消、收尾和来源输出仍能完成；未新增 Skill 内容或联网工具；模型请求中的知识库工具定义保持兼容。预期上述测试全部通过，具体数量由实现后报告。

**提交范围：** 通用工具契约、知识库适配器、Agent 分发和相关测试。完成后形成一个独立提交。

## 5. 阶段 2：Skill 目录与快照，先不影响在线请求

**目标：** 能把本地 SKILL.md 解析成可用能力目录，并生成预算内的简介索引。目录在启动时加载，模型接入留到阶段 3。

### Task 2.1：配置与文档解析

- [ ] 新建 `SkillProperties`、`SkillDocumentParser` 和不可变 Skill 文档模型。配置默认关闭；关闭时不扫描目录，也不因缺失目录报错。
- [ ] 使用安全 YAML 解析器读取 name、description、metadata；先检查 Maven 已有依赖，再决定是否需要显式添加依赖。禁止重复键、对象标签、过深嵌套和超限输入。
- [ ] 实现原设计第 6 节的名称、长度、目录一致性和工具组约束；未知工具组不能给模型授予工具。
- [ ] 将下面的内容写入知识库示例，不把查询策略复制到每个工具描述里。

```markdown
---
name: knowledge-search
description: 查询已上传文件、内部项目或业务资料，并保留文件来源时使用。
metadata:
  shadowrag-toolsets: knowledge-base
---

# 知识库检索

提取问题的核心实体与主题，调用 search_knowledge_base。
资料不足时改写或拆分查询；只使用实际返回的资料。
保留工具提供的文件来源编号和名称，未命中时明确说明。
不把内部资料或内部查询自动发送给互联网工具。
```

### Task 2.2：目录扫描、可用性与简介索引

- [ ] 新建 `SkillRegistry`、`SkillCatalogSnapshot` 和 `SkillIndexFormatter`。按配置顺序扫描，后一个目录整体覆盖同名 Skill，不拼接不同来源。
- [ ] 在启动时固定正文与允许的参考文件清单；验证解析后的真实路径，拒绝目录外链接和 junction。参考正文读取能力留到阶段 4。
- [ ] 应用禁用列表、工具组是否存在和执行器是否可用的检查；不可用项不进入可用索引，并留下脱敏诊断。
- [ ] 索引只包含加载规则、name、description，不包含正文、工具 Schema、服务端路径或凭证。
- [ ] 阶段 2、3 暂只支持能完整放进索引预算的小目录。目录超限时禁用该次目录快照并报告原因，禁止截断后悄悄丢条目；阶段 4 加入 search_skills 后再改成发现模式。
- [ ] 新建 `SkillDocumentParserTest`、`SkillRegistryTest`、`SkillIndexFormatterTest`，使用临时目录验证合法文档、重复键、中文描述、禁用项、未知工具组、目录覆盖、超限与路径越界。Windows 链接测试若环境无法创建链接，应记录环境限制并在允许创建的测试环境补验，不能称已覆盖。

配置形状：

```yaml
skills:
  enabled: false
  directories: [./skills]
  disabled: []
  max-index-tokens: 1500
  max-body-tokens: 3000
  max-reference-tokens: 1500
  max-guidance-tokens-per-turn: 9000
  max-loaded-per-turn: 3
  max-active-tools: 8
  max-search-results: 5
  max-file-bytes: 65536
  max-reference-files-per-skill: 10
  max-snapshot-bytes: 33554432
  toolsets:
    knowledge-base:
      tools: [search_knowledge_base]
```

验证命令：

```powershell
mvn '-Dtest=SkillDocumentParserTest,SkillRegistryTest,SkillIndexFormatterTest,AgentLoopServiceTest' test
```

**完成标准：** 能从示例文件得到可用快照和完整简介；非法文件被跳过并给出原因；关闭配置时在线请求与阶段 1 一致。阶段 2 的目录组件尚不向模型发送内容，可独立验收后保持关闭。

**提交范围：** 配置、文档模型、目录组件、知识库示例和解析测试。

## 6. 阶段 3：最小渐进加载闭环，知识库作为验证对象

**目标：** 第一次真正启用 Skill，让模型先加载指南，再获得业务工具。预算、请求隔离和执行许可随闭环一同完成，不留到上线以后补。

### Task 3.1：任务状态与工具暴露快照

- [ ] 新建请求内 `TurnSkillState`，固定目录快照，记录已加载 Skill 的名称/内容哈希、开放工具、指南 token、当前任务保护尾部条数和调用次数。
- [ ] 每次模型请求创建不可变 `ToolExposureSnapshot`，保存这次真正发送的定义和可执行映射。执行依据这个快照，不直接依据共享注册表或已改变的下一轮状态。
- [ ] 修改 `ContextBudgetService` 的 Agent 路径，使用显式 `protectedTailCount`。初始为真实当前问题的 1 条消息；统一追加当前任务消息时递增，禁止按“最后一条 user”重新判断任务边界。
- [ ] 修改 `ChatHandler`，移除固定知识库 Schema 的提前预算；每次发给模型前由 Agent 使用实际可见定义做最终预算，保证原始问题和当前任务配对不能被删除。
- [ ] 在 `SkillContextBudgetTest` 中验证低窗口下优先删旧历史，并保留原始问题、完整 assistant/tool 配对、指南和来源清单；不能容纳新增正文时不激活工具。

### Task 3.2：原子加载与下一次请求生效

- [ ] 新建 `LoadSkillTool` 和类型化 `GuidanceProposal` / `SkillActivation`。参数只接受精确 name，不接受工具名单、文件路径或用户身份。
- [ ] `load_skill` 先计算候选正文、工具集合和完整消息预算；全部成功后才提交状态，失败不残留指南或权限。普通工具返回的文本和 JSON 不能生成激活附加项。
- [ ] 先为整批模型调用写入 assistant 消息，再为每项补齐 tool 结果，最后追加本批成功的指南。指南注明来自管理员并服从 system 与真实用户任务。
- [ ] 同任务重复加载返回 `already_loaded`，不重复正文；新用户任务重新加载。相同 Spring 单例服务上的两个请求不能共享 loadedSkills。
- [ ] 阶段 3 的初始 tools 暂只有 `load_skill`，小目录简介已经完整列出名称。`search_skills` 在阶段 4 加入；业务工具只在成功加载后的下一次模型请求出现。
- [ ] 同一批 `load_skill` 加隐藏业务工具：加载可成功，业务调用返回 `TOOL_NOT_EXPOSED`，不能立即执行。相同批的所有调用都按发起它们的暴露快照检查。
- [ ] 启用模式的调用预算统计所有尝试，包括加载、重复、错误和被拒绝项。启用时显式配置 6 轮 / 12 次建议预算；关闭模式保留原 3 轮 / 6 次统计行为。
- [ ] 配置启用模式的提示词：先匹配并加载 Skill，再调用开放业务工具；纯计算/现成文本处理可直接回答。原“事实问题直接先知识库检索”的提示词只用于关闭模式。

成功加载结果形状：

```json
{
  "ok": true,
  "skill": "knowledge-search",
  "status": "loaded",
  "availableTools": ["search_knowledge_base"],
  "effectiveFrom": "next_model_request"
}
```

### Task 3.3：抓取真实请求验证闭环

- [ ] 新建 `ProgressiveSkillAgentTest`，复用现有 `MockModelSseServer` 捕获 messages/tools；写测试失败后再接入 Agent，不只测试 formatter 字符串。
- [ ] 用下表规定的脚本响应逐项验证；同批隐藏调用和状态隔离必须检查实际检索执行次数。
- [ ] 在 `ChatHandlerHistoryTest` 增加持久化断言：只保存真实问题和最终答案，临时指南不被写入。
- [ ] 验证原 SSE `round_end`、`tool_progress` 字段继续可消费；动态工具标签不能全部显示成知识库检索。如现有前端无法正确处理，协调已有改动补齐展示及其相关测试后再上线。

| 模型响应脚本 | 必须观察到的真实请求/执行结果 |
| --- | --- |
| load knowledge-search → search_knowledge_base → 最终回答 | 请求 1 只有 load_skill 且无正文；请求 2 有正文和知识库 Schema；请求 3 有检索结果和来源；实际检索身份为当前认证用户 |
| 直接猜 search_knowledge_base → 最终回答 | 未开放的工具返回错误，实际检索为 0 次 |
| 同批 load knowledge-search + search_knowledge_base → 下一轮再检索 → 回答 | 同批检索为 0 次；下一轮取得真实定义后才允许检索 |
| 两次 load knowledge-search → 回答 | 指南正文只有 1 份，第二次返回 already_loaded，两个调用结果仍完整配对 |
| 超限正文加载 → 回答 | 返回明确错误，工具集合仍为加载前集合，正文不进入请求 |
| 请求 A 加载后，请求 B 开始 | B 首次请求仍只有基础加载工具，没有 A 的正文或开放状态 |
| 加载后取消生成 | 不发起后续模型/工具调用，指南不持久化 |
| skills 关闭 | 模型继续直接看到原知识库 Schema，没有 Skill 内容，预算行为保持旧模式 |

验证命令：

```powershell
mvn '-Dtest=ProgressiveSkillAgentTest,SkillContextBudgetTest,ContextBudgetAgentTest,ContextBudgetServiceTest,AgentLoopServiceTest,ChatHandlerHistoryTest,ChatHandlerStreamingTest' test
```

**完成标准：** 表中所有行为通过自动化验证，原知识库功能及鉴权正常；一次问题内指南和工具动态加载，新问题恢复初始状态。完成本阶段即可在小目录场景启用 Skill，回退只需关闭开关。

**提交范围：** 可按“任务保护与暴露快照”“加载闭环与开关”“端到端验收”分为三个可审查提交，阶段全部完成后才作为可上线交付。

## 7. 阶段 4：目录发现与参考资料渐进读取

**目标：** Skill 数量增加时保持上下文有界，并完成参考文件的第三层展开。

### Task 4.1：search_skills 与大目录降级

- [ ] 新建 `SkillDiscoveryService` 和 `SearchSkillsTool`。query 接受 1–500 字符的非空字符串，拒绝未知参数，只搜索当前配置可用的 name、description、metadata.tags。
- [ ] 按原设计实现本地词法排序：NFKC、ASCII 词/中文二元组，name/tags/description 权重 3/2/1，精确名称优先，同分按名称，最多返回 5 个完整候选并受返回预算约束。
- [ ] 初始工具固定为 `search_skills`、`load_skill`。小目录仍发完整简介；大目录只发发现规则及存在且可用的 knowledge-search 入口简介，不发其他条目的 Schema 或正文。
- [ ] 空目录、无命中、入口被禁用和预算不足都给出明确状态；不按字母顺序随意截取目录。即使不在短索引，合法精确名称仍可加载。
- [ ] 新建 `SkillDiscoveryServiceTest` 并扩展 `SkillIndexFormatterTest`，覆盖中文问题、精确名称、禁用项、100 个条目的目录、空候选与完整条目预算。

### Task 4.2：参考资料第三层展开

- [ ] 新建 `ReadSkillReferenceTool` 和 `ReferenceActivation`，仅已加载 Skill 的快照清单可读；只接收 name 和 path，拒绝未知字段、绝对路径、`..` 和超长路径。
- [ ] 为知识库 Skill 加入 `references/comparison-template.md`，正文说明比较任务可按需读取；基础请求和刚加载 Skill 时均不自动带入参考正文。
- [ ] 校验参考单份和当前任务总指南预算，成功后通过类型化指南附加项注入；同任务同参考只注入一次，失败不残留状态。
- [ ] 引入参考工具后的实际工具总数也纳入 max-active-tools。只在当前已加载 Skill 有可读参考文件时开放该工具。
- [ ] 新建 `SkillReferenceToolTest`，验证未加载拒绝、路径越界、清单外拒绝、超限拒绝、重复读取，以及下一次模型请求才出现参考内容。

调用形状：

```json
{"name":"knowledge-search","path":"references/comparison-template.md"}
```

### Task 4.3：诊断与规模验收

- [ ] 日志记录目录可用/禁用/不可用数量，每轮暴露工具名、加载 Skill 名称、估算 Schema token、指南 token、拒绝原因与耗时。不记录凭证、完整私人问题或完整检索正文。
- [ ] 增加 100 个 Skill/工具的固定测试目录；断言首次请求只包含两个基础工具，加载一个 Skill 后只出现绑定组的定义，不把目录全部发送。
- [ ] 扩展 `ProgressiveSkillAgentTest`，覆盖 search_skills → load_skill → read_skill_reference/业务工具 → 回答，保留当前任务和调用配对。
- [ ] 核对原设计核心验收清单，确认路径限制、原子提交、总预算、请求隔离和关闭模式均已覆盖，再将本阶段标记完成。

验证命令：

```powershell
mvn '-Dtest=SkillDiscoveryServiceTest,SkillIndexFormatterTest,SkillReferenceToolTest,ProgressiveSkillAgentTest,SkillContextBudgetTest,SkillRegistryTest,AgentLoopServiceTest,ChatHandlerHistoryTest' test
```

**完成标准：** 小目录和大目录均可发现与加载；未选中的 Skill 正文及工具定义不进入模型请求；参考正文只在读取后出现。此时 Skill 加载层的核心范围全部完成。

**提交范围：** 目录发现、参考读取、规模/诊断验收各自独立提交。

## 8. 每阶段的交付记录

每个阶段评审时填写以下信息，不把未运行的检查写为通过：

| 项目 | 应提供的证据 |
| --- | --- |
| 代码范围 | 本阶段文件、独立提交和实际实现的行为 |
| 模型可见内容 | 捕获的首个请求与加载后请求的 tools 名称和正文有无，不公开私密内容 |
| 验证 | 实际运行的命令、通过/失败结果、环境限制 |
| 权限与隔离 | 当前身份来源、隐藏调用拒绝、两请求状态互不影响的测试 |
| 回退 | skills 开关关闭后的实际回归结果 |
| 下一阶段 | 是否满足依赖与准入标准，尚未覆盖的范围 |

实施前先检查工作区和适用仓库指令；不得把已有用户改动纳入自己的提交。每个任务遵循写失败测试、运行确认、实现、运行验证、形成独立提交的顺序。最终覆盖核心范围后运行 `mvn test`；前端只有在本阶段实际修改时，按其当前 package.json 的相关脚本验证。

## 9. 自检与开工顺序

- [x] 四个阶段只覆盖 Skill 加载层，不依赖联网或外部工具协议。
- [x] 每阶段定义交付、依赖、修改范围、验证命令和完成标准。
- [x] 原子激活、原始问题保护、同批快照和身份校验随阶段 3 交付，不能延后成为上线欠账。
- [x] 明确新用户任务重置加载状态，临时指南不保存到聊天历史。
- [x] 大目录在阶段 3 有显式上限，阶段 4 才切换为 search_skills 模式，不静默丢失候选。
- [x] 旧设计中的联网扩展被标记为历史备选，不作为本轮实施或验收范围。
- [x] 文档中的测试类与配置为未来实施目标，未声称现有代码已具备或检查已通过。

建议下一次直接实施阶段 1，完成其回归后再进入阶段 2。阶段 3 验收完成后可先试用知识库 Skill；阶段 4 验收完成后，本计划交付结束。
