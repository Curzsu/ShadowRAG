# 可观测指南

> 与本分支代码同步。依据：[LangfuseConfiguration](../src/main/java/com/yizhaoqi/smartpai/config/LangfuseConfiguration.java)、[LangfuseTracing](../src/main/java/com/yizhaoqi/smartpai/observability/LangfuseTracing.java)、[配置加载脚本](../scripts/langfuse/run-with-langfuse.ps1)及[评测脚本目录](../scripts/langfuse/)。

## 目前能看什么

已实现 Java 手动 OpenTelemetry 埋点，通过 HTTP/protobuf 批量导出到 Langfuse：

- `chat.request`：请求、会话关联、耗时、终态、是否提交、完成通知是否送达。
- `llm.round`：每轮模型、生成参数和首次非空 content 时间。
- `tool.knowledge_search`：工具调用、检索结果标识及精排状态；搜索内部还有 embedding、retrieval、rerank 阶段计时。
- 离线检索与首轮路由评测：可关联样本、实验、trace 和评分。

这不是完整运行监控平台。当前没有接入 ELK、Prometheus/Grafana 指标告警或独立 Collector，也没有完整跨 HTTP、Kafka、日志的统一关联保证。供应商实际 usage／Token 消耗和成本核算未作为这套追踪的完整能力交付；上下文 Token 估算不能当作实际账单。

## 启用与关闭

普通 `mvn spring-boot:run` 默认关闭 Langfuse。可在进程环境中设置以下变量，或手动建立 Git 忽略的根目录 `.env.langfuse.local`：

```dotenv
LANGFUSE_ENABLED=true
LANGFUSE_BASE_URL=https://jp.cloud.langfuse.com
LANGFUSE_PUBLIC_KEY=填入项目公钥
LANGFUSE_SECRET_KEY=填入项目私钥
LANGFUSE_ENVIRONMENT=development
LANGFUSE_CAPTURE_CONTENT=false
```

不要将真实值加入 Git。`LANGFUSE_BASE_URL` 使用项目所在区域的根地址，不包含 API 路径；导出器会追加 `/api/public/otel/v1/traces`。

在项目根目录执行：

```powershell
./scripts/langfuse/run-with-langfuse.ps1
```

脚本只读取六个允许键，已有非空进程环境变量优先，子进程结束后恢复环境；普通 Maven 命令不会自动读取这个文件。缺少凭据或导出配置无效时使用无操作追踪，导出失败不改变聊天结果。Java 导出为有界异步批处理，连接／导出超时 3 秒；已有 `HTTPS_PROXY`／`HTTP_PROXY` 可用于 HTTP CONNECT，本地回环端点绕过代理。

关闭时将 `LANGFUSE_ENABLED=false` 后重启对应后端进程；修改文件不会自动影响已启动进程。

## 数据与计时口径

普通聊天采集关联 ID、状态、模型参数和检索结果标识等元数据，不上传提问、回答、原始工具结果正文或凭据。`LANGFUSE_CAPTURE_CONTENT` 是保留配置，即使设为 true，当前聊天实现也不会因此上传正文。离线检索实验会读取或使用已导入云端的问题与标签，不能将聊天的正文限制误解为实验完全不处理问题文本。

- 模型 TTFT：从本轮模型观测开始到首次非空 content；只有工具调用的轮次没有正文首字时间。
- `server_first_chunk_ms`：后端首次发送 chunk 的时间，可能是中间说明，不等于用户最终答案首字或浏览器端耗时。
- 检索耗时与模型答案轮 TTFT 是不同阶段，不可互相代替，也不能用单次精排提速直接宣称整段聊天提速。

## 评测入口

以下命令会访问真实模型、检索服务或 Langfuse，并创建实验／评分，可能产生费用。它们不是文档检查，也不是默认 CI 的运行要求。

检索实验需要指定具有相应文档权限的实际账号，默认数据集为 `retrieval-eval-50`：

```powershell
./scripts/langfuse/run-retrieval-experiment.ps1 -Username admin -Limit 10
```

账号、数据集、索引与依赖需先准备好。脚本冻结云端样本，运行 Java BM25 和实际混合链路，计算 Hit@5／MRR@5 并回读云端核验。精排降级仍属于实际链路结果，严格精排成功子集应单独解读。流程见[实验接入记录](research/2026-10-08-langfuse-dataset-experiments.md)。

首轮路由联调：

```powershell
./scripts/langfuse/run-routing-evaluation.ps1
# 已复核数据可按脚本约定指定；不是任意数据集格式。
./scripts/langfuse/run-routing-evaluation.ps1 -Dataset ./path/to/reviewed-routing.jsonl
```

路由脚本参数只有 `Dataset`、`RunId`，没有 `Limit`。当前默认运行 10 条候选标签，检查首轮是否调用知识库工具，不执行后续完整检索问答；正式准确率前需完成当前策略的标签复核，见[路由记录](research/2026-10-08-langfuse-routing-evaluation.md)及[资产说明](eval/agent_routing/README.md)。

## 验证状态与排障

已有真实聊天与小样本实验的 Cloud 回读记录，但本次文档整理没有重跑这些联调，不将历史结果写成当前运行验收。10 条候选路由结果、10 组检索结果均不能替代正式完整标注集的质量指标。历史过程见[阶段 1、2 记录](research/2026-10-08-langfuse-stage-1-2-progress.md)和[检索评测记录](research/2026-10-08-langfuse-retrieval-evaluation.md)。

看不到 trace 时先确认启动方式、开关、区域、凭据与代理，再检查固定导出告警；批量导出存在延迟，SDK 刷新成功不等于云端已收到。不要为排障在日志打印密钥或业务正文。性能问题先分辨模型、embedding、retrieval 和 rerank 阶段，再核对是否发生降级。
