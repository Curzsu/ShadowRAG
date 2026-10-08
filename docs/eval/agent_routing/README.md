# ShadowRAG Agentic RAG 路由评测集

> 2026-10-06 策略更新：线上已改为事实性问答优先检索，人物/实体及技术概念不再默认 DIRECT，纯寒暄、纯计算、翻译和输入文本改写仍可直接完成。此目录的 v1/v2 提示词、工具快照、标签及模型报告保留为历史评测资产，不代表当前策略；重新做整套准确率评测前须按新策略重新复核标签。当前策略使用实际生产提示词和 Java 工具定义的 `LiveGeminiKnowledgeFirstTest` 验收。

当前策略的 Langfuse 首轮路由联调入口为 `scripts/langfuse/run-routing-evaluation.ps1`，默认仅跑 10 条待复核候选标签。以下定义与旧脚本属于历史策略，不用于当前准确率验收。

这套历史资产用于测量第一次模型调用是否正确选择 `search_knowledge_base`，历史标签为：

- `SEARCH`：回答依赖用户上传文件、项目知识库、内部制度或指定文档，第一次调用应产生 `search_knowledge_base` Tool Call。
- `DIRECT`：不依赖私有文档即可回答的常识、技术原理、写作、计算或闲聊，第一次调用不应产生 Tool Call。

当前不设置 `CLARIFY` 标签，因为生产代码尚无独立澄清 Tool。无法在现有上下文中判断目标文档的纯歧义问题不进入本轮二分类测试，避免评测标签与线上能力不一致。

## 文件

| 文件 | 用途 |
|---|---|
| `routing-dev.jsonl` | 60 条开发集，`SEARCH/DIRECT = 30/30`；只用于调 Tool Description、System Prompt 和规则。 |
| `routing-test.jsonl` | 120 条锁定测试集，`SEARCH/DIRECT = 60/60`；调参阶段不得据此修改 Prompt。 |
| `route_eval.py` | 零第三方依赖的数据校验和指标计算工具。 |
| `route_predict.py` | 调用 OpenAI-compatible Function Calling 接口生成首轮路由预测。 |
| `ttft_benchmark.py` | 对 60 条 `DIRECT` 问题运行强制检索与 Agentic 的端到端 TTFT 对照。 |
| `test_route_eval.py` | 校验器与指标公式的自动化测试。 |
| `test_route_predict.py` | 路由请求、流式解析、生产 Prompt 与模型端点回归测试。 |
| `test_ttft_benchmark.py` | TTFT 流式计时、百分位与摘要公式测试。 |
| `predictions.example.jsonl` | 路由预测输出格式示例，不是完整预测文件。 |
| `run-metadata.template.json` | 正式评测时记录模型、Prompt、数据哈希和运行条件。 |

测试集有 18 条多轮问题、14 条检索困难正例和 38 条含“文档、报告、制度、PDF”等词但不应检索的困难负例。困难负例用于防止模型只靠关键词触发工具。

## 标注口径

只问一个问题：**要可靠回答当前问题，是否必须读取本项目可访问的私有或指定资料？**

| 场景 | 标签 | 示例 |
|---|---|---|
| 指定“上传的合同”“知识库制度”“某份 PDF”并询问其中事实 | `SEARCH` | “上传的合同把付款分成哪几个节点？” |
| 多轮历史已确定文档，当前问题用“它、里面、这份”承接 | `SEARCH` | 历史在看基金报告，当前问“它的跟踪误差呢？” |
| 询问项目当前配置、内部流程或公司标准 | `SEARCH` | “我们的索引名默认是什么？” |
| 询问一般定义、原理或行业惯例 | `DIRECT` | “指数基金的跟踪误差代表什么？” |
| 只出现“报告、PDF、制度”等名词，但未要求读取具体资料 | `DIRECT` | “报告结论应该怎样用数据支撑？” |
| 写作、计算、闲聊 | `DIRECT` | “把 240 ms 降到 156 ms，降幅是多少？” |

边界规则：

1. 以“答案是否依赖私有资料”为准，不以关键词为准。
2. 标注时同时阅读 `history` 与 `query`；历史能解析指代时不得当作歧义问题。
3. 题目明确要求“只依据资料”“引用原文”“比较上传文件”时标为 `SEARCH`。
4. 如果标注人认为问题必须先澄清且无法归入两类，记录在复核备注中并从锁定集替换，不强行猜标签。

## 人工复核门禁

这些样本目前由模型辅助构造，全部标记为 `pending_human_review`。在真人逐条确认并把状态改为 `human_reviewed` 前，只能称为“候选评测集”，不能在简历里写“120 条人工标注测试集”。

推荐由两个人独立复核；时间紧时，至少由项目作者逐条做一次盲审，并对所有分歧样本再次裁决：

1. 复制测试集，临时隐藏 `expectedRoute` 列或字段。
2. 只看 `history`、`query` 和上述口径，填写 `SEARCH`、`DIRECT` 或“需裁决”。
3. 与候选 `expectedRoute` 对比；一致则确认，不一致则写出理由并裁决或替换样本。
4. 确认后将该行 `annotationStatus` 改为 `human_reviewed`；不要仅批量替换状态。
5. 重新运行带人工复核门禁的校验命令。全部 180 条均完成后才会通过。

## 数据格式

每行是一个 JSON 对象：

```json
{
  "id": "test-search-016",
  "split": "test",
  "history": [
    {"role": "user", "content": "阅读《缓存故障复盘》"},
    {"role": "assistant", "content": "好的，你想了解故障经过还是改进措施？"}
  ],
  "query": "它认为最早出现的异常信号是什么？",
  "expectedRoute": "SEARCH",
  "tags": ["multi_turn", "pronoun_reference", "fact_lookup"],
  "annotationStatus": "pending_human_review",
  "source": "constructed"
}
```

预测文件每行至少包含：

```json
{"id":"test-search-016","predictedRoute":"SEARCH"}
```

判定规则必须直接来自第一次模型调用：出现 `search_knowledge_base` Tool Call 记为 `SEARCH`，否则记为 `DIRECT`。不能根据最终答案内容反推路由。

## 使用方式

在项目根目录执行，无需启动 Docker，也无需安装 Python 包：

```powershell
python docs\eval\agent_routing\route_eval.py validate `
  --dev docs\eval\agent_routing\routing-dev.jsonl `
  --test docs\eval\agent_routing\routing-test.jsonl
```

人工复核完成后执行严格校验：

```powershell
python docs\eval\agent_routing\route_eval.py validate `
  --dev docs\eval\agent_routing\routing-dev.jsonl `
  --test docs\eval\agent_routing\routing-test.jsonl `
  --require-human-reviewed
```

拿到线上路由预测后生成报告：

```powershell
python docs\eval\agent_routing\route_eval.py evaluate `
  --dataset docs\eval\agent_routing\routing-test.jsonl `
  --predictions docs\eval\agent_routing\predictions.jsonl `
  --out-json docs\eval\agent_routing\results\route-report.json `
  --out-md docs\eval\agent_routing\results\route-report.md `
  --require-human-reviewed
```

输出包括：

- `Accuracy`：简历中的路由准确率 `[X]%`。
- `routes.SEARCH.recall`：简历中的知识库问题召回率 `[Y]%`，即应检索问题中实际触发搜索的比例。
- `Macro-F1`、两类 Precision/Recall/F1、混淆矩阵。
- 每个标签切片的准确率和全部错误样本，便于只在开发集上改 Prompt。

## 与 TTFT 的边界

路由报告本身不生成 `[Z]%`。TTFT 基准程序用同一模型、同一请求集、相同网络与预热条件，对测试集中的 60 条 `DIRECT` 问题分别跑“强制检索基线”和“Agentic 路由”，记录首个可见文本到达时间：

```text
Z = (P50_TTFT_强制检索 - P50_TTFT_Agentic) / P50_TTFT_强制检索 × 100%
```

测试集为刻意平衡的诊断集，适合比较路由能力；它不代表生产流量的真实类别比例。正式报告应同时保留模型名、temperature、Prompt/Tool Schema 版本、数据文件 SHA-256、预热次数、重复次数和原始逐条结果。

## 2026-08-30 实测结果

运行环境为 Volcengine Coding Plan 的 `glm-5-3-flash`，OpenAI-compatible Base URL 固定为 `https://ark.cn-beijing.volces.com/api/coding/v3`。密钥只通过临时环境变量注入，仓库不保存密钥。

### 路由

| 指标 | v1 | v2 |
|---|---:|---:|
| Accuracy | 77.5%（93/120） | **100%（120/120）** |
| SEARCH Recall | 100%（60/60） | **100%（60/60）** |
| DIRECT Recall | 55%（33/60） | **100%（60/60）** |

v2 只根据 60 条开发集上的 7 个误触发类型优化 Tool Description 与 System Prompt 的负边界；锁定测试集未用于逐例修改规则。v2 固定测试集本轮无请求错误。由于标签仍为 `pending_human_review`，该结果当前应表述为“120 条独立离线测试集”，不能表述为“120 条人工标注测试集”。

### TTFT

| 指标 | 强制检索 | Agentic | 变化 |
|---|---:|---:|---:|
| P50 TTFT | 13,187 ms | 8,659 ms | **-34.34%** |
| P95 TTFT | 23,041.25 ms | 20,641.3 ms | -10.42% |
| 成功样本 | 60/60 | 60/60 | 0 失败 |

强制检索每条都真实执行 Embedding、KNN + BM25、RRF 和 Cross-Encoder Rerank，并应用 TEI 返回的精排顺序后返回 top 10；Agentic 组 60 条均直接回答。单并发运行，2 对预热，执行顺序严格 30/30 交替。详细口径见 `results/ttft-direct-60-report-v2-volc.md`，全部哈希与运行条件见 `results/evidence-bundle-v2-volc.json`。

上述数字只代表当前模型、网络和刻意平衡测试集的一次可复现运行。简历可以写实测 P50，但面试时应主动说明路由标签仍需真人复核、每条 TTFT 只重复 1 次，以及 100% 不代表线上长期零错误。
# 当前策略 Langfuse 路由联调

运行 `./scripts/langfuse/run-routing-evaluation.ps1`，自动冻结 10 条候选样本，调用实际 Java 首轮模型、上传 `route_correct` 并回读云端校验。说明见 `docs/research/2026-10-08-langfuse-routing-evaluation.md`。

默认候选标签待人工复核，不可作为正式简历指标。历史 `route_predict.py` 仍保留历史私有知识边界，不用于当前生产策略联调。当前策略快照、模型参数、样本哈希和每次真实调用均保存在独立运行目录。
