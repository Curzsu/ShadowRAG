# ShadowRAG Skill 接入层开发文档：优先开发效率

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 只有用户明确要求并行代理时，才使用 superpowers:subagent-driven-development。

**Goal:** 用户通过自然语言或 `/skill:name 参数` 使用管理员部署的任务指南；模型按需获得完整指南，继续使用现有知识库工具完成任务。

**Architecture:** 复用 ChatHandler、AgentLoopService、DeepSeekClient、上下文预算和 SSE。管理员目录在启动时解析为不可变 Skill 快照；初始只广播简介，自动加载和显式命令共用激活校验。知识库工具始终可用，指南和加载状态只存在于当前用户请求。

**Tech Stack:** Java 17、Spring Boot 3.4.2、Jackson、安全 YAML 解析、现有 TokenEstimator、JUnit 5 / Mockito / MockModelSseServer、Vue 3。

**Spec:** 本文件第 1–8 节是本轮设计基线，第 9 节是开发任务，第 10–11 节是验收和交付要求。依据当前对话中已确认的 pi 两阶段接入方案编写，可以独立交给开发者使用。

日期：2026-10-07。状态：开发文档，功能尚未实现，本文中的新增接口、配置和测试均为待开发内容。

2026-10-07 审查更新：精简内容 hash、重复指南 token 状态、新预算重载、独立加载轮次豁免及显式命令的模拟工具进度。以本文件更新后的接口和任务为准；上下文完整性、请求隔离和真实调用验收保持要求。

## Global Constraints

- 管理员维护实例级 Skill；用户使用技能，不提供个人上传、编辑、安装或管理页面。
- 首版使用一个配置目录，默认关闭；修改文件或启停配置后重启生效。
- `SKILL.md` 使用 YAML frontmatter + Markdown；name、description 必填，正文完整加载。
- `search_knowledge_base` 在所有普通工具轮始终可用，Skill 不授予工具或知识库权限。
- 每请求最多成功加载 1 个 Skill、最多进行 1 次自动加载尝试；显式命令预加载后不再提供自动加载工具。
- 工具批次统一计数。自动加载可用的请求将现有工具轮上限增加 1，默认从 3 到 4；显式预加载、关闭功能或无可用技能时仍为 3。实际检索调用上限仍为 6，总超时不变。
- Skill 正文单份最多 3000 个估算 token；完整目录最多 1500 个估算 token；单文件最多 65536 字节。
- 保留一个 system 消息；正文为带用途标识的临时 user 指南，服从系统规则和原始用户任务。
- 明确记录当前任务受保护的尾部范围，不以最后一条 user 消息重新确定任务边界。
- 直接复用 ContextBudgetService 已有的四参数 fit，无需新增预算接口或修改预算算法。
- 不截断指南，不将指南写入 MySQL/Redis 的真实聊天历史，不跨请求共享加载状态。
- 保留现有 SSE 事件类型和 started/finished 状态，前后端一起扩展工具名称及错误码白名单。
- 不改造 DeepSeekClient 协议、不接入 MCP / CLI、不执行 scripts、不读取 references、不构建通用工具框架。
- 开工前检查工作区已有修改，尤其是前端消息展示和旧 Skill 文档；不得覆盖或混入无关改动。

## Review Focus

以下五个条件必须各有对应测试，任务中已标明归属：

1. CRLF、UTF-8 BOM、中文及 YAML `|` / `>` 描述，必须正确分离 frontmatter 与正文。归属任务 1。
2. 加载指南后出现第二条 user 消息，原始问题与前面的 assistant/tool 配对仍受保护。归属任务 2。
3. 同批返回 load_skill 与知识库检索，先完整配对全部 tool 结果，再追加指南；指南从下一次模型请求生效。归属任务 3。
4. 激活预算不足或用户取消，不能留下“已加载”的状态或发送成功确认。归属任务 2、3。
5. load_skill 进度或新 Skill 错误码经过真实 SSE，不能被前端当成非法协议或显示成知识库检索。归属任务 4。

---

## 1. 为什么选这条开发路线

Skill 是任务方法，知识库是事实依据，工具负责执行。首版的收益是复用比较、总结等方法，并按需发送正文。

当前后端只有一个知识库业务工具。把它隐藏到 Skill 加载之后，节省不了多少 Schema，却会让普通事实问答增加一次模型往返。因此保留知识库工具直接可用，仅让技能正文渐进展开。

| 决策 | 开发效率上的原因 |
| --- | --- |
| 一个管理员文件目录 | 不需要数据库迁移、上传接口、用户归属或管理页面 |
| 两个示例 Skill | 足以验证自动选择、显式调用与回答效果 |
| 精确工具名分支分发 | 当前只有 search_knowledge_base / load_skill，无需先提取十几个工具框架类 |
| 启动时缓存正文 | 请求期间无文件 IO，内容版本固定；正文进入模型上下文仍然按需 |
| 每请求一个 Skill | 简化去重、预算、失败恢复和任务边界 |
| 一个受控目录 | 不做多目录覆盖、全局用户目录或热更新 |
| 保留现有 HTTP、SSE 和持久化 | 改动集中在加载和提示词，不重写稳定链路 |
| 显式命令由服务端展开 | 所有客户端共用逻辑，用户明确选择时少一次模型加载往返 |

本次审查去掉了以下首版用不到的机制：正文 SHA-256 与版本记录、单独累计指南 token、额外 fitAgent 重载、技能数量和目录 token 的双重上限、加载轮次豁免规则、显式命令的模拟 tool_progress。五个生产文件保留清晰职责，不再加框架类。候选激活只是本地变量与一次预算校验，不做事务、锁或通用状态机。

本文件是本轮的实施范围。已有的 [渐进式加载与动态工具开放设计](/E:/Curzsu/ShadowRAG/docs/superpowers/specs/2026-10-06-progressive-skill-loading-design.md) 和 [四阶段计划](/E:/Curzsu/ShadowRAG/docs/superpowers/plans/2026-10-07-skill-loading-phased-plan.md) 保留作为后续扩展参考。不要把其中的动态工具组、search_skills、参考读取或 CLI 要求同时加入本轮。

这里的 ROI 是范围与依赖分析，不是已测得的工时或性能结论。开发后记录实际改动量、加载次数和延迟。

## 2. 产品行为

### 2.1 自动加载

用户输入“比较 A、B 两份报告的部署方案”：

1. 首次模型请求包含技能 name / description 与 load_skill 定义，不包含技能正文。
2. 模型选择 `load_skill({"name":"document-compare"})`。
3. 后端检查技能和上下文预算，完整注入指南。
4. 下一次模型请求同时看到指南和知识库工具，继续检索、回答。

纯寒暄、纯计算、没有匹配技能的普通任务可以直接走原有流程。不要要求每个知识库问题都先加载技能。

### 2.2 显式加载

用户输入 `/skill:document-compare 比较 A 和 B`：

1. 服务端解析命令，查找同一份目录快照。
2. 首次模型请求中的当前 user 消息替换为完整指南 + 原始任务参数。
3. 不让模型再次调用 load_skill；直接开始检索或回答。
4. 持久化的用户消息仍然是原始 `/skill:...` 输入。

参数为空也允许；模型根据技能和现有上下文判断是否需要澄清，不由后端编造任务。只识别整条消息开头的命令，正文中的 `/skill:` 不触发展开。

### 2.3 生效范围

指南只对当前这次用户提问生效。下一次请求重新选择或显式指定；“继续”不保证自动沿用上次技能。历史消息中的旧命令不重新展开。普通用户粘贴 SKILL.md 只作为聊天文本，不注册技能。

## 3. 文件与配置

首版不做 classpath 提取器。仓库提供两份默认文件，部署时将整个 `skills` 目录随应用分发或挂载；容器配置服务端绝对路径。文件不是 Java 常量，管理员能修改或新增技能，重启生效。

```text
E:/Curzsu/ShadowRAG/skills/
  document-compare/SKILL.md
  evidence-summary/SKILL.md
```

新增配置形状，当前应用尚不识别：

```yaml
skills:
  enabled: false
  directory: ${SHADOWRAG_SKILLS_DIR:./skills}
  disabled: []
  max-index-tokens: 1500
  max-body-tokens: 3000
  max-file-bytes: 65536
```

`SkillProperties` 对应 Java 属性为 enabled、directory、disabled、maxIndexTokens、maxBodyTokens、maxFileBytes。数值必须为正；directory 非空。disabled 是唯一禁用来源。加载次数 1 和加载数量 1 作为首版固定规则，不添加额外可配置策略。

### 3.1 目录与启动规则

- enabled=false：不扫描、不发简介、不暴露 load_skill；原检索行为和预算保持兼容。
- enabled=true：仅扫描配置目录的直接子目录中的 `SKILL.md`，不递归扫描工程，不读取其他 Markdown。
- 使用 UTF-8 严格解码；检查读取大小上限；兼容文件开头的 BOM 和 LF / CRLF。
- 解析真实路径，技能文件必须仍位于其技能目录，技能目录必须位于配置根目录；不跟随越界 symlink / Windows junction。
- 安全 YAML 解析，拒绝重复键、自定义对象标签和别名展开；限制嵌套深度为 20。
- name 为 1–64 个小写 ASCII 字母、数字或连字符，无首尾或连续连字符，必须与父目录名称一致。
- description 为非空字符串，最长 1024 字符；name / description 不从目录名或正文猜测。
- 正文去除首尾空白后非空，按 TokenEstimator 检查 3000 token 限额。首版重启更新，不计算内容 hash 或实现版本回放。
- 标准可选字段允许存在；metadata 如存在必须是字符串映射。compatibility / allowed-tools / 未知字段均不执行安装、授权或联网。
- 非法文件跳过并诊断。目录缺失或没有有效项时知识库仍可用；自动技能目录和工具一起省略。
- 禁用项保留元数据及禁用诊断以便显式调用返回 SKILL_DISABLED，但不进入自动目录。
- 按 name 排序，以生成稳定快照和提示词。同名冲突不能静默覆盖，拒绝冲突项并记录诊断。
- 检查完整可用目录的 1500 token 预算。超过上限，技能快照设为不可用，记录原因；不静默截掉目录后半段。普通 RAG 仍可运行。首版目录由管理员维护，不再增加独立的技能数量限制或大目录搜索策略。
- 启动加载完成后快照不可变，所有请求只读。请求中不读取变化中的文件，不缓存用户的已加载状态。

安全 YAML 实现优先使用项目已有 SnakeYAML，必要时在 pom.xml 显式声明由 Spring Boot 管理版本的依赖。使用基础类型安全构造方式，禁止把 frontmatter 反序列化为任意 Java 对象；不手写完整 YAML 解析器。

### 3.2 两份默认指南

创建 [document-compare/SKILL.md](/E:/Curzsu/ShadowRAG/skills/document-compare/SKILL.md)：

```markdown
---
name: document-compare
description: 用户要求比较两份或多份知识库资料、找出异同或判断口径差异，并需要事实依据与来源时使用。
---

# 文档比较

1. 明确比较对象与维度；缺少对象时先澄清。
2. 分别检索各对象，查询包含对象和比较主题。资料不足时调整查询。
3. 区分明确差异、口径不同和信息缺失，不把“资料未提及”写成“不存在”。
4. 先给主要结论，再用“比较维度、对象 A、对象 B、依据”表格呈现结果。
5. 每项事实保留 search_knowledge_base 提供的来源编号与文件名。
6. 最后列出尚无法确认的部分，不编造事实、来源或完整阅读声明。
```

创建 [evidence-summary/SKILL.md](/E:/Curzsu/ShadowRAG/skills/evidence-summary/SKILL.md)：

```markdown
---
name: evidence-summary
description: 用户要求总结知识库文档、内部项目或报告，需要提炼结论、整理证据并保留来源时使用；只改写用户已提供文本时通常无需加载。
---

# 带证据的资料总结

1. 确认用户指定的主题、资料和总结范围。
2. 检索相关资料，只整理与任务相关且实际返回的内容。
3. 按“主要结论、支持证据、未覆盖的信息”组织回答。
4. 将资料明确陈述的事实与用户要求的分析区分，分析说明依据。
5. 保留工具提供的来源编号与文件名。资料不足或只获得片段时明确说明。
6. 不根据模型记忆补齐资料未提及的数字、时间、身份或技术结论。
```

两份文件保持短小、独立，不引用本轮尚不支持的 references 或 scripts。

## 4. 最小组件和接口

建议只新增以下五个生产文件，不提前创建 ToolRegistry / ToolExposureSnapshot / 插件执行器框架。签名是本轮契约，不代表已有实现。

| 新文件 | 职责与接口 |
| --- | --- |
| [SkillProperties.java](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/config/SkillProperties.java) | 配置、默认值、启动验证 |
| [SkillDocument.java](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/skill/SkillDocument.java) | 不可变 record：name、description、body；不发送服务端真实路径 |
| [SkillDocumentParser.java](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/skill/SkillDocumentParser.java) | `SkillDocument parse(Path skillFile)`；超限 / 解析 / 字段错误转换成受控诊断 |
| [SkillCatalog.java](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/skill/SkillCatalog.java) | 启动扫描、完整简介格式化和只读查询：`Optional<SkillDocument> find(String name)`、`List<SkillDocument> available()`、`boolean isDisabled(String name)`、`boolean isReady()`、`String prompt()` |
| [SkillRuntime.java](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/skill/SkillRuntime.java) | 简介、命令、候选激活、加载 Schema；内部嵌套 record / state，避免为每个 DTO 新建文件 |

`SkillRuntime` 的接口：

```java
String catalogPrompt();
Optional<ExplicitCommand> parseCommand(String originalInput);
List<Map<String, Object>> loadToolDefinitions(TurnState state);
Activation prepareActivation(String name, TurnState state);
LoadOutcome prepareLoad(ModelToolCall call, TurnState state);
Map<String, Object> guidanceMessage(Activation activation);
String explicitUserContent(Activation activation, String args);
void commitActivation(TurnState state, Activation activation);
```

内部类型固定如下：

- `ExplicitCommand(String name, String args)`：原始输入仅用于解析；args 保留内部换行和空格，允许去掉首尾空白。
- `Activation(SkillDocument document, String guidanceContent)`：候选，不产生全局或请求加载副作用。
- `LoadOutcome(String toolContent, Activation activation, String skillName, boolean ok)`：失败时 activation=null；成功状态只有预算校验后才能对外确认。
- `TurnState`：每请求创建，只包含 protectedTailCount=1、automaticLoadAttempts=0、loadedName=null。loaded 状态只由 commitActivation 更新。指南已在 messages 中，无需再保存正文副本、hash 或累计 token。
- `SkillException`：嵌套异常，带本文件定义的 code 和固定安全消息；不携带文件路径或解析堆栈到客户端。

`prepareLoad` 对精确 name 参数做解析，拒绝未知字段、非字符串、尾随 JSON。每个属于当前可见 load_skill 的调用都消耗尝试额度，包括无效参数与查找失败。只有第一次能准备候选；同批其余调用返回 SKILL_LOAD_LIMIT，不重复添加正文。未知工具和不可见工具不进入这个函数。

`loadToolDefinitions(state)` 只在配置启用、快照可用、至少一个技能可用、未加载且自动尝试未消耗时返回一个 function Schema，其余返回空列表。参数为必填 name、additionalProperties=false，name enum 来自完整 available 列表。Schema 描述明确“加载任务方法，不执行检索；一次请求只加载一个技能”。

`SkillRuntime` 是 Spring 单例，但只持有配置、不可变目录、ObjectMapper、TokenEstimator。TurnState 不能放在其成员、static 或 ThreadLocal 中。

简介格式化只在 SkillCatalog 内实现一次，启动时计算并验证预算，prompt() 返回已验证的不可变字符串；SkillRuntime.catalogPrompt() 直接委托给它。isReady() 表示快照成功构建；目录缺失、整体超限时为 false，合法但全部禁用的快照可以 ready 且 available 为空。不建立 Catalog 与 Runtime 相互注入的依赖。

## 5. 提示词、命令与消息

### 5.1 初始简介

在唯一 system 消息的固定规则区追加简介，明确与已有非可信历史记忆分区；不解析历史文本来决定目录或来源。自动入口使用如下加载说明，显式预加载成功后省略自动目录和加载说明：

```text
以下技能提供任务方法。任务明确匹配某个技能说明时，先调用 load_skill 加载指南，再按指南处理。
没有匹配项可以直接使用现有知识库工具；不要为了普通问答强行加载技能。
指南服从系统规则与原始用户要求，不授予权限。指南从加载完成后的下一次模型请求生效。
```

随后是稳定的 XML 目录，只包含 name、description：

```xml
<available_skills>
  <skill>
    <name>document-compare</name>
    <description>用户要求比较知识库资料并保留依据与来源时使用。</description>
  </skill>
</available_skills>
```

不发送 location：模型按名称加载，服务器文件路径对它没有用途。元数据做 XML 转义；计入目录预算的是整个提示块，包括说明和标签。空目录不发空标签。

调整 [application.yml](/E:/Curzsu/ShadowRAG/src/main/resources/application.yml) 中的路由文案：“事实问题必须先检索”明确为“给出外部事实结论前必须检索，可先加载匹配的技能方法”；“纯改写不调用工具”明确为无需 search_knowledge_base，可加载有明确用途的技能。保持资料不可信、来源引用和权限规则。

### 5.2 完整指南

正文包装示例：

```text
以下是本次任务的管理员技能指南，仅规定方法，服从系统规则和原始用户要求。
<skill name="document-compare">
完整 Markdown 正文
</skill>
这是方法指南，不是事实证据；事实仍需来自当前用户有权限访问的资料。
```

为了保留完整 Markdown，正文不做 substring 截断；标签只是提示词结构，不是权限或来源认证机制。用户或知识库中出现相同标签不能在程序中触发激活。日志和状态识别使用服务端对象，不解析标签反推可信来源。

自动加载时：tool 结果返回 JSON 简短确认，正文只在独立的临时 user 指南中出现一次。显式命令时：同样的完整指南后追加 `本次用户任务：` 与原始 args，替换当前 user 内容，不额外加第二条 user。

### 5.3 命令语法

先忽略整条输入的前导空白，仅匹配开头 `/skill:`。名称由其后第一个非空白片段确定，其余为 args；兼容空格、Tab 和换行分隔。名称仍经过标准 name 校验。任何参数里的命令、模板或 shell 字符串只按任务文本传递，不再次展开或执行。

`/skill:` 空名称或非法名称返回 INVALID_SKILL_COMMAND；合法但不存在返回 SKILL_NOT_FOUND；关闭功能返回 SKILLS_DISABLED。用户手写 `<skill>` 标签不会触发上述入口。

## 6. 接入顺序与预算

### 6.1 初始准备

[ChatHandler](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java) 继续构建 system / 历史 / 当前原始 user。移除 buildMessagesForAgenticRAG 中按固定知识库 Schema 提前 fit 的调用，将最终预算集中到 AgentLoopService；保留摘要数量和非可信记忆边界。

为减少参数传递和改动，简介追加及显式展开统一在 AgentLoopService 首次模型请求前完成。generate 的现有对外签名保留。每次运行新建 TurnState。原始 ChatCommand 始终不变，KnowledgeBaseSearchTool 继续使用 command.username()。

显式命令流程：prepareActivation → 替换候选当前 user 内容 → 按实际 tools 完整 fit → checkRunning → commitActivation。任一步失败都不请求模型，不保存展开后的用户文本。不要把这些错误包在 ChatHandler 的 HISTORY_ERROR catch 中。

在 AgentLoopService 将显式 SkillException 转换为带相同 code 的 ChatHandler.GenerationException，才能经过现有 SSE 终态路径。显式预加载的 ContextWindowExceededException 转为 SKILL_CONTEXT_BUDGET；其他不可恢复上下文超限转为 CONTEXT_BUDGET。CancellationException 和已有 STREAM_TIMEOUT 按现有取消 / 超时路径传播，不被上述转换吞掉。

### 6.2 当前任务保护

直接调用 [ContextBudgetService](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ContextBudgetService.java) 已有的公共方法：

```java
List<Map<String, Object>> fit(
    List<Map<String, Object>> messages,
    int reservedOutputTokens,
    int extraTokens,
    int protectedTailCount);
```

extraTokens 传本次实际工具定义的 token。现有三参数 fitAgent 保留给原调用和测试；新增指南的 Agent 路径改为直接调用 fit 并提供显式尾部计数。无需给预算服务增加重载或改算法。

初始 protectedTailCount=1。每个当前任务 assistant、tool、指南追加后增加计数；历史裁剪只删除该尾部范围之前的完整旧轮，不改变计数。显式命令替换现有 user 不增加计数。

现有工具正文裁剪可以继续用于检索数据；正文指南处于受保护 user 消息，不会进入 tool 裁剪。当前任务无法通过删旧历史和裁剪检索正文放下时，明确失败，不删原始问题或指南。

每次模型请求使用同一份实际 visibleTools，预算与发送必须一致：

```text
TokenEstimator(messages)
+ TokenEstimator(JSON(visibleTools))
+ generation.maxTokens
+ context.safetyMarginTokens
<= context.windowTokens
```

索引和指南已经在 messages 中，不重复加计；空工具按 0 预留。单份正文 3000 token 是加载前限制，包装后的整个请求仍需试算。当前估算器使用 CL100K_BASE，不宣称精确匹配供应商 tokenizer。

### 6.3 自动激活的提交顺序

每轮固定 visibleTools 和 visibleNames 到本地变量；可执行集合是知识库工具，加上仍可用的 load_skill。精确名称分支即可，不实现通用暴露快照类。

1. 保留模型返回的完整 assistant tool_calls，包括现有 opaque thought_signature 协议字段。
2. 按 index 顺序处理本批调用，每个 callId 必须有一个 tool 结果。知识库检索直接复用 execute，未知 / 未暴露工具返回受控错误。
3. load_skill 产生候选，不提交已加载状态，也不提前发送 ok=true 的 finished 进度。
4. 全部 tool 结果配对完成后，向草稿消息末尾追加候选指南。不要插在 tool 结果中间；同批检索仍按加载前的指南执行。
5. 用草稿尾部计数与下一轮实际工具定义试算；候选成功时下一轮只保留知识库工具，收尾时没有 tools。
6. 成功：checkRunning，再一次性提交拟合消息、尾部计数和加载状态，然后发送 finished / ok=true。
7. 候选放不下：移除候选指南，将该 callId 的 tool 内容改成 SKILL_CONTEXT_BUDGET，加载状态仍为空；自动尝试额度已消耗，后续只提供知识库工具。重新 fit 完整配对的失败消息，成功则继续普通 RAG。
8. 连不含新指南的当前任务也放不下：以 CONTEXT_BUDGET 结束请求。取消或超时沿既有终态处理，不转换成可恢复加载失败继续调用。

LoadOutcome 中的 ok 只是候选成功，不得在第 5 步之前作为外部成功状态。进度 finished 表示调用结束，必须结合 ok 才能说明是否加载成功。

### 6.4 循环额度

保留现有配置 maxToolRounds=3、maxToolCalls=6、重复查询阈值=3、收尾预留 10000 ms 和总生成期限。不要全局改成 6 轮 / 12 次。

请求初始化时固定 effectiveMaxToolRounds：首次确实提供 load_skill 则取 maxToolRounds+1，否则取 maxToolRounds。之后所有返回工具调用的模型轮统一 rounds++，包括加载、搜索、混合、无效和未知调用；不区分加载轮豁免。显式预加载不占模型工具轮。

executed 仍只统计实际知识库调用，按原 Result.executed 语义计入 6 次上限；load_skill 不增加 executed，独立的 automaticLoadAttempts 只用于限制加载尝试。下一次请求不会因为 Schema 已移除 load_skill 而缩回轮上限。

模型循环硬上限为 effectiveMaxToolRounds+1，最后一次供无工具收尾：默认自动入口最多 5 次，显式入口和关闭时最多 4 次。轮数 / 搜索次数 / 时间任一达到限制就立即进入无工具收尾，不为了用满上限调用模型。

接受的取舍：自动入口开放但模型没有加载技能时，也允许最多 4 个工具轮，检索次数仍最多 6 次。这是有界地放宽原来的轮上限，用来省去批次豁免和多套轮次计数；不保证未使用技能的启用请求仍精确保持 3 个工具轮。若后续实测费用或延迟需要更严格控制，再细分预算。

达到预算后的收尾维持现有部分完成提示。重复查询继续使用知识库 query 归一化，load 参数不要交给 search.query。隐藏的 load_skill 调用不得执行，即使 name 存在也返回 TOOL_NOT_EXPOSED。

## 7. 页面、错误和持久化

### 7.1 SSE 和页面

自动加载复用 tool_progress：tool=load_skill、roundId、callId、status=started/finished。增加可选 skillName（合法 name）及 finished 时的 ok（boolean）；不向前端发送正文或路径。

显式命令直接从内存预加载，不发送 tool_progress，也不创建模拟 callId。用户能在已发送的原始命令中看到所选技能；失败走明确的 SSE error，成功后直接进入回答流程。专门的“已选技能”标记可以后续加，本轮不新增 UI 状态。

两端都接受 search_knowledge_base / load_skill，其他未知名称继续拒绝。旧知识库事件不强制新增字段。load_skill 的 finished 必须带 ok，skillName 可缺省（例如非法参数），如存在必须通过名称校验。

Vue 页面按名称显示“知识库检索”或“加载技能：名称”。技能 finished / ok=true 为“已加载”，ok=false 为“加载失败”；不要把失败显示成成功。检索原有进度继续可用。

### 7.2 错误契约

显式命令失败作为 SSE error + failed completion 结束，不调用模型，不持久化成功轮。以下终态错误码必须同时加入后端 safeMessage 和前端 errorCodes：

| code | 固定客户端文案 |
| --- | --- |
| SKILLS_DISABLED | 当前未启用技能功能 |
| INVALID_SKILL_COMMAND | 技能命令格式无效，请使用 /skill:名称 任务 |
| SKILL_NOT_FOUND | 未找到指定技能，请检查名称 |
| SKILL_DISABLED | 指定技能已被管理员禁用 |
| SKILL_UNAVAILABLE | 技能目录当前不可用，请联系管理员 |
| SKILL_CONTEXT_BUDGET | 当前任务无法完整加载技能，请缩短问题或新建会话 |
| CONTEXT_BUDGET | 当前任务超出上下文预算，请缩短问题或新建会话 |

文件解析和正文单份超限在启动时诊断，不暴露半个合法 Skill；显式查找被跳过的文件可返回 SKILL_NOT_FOUND，管理员从诊断查看具体原因。

自动加载的上述失败先作为配对 tool 错误返回模型，可以继续普通 RAG；不是每次工具失败都结束 SSE。另有工具内错误 INVALID_ARGUMENTS、SKILL_LOAD_LIMIT、TOOL_NOT_EXPOSED、UNSUPPORTED_TOOL，不加入终态 error 白名单。

提示词要求自动加载失败时在最终答案简短说明未使用该技能，然后按现有资料处理。页面还通过 ok=false 明确显示加载失败，不能只依赖模型自行解释。

### 7.3 持久化与日志

persistCompletedTurn 始终使用原 ChatCommand.message() 和最终回答。技能指南、工具确认、进度、opaque 协议状态不写入真实用户 / 助手历史。已有完成仲裁、失败不存、取消不存和 Redis 缓存流程继续复用。

复用现有日志，技能操作只补 requestId、skillName、成功 / 失败类别和耗时，不建立版本审计、指标平台或额外日志存储。文件诊断可在管理员服务端日志包含配置内路径，SSE 和模型请求不包含真实路径；不记录技能全文、用户查询或凭证。

## 8. 改动清单

| 现有文件 | 本轮改动 |
| --- | --- |
| [ChatHandler.java](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java) | 消息准备保留原文，移除固定 tools 的提前 fit，保留历史与持久化行为 |
| [AgentLoopService.java](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/AgentLoopService.java) | 请求内状态、简介、显式预加载、精确工具分发、候选提交、一次额外加载轮 |
| [ContextBudgetService.java](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ContextBudgetService.java) | 复用现有 fit，无需修改生产实现；新增调用侧保护测试 |
| [ChatStreamService.java](/E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/chat/ChatStreamService.java) | 仅补全新终态 code 的安全文案，不重写仲裁或持久化 |
| [application.yml](/E:/Curzsu/ShadowRAG/src/main/resources/application.yml) | 默认关闭配置，针对 Skill 的提示词规则 |
| [pom.xml](/E:/Curzsu/ShadowRAG/pom.xml) | 仅在需要时显式声明已有版本管理下的 SnakeYAML |
| [chat-stream.ts](/E:/Curzsu/ShadowRAG/frontend/src/service/api/chat-stream.ts) | 新工具名、加载进度可选字段、终态 code 的协议校验 |
| [chat-rounds.ts](/E:/Curzsu/ShadowRAG/frontend/src/store/modules/chat/chat-rounds.ts) | ChatToolCall 保留 skillName / ok；finished 更新不能丢字段 |
| [chat-message.vue](/E:/Curzsu/ShadowRAG/frontend/src/views/chat/modules/chat-message.vue) | 按工具名和 ok 显示技能进度；“检索过程”改为覆盖两种工具的“处理过程” |
| [chat-stream.mjs](/E:/Curzsu/ShadowRAG/src/main/resources/static/chat-stream.mjs) | 用现有脚本从 TS 生成，禁止手改生成协议 |
| [test.html](/E:/Curzsu/ShadowRAG/src/main/resources/static/test.html) | 静态调试页按工具名区分加载 / 检索，正确显示失败 |

不改 ChatCommand 和 HTTP 请求 DTO；输入框继续发送原始 message。DeepSeekClient 已接受 tools 列表，无需改供应商请求或引入新的 LLM 框架。

已有测试通过构造器直接创建 ChatHandler / AgentLoopService。新增依赖后统一更新这些测试的构造位置；必要时保留一个明确关闭 Skill 的兼容构造器，但不要在测试分支悄悄创建启用状态或另一套运行时。

## 9. 开发任务

按下面四个任务顺序实施，每个任务通过后再推进。推荐同一开发者顺序完成，减少接口交接；不需要并行代理。每个任务完成后可形成独立提交，提交范围仅包含本任务文件和在已有改动基础上增加的相关变更，禁止 git add . 混入其他工作。

### Task 1：配置、目录与两份默认 Skill

**Files:** 创建第 4 节五个文件中的 SkillProperties、SkillDocument、SkillDocumentParser、SkillCatalog，以及两份默认 SKILL.md；修改 application.yml。创建以下测试：

- [SkillDocumentParserTest.java](/E:/Curzsu/ShadowRAG/src/test/java/com/yizhaoqi/smartpai/skill/SkillDocumentParserTest.java)
- [SkillCatalogTest.java](/E:/Curzsu/ShadowRAG/src/test/java/com/yizhaoqi/smartpai/skill/SkillCatalogTest.java)

**Interfaces:** 产出第 4 节 parse、find、available、isDisabled、isReady、prompt 和不可变 SkillDocument，供 Task 2 使用。

- [ ] 写 parser 测试：`parsesBomCrLfAndChineseBlockDescriptions`，分别给 BOM / CRLF / 中文 / `|` / `>` 输入，断言准确 name、description、body，无 frontmatter 残留。
- [ ] 写 parser 测试：`rejectsMissingFieldsDuplicateYamlKeysAndOversizedBody`，断言 name 必填、目录一致、空正文、重复键、自定义标签、超深嵌套、别名、非法 UTF-8、65536 字节超限与 3000 token 超限全部受控失败。
- [ ] 写 catalog 测试：`disabledModeDoesNotReadFilesystem`、`missingDirectoryKeepsRagAvailable`、`snapshotIsImmutableAndOnlyDirectSkillsAreDiscovered`、`disabledSkillIsNotAdvertised`、`indexOverflowDisablesEntireSnapshot`、`outOfRootLinkIsRejected`。用临时目录，覆盖排序和完整 1500 token 预算，禁止仅按字符猜测 token。
- [ ] 运行下方命令，确认新增功能缺失导致测试失败。
- [ ] 实现配置、解析和扫描；按第 3 节创建两个指南。SkillCatalog 内实现唯一的简介 formatter，启动时生成 prompt 并检查完整块预算；此任务不依赖尚未创建的 SkillRuntime。
- [ ] 再运行，预期新增测试通过；Windows 链接测试若无法创建链接，明确记录未验证项，不能称已覆盖。
- [ ] 检查 diff，可单独提交目录能力；此时在线对话仍未启用 Skill。

```powershell
Set-Location 'E:\Curzsu\ShadowRAG'
mvn '-Dtest=SkillDocumentParserTest,SkillCatalogTest' test
```

### Task 2：命令、候选激活与任务保护

**Files:** 创建 SkillRuntime。创建 [SkillRuntimeTest.java](/E:/Curzsu/ShadowRAG/src/test/java/com/yizhaoqi/smartpai/skill/SkillRuntimeTest.java)，扩展 [ContextBudgetAgentTest.java](/E:/Curzsu/ShadowRAG/src/test/java/com/yizhaoqi/smartpai/service/ContextBudgetAgentTest.java)；无需修改 ContextBudgetService 生产实现。

**Interfaces:** 消费 Task 1 的目录及现有 ContextBudgetService.fit；产出第 4 节 Runtime 全部签名及 TurnState，供 Task 3 使用。

- [ ] 写 runtime 测试：`catalogHasMetadataButNoBodyOrServerPath`，断言 XML 转义、排序、完整块 token；`explicitCommandPreservesArgsAndOnlyMatchesPrefix`，断言 Tab / 换行 / 前导空白、空参数、参数内命令和正文中命令。
- [ ] 写 runtime 测试：`loadAcceptsOnlyExactNameArgument`、`preparingActivationDoesNotMarkLoaded`、`oneAttemptAndOneSkillPerTurn`、`disabledOrUnknownSkillsHaveSpecificCodes`。断言非法 JSON 消耗一次尝试，候选阶段 loadedName 为空，提交后才有 name，下一次请求状态为空。
- [ ] 在 ContextBudgetAgentTest 新增 `explicitTailPreservesQuestionProtocolAndLaterGuidance`：system、旧轮、真实问题、assistant/load tool、后续 user 指南、assistant/search tool；压低预算，断言旧轮删除，真实问题、所有 callId、完整指南和来源索引保留。
- [ ] 新增 `fullGuidanceCannotBeSilentlyTruncated`：指南和当前任务不可裁剪部分无法放下时，断言 ContextWindowExceededException，原消息不被原地修改。
- [ ] 运行下方测试确认 Runtime 功能缺失导致失败；使用现有 fit 的保护测试应能通过，不人为修改预算算法制造失败。
- [ ] 实现 Runtime。SkillRuntime.catalogPrompt 直接委托 Task 1 的 SkillCatalog.prompt，不重复生成简介，不引入 Spring 依赖循环。保护测试直接调用已有 fit 的显式尾部参数。
- [ ] 再运行，预期全部通过；此任务只提供能力，尚不改变在线模型请求。
- [ ] 检查 diff，可单独提交候选激活和预算契约。

```powershell
mvn '-Dtest=SkillRuntimeTest,SkillCatalogTest,ContextBudgetAgentTest,ContextBudgetServiceTest' test
```

### Task 3：自动加载与显式命令接入现有循环

**Files:** 修改 AgentLoopService、ChatHandler、application.yml。创建 [SkillAgentLoopTest.java](/E:/Curzsu/ShadowRAG/src/test/java/com/yizhaoqi/smartpai/service/SkillAgentLoopTest.java)，扩展现有 AgentLoopServiceTest、ChatHandlerHistoryTest、ChatHandlerStreamingTest。

**Interfaces:** 消费 Task 2 的 Runtime 与既有四参数 fit；保持 generate(ChatCommand, ChatRequestContext, List<Map<String,Object>>, Consumer<ChatOutput>)、persistCompletedTurn 的对外契约。产出 load_skill 进度以及第 7 节定义的终态代码，Task 4 配套页面。

- [ ] 使用现有 [MockModelSseServer](/E:/Curzsu/ShadowRAG/src/test/java/com/yizhaoqi/smartpai/support/MockModelSseServer.java) 捕获真实请求，不只 mock “加载成功”返回值。
- [ ] 写 `firstRequestHasCatalogAndBothToolsButNoBody`、`automaticLoadInjectsBodyExactlyOnceInNextRequest`：断言初始 tools 为知识库和 load_skill，后续只含知识库；正文一次出现，角色序列为 system、真实 user、assistant、tool、指南 user。
- [ ] 写 `explicitCommandExpandsBeforeFirstModelRequest`：首次含完整正文与参数，不含 load_skill；原 command 不变，不发送模拟 tool_progress，不伪造模型工具配对。
- [ ] 写 `sameBatchPairsAllResultsBeforeGuidance`：模型同批返回 load_skill、search_knowledge_base、第二个 load_skill，断言每个 callId 都有 tool，只有一个正文，指南在全部 tool 后，第二次加载返回限额错误；检索身份仍是 alice。
- [ ] 写 `loadBudgetFailureDoesNotCommitAndRagCanContinue`：正文在单份预算内但整个候选放不下，断言 loadedName 为空、ok=false、没有正文、对应 tool 为 SKILL_CONTEXT_BUDGET，下一次仍可搜索。
- [ ] 写 `automaticModeUsesOneAdditionalUnifiedToolRound`：一个加载轮 + 三个搜索轮 + 一个无工具收尾，断言最多 5 个请求、每个工具批次统一计轮、实际搜索最多 6 次；再断言自动入口未加载技能时也允许 4 轮，显式入口和关闭时仍是 3 轮。重复和未知调用不能空转。
- [ ] 写 `explicitUnknownSkillStartsNoModel`、`nextUserTurnHasFreshSkillState`、`concurrentRequestsNeverShareGuidance`、`cancelBeforeActivationCommitStopsFurtherWork`、`thoughtSignaturesSurviveGuidanceInsertion`。
- [ ] 在历史测试断言完成时只保存原始 `/skill:...` 和最终答案；关闭功能时原有行为成立。将 buildMessages 的预算断言移到实际 Agent 请求测试，避免要求已移除的提前 fit 行为。
- [ ] 运行测试确认失败，然后按第 6 节顺序实施；保持现有模型取消、重复检索检测、来源和收尾协议。
- [ ] 再运行，预期通过；Task 3 的进度事件在 Task 4 完成前尚不适合开启给真实前端，部署配置继续关闭。
- [ ] 检查 diff，可单独提交后端闭环。

```powershell
mvn '-Dtest=SkillAgentLoopTest,AgentLoopServiceTest,KnowledgeBaseSearchToolTest,ContextBudgetAgentTest,ChatHandlerHistoryTest,ChatHandlerStreamingTest' test
```

### Task 4：SSE、页面提示和完整验收

**Files:** 按第 8 节修改 ChatStreamService 和前端 / 静态页；扩展 [ChatStreamServiceTest.java](/E:/Curzsu/ShadowRAG/src/test/java/com/yizhaoqi/smartpai/service/chat/ChatStreamServiceTest.java)、[chat-stream.test.ts](/E:/Curzsu/ShadowRAG/frontend/src/service/api/chat-stream.test.ts)、[chat-rounds.test.ts](/E:/Curzsu/ShadowRAG/frontend/src/store/modules/chat/chat-rounds.test.ts)。生成的静态协议由同步脚本更新。

**Interfaces:** 消费 Task 3 的事件和错误码；产出第 10 节的页面体验，不新增 HTTP DTO、事件类型或上传入口。

- [ ] 写传输测试：接受 load_skill 的 started / finished / ok / skillName；接受第 7.2 节全部终态错误码；拒绝未知工具名、非法技能名、非布尔 ok 和缺少 ok 的技能 finished；旧知识库事件继续兼容。
- [ ] 写 chat-rounds 测试：started 建立一行，finished 更新同一 callId 的 ok / skillName；不重复行，不把中间轮或进度写入最终答案。
- [ ] 写后端 SSE 测试：显式未知技能发 SKILL_NOT_FOUND 和 failed completion、无持久化；技能 finished 不是最终回答确认；取消不存指南或部分答案。
- [ ] 先运行测试确认失败，再修改校验、状态保存和文案；Vue 在现有未提交改动基础上增加，不恢复旧文件。
- [ ] 运行同步脚本生成静态 transport；静态调试页也区分技能加载，不能只改 Vue。
- [ ] 运行后端相关回归、前端 test:chat 和 typecheck，预期通过。已有不相关失败单独记录，不能当成本功能通过。
- [ ] 完成第 10 节手工验收并记录结果。检查 diff，可单独提交展示与协议配套；到此才能在目标环境开启 Skill。

```powershell
Set-Location 'E:\Curzsu\ShadowRAG'
mvn '-Dtest=SkillDocumentParserTest,SkillCatalogTest,SkillRuntimeTest,SkillAgentLoopTest,AgentLoopServiceTest,KnowledgeBaseSearchToolTest,ContextBudgetAgentTest,ContextBudgetServiceTest,ChatHandlerHistoryTest,ChatHandlerStreamingTest,ChatStreamServiceTest,ChatReActHttpTest' test

Set-Location 'E:\Curzsu\ShadowRAG\frontend'
pnpm sync:chat-transport
pnpm test:chat
pnpm typecheck
```

## 10. 产品验收与效果评估

使用两份可区分、带已知事实的知识库测试资料 A / B，以及另一登录用户不可访问的私有资料。启用两份默认 Skill，运行以下场景：

| 场景 | 通过标准 | 证据 |
| --- | --- | --- |
| 自动文档比较 | 匹配 document-compare、成功加载、检索两方、按维度比较并保留来源 | 页面过程 + 请求捕获 + 答案 |
| 自动资料总结 | 匹配 evidence-summary，包含结论 / 证据 / 未覆盖信息 | 页面过程 + 答案 |
| 显式比较 | `/skill:document-compare 比较 A 和 B` 首次请求即含指南，无额外模型加载轮或模拟工具进度 | 请求捕获 + 历史原文 |
| 无匹配 / 寒暄 / 纯计算 | 不强行加载技能，正常回答 | 无加载调用 |
| 未知 / 禁用 / 功能关闭命令 | 固定可理解的错误，前端不报协议故障，无模型请求和成功轮持久化 | SSE + 页面 + 数据库断言 |
| 自动加载预算失败 | 显示加载失败，后续按普通 RAG 处理，不声称成功使用指南 | ok=false + 无正文的后续请求 |
| 比较对象缺一方资料 | 写明资料缺失，不推断其不存在，不伪造差异与来源 | 答案核对 |
| 并发 / 后续请求 | 指南不串用户；新请求没有继承已加载状态 | 并发测试 + 请求捕获 |
| 加载或检索时停止 | 不再启动后续操作，不持久化部分回答，页面结束 | 取消测试 + 手工停止 |
| 原功能兼容 | 普通问答、来源、历史重进、取消和错误体验正常 | 回归测试 + 页面 |
| 修改管理员文件后重启 | 新指南生效，不需要改 Java；未重启仍使用旧快照 | 加入可识别的指南内容并捕获实际请求 |
| 私有资料访问 | 另一用户使用相同 Skill 仍不能检索无权资料 | 实际认证身份与结果 |

自动选择属于模型行为：脚本测试只证明接入机制，不能证明真实模型一定选对技能。使用目标模型做一个固定小样本：比较 4 条、总结 4 条、无需技能 4 条，共 12 条，保存原始问题与启用 / 关闭两组结果。

逐条记录匹配是否正确、指定格式是否遵循、事实 / 引用是否正确、资料不足是否表达清楚、模型请求次数和总耗时。所有明确匹配样本应正确加载且按方法完成；无需技能样本不加载；不得出现捏造来源或“未提及=不存在”。失败时先调整 description / 指南 / 路由提示再复测，不直接增加新的技能分类器。

延迟记录实际值，不预先声称显著提升或零成本：自动加载通常增加一次模型往返，显式调用省去选择与加载往返。达到上述行为标准才可开启；若新增延迟不可接受，优先让已知任务使用显式入口，或缩短简介和指南，不扩大基础架构。

## 11. 交付、上线与后续边界

开发结果必须包含实现、两份 Skill、通过的测试记录、12 条真实模型验收记录、管理员启用说明。验收记录建议新增到 [2026-10-07-skill-integration-roi-acceptance.md](/E:/Curzsu/ShadowRAG/docs/research/2026-10-07-skill-integration-roi-acceptance.md)，由实施者在实际运行后填写；不要事先勾选或虚构通过结果。

管理员启用说明：随部署分发或挂载 skills 目录 → 配置服务端绝对 directory → enabled=true → 重启 → 查看目录诊断 → 在页面完成显式比较和普通问答冒烟。容器中配置的是容器路径，不是开发机的 E 盘路径。

回退：enabled=false 后重启，Skill 索引和工具省略，普通知识库功能仍可用；前端对新事件的兼容可以保留。历史中已保存的原始技能命令仅作为历史文本；若用户再次发命令，明确提示功能关闭。

本轮完成后停止在这里。只有实际出现相应需求，才另行设计：多技能组合、参考文件按需读取、技能目录搜索、工具 Schema 动态开放、用户或租户私有 Skill、MCP / CLI、脚本执行或热更新。

### 参考依据

- [Agent Skills 文件规范](https://agentskills.io/specification)：SKILL.md、YAML 元数据和目录格式。
- [官方接入指南](https://agentskills.io/client-implementation/adding-skills-support)：渐进披露、专用加载工具及显式入口。文件格式兼容不代表任意技能依赖的运行能力均受支持。
- [pi skills.ts](/E:/pi/packages/coding-agent/src/core/skills.ts:358)：简介广播；启动读取文件不等于把正文发送给模型。
- [pi agent-session.ts](/E:/pi/packages/coding-agent/src/core/agent-session.ts:2146)：显式命令读取正文、去除 frontmatter 并附带用户参数。

pi 源码只用于理解机制。执行本计划不依赖开发机存在 E:/pi，也不需要复制 pi 的文件工具、目录扫描兼容层或完整 agent 架构。
