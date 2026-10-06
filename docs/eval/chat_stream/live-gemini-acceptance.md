# Gemini 真实 API 联调

日期：2026-10-06。分支：`codex/knowledge-base-react`。用户授权使用提供的 Gemini 凭据进行真实 API 测试；凭据仅用于临时进程环境，没有写入项目配置、测试源码或报告。

## 实际结果

项目链路使用 Gemini 的 OpenAI 兼容接口，模型为 `gemini-2.5-flash-lite`。通过项目的 `DeepSeekClient`、`BlockingModelHttpClient` 和 `AgentLoopService` 发出真实流式请求；类名仍为 DeepSeekClient，但本次请求目标为 Gemini。仅知识库检索边界使用合成报告。

| 场景 | 模型请求 | 检索 | 回合 | 最终回答 | 单次耗时 |
| --- | --- | --- | --- | --- | --- |
| 普通对话 | 1 | 0 | final | 联调成功。 | 1082ms |
| 连续查询报告 A、B | 3 | 2 | intermediate → intermediate → final | 2025 年营业收入 100 万元，2026 年 120 万元，增长率 20%，引用报告 A/B | 3838ms |

真实模型按提示要求先查询 `报告A`，收到工具结果后再查询 `报告B`。测试同时检查实际后续请求中的 assistant/tool 消息使用供应商原始调用 ID 配对。2/2 真实模型用例通过；以上耗时是本机单次样本，不是性能指标。

完整无密钥结果：[普通对话](live-gemini-direct.json)、[连续检索](live-gemini-two-searches.json)。

用户示例的 `antigravity-preview-09-2026` 也通过原生 Interactions API 单独探测：使用所给三种内置工具及 remote/network-disabled 环境，后台任务进入 `completed`，供应商返回总 token 数 4983。本次没有提取其最终文本，不据此评价回答内容。该托管 Agent 与项目自己执行工具的 ReAct 循环是不同接入方式；上述项目测试使用兼容接口，并未将 ShadowRAG 改为调用托管 Agent。

## 联调发现和修复

1. 本机 PowerShell 使用系统代理 `http://127.0.0.1:7890`，而 Java 客户端没有显式代理时直接连接，首次真实 Java 请求连接超时。联调测试通过可选环境变量显式传入代理。测试传入的 URL 也改为 base URL，避免客户端再次追加 `/chat/completions`。项目永久配置未更改。
2. Gemini 的实际工具调用帧不带 tool-call `index`，但一次返回完整 `id/type/function.name/function.arguments`。原解析器报 `Invalid tool index`，连续检索无法继续。
3. 解析器现在兼容单个完整的无 index 调用批次，按数组顺序分配内部序号，保留供应商原始 ID。仍拒绝缺失 ID、非完整 JSON 参数、混用分片协议、重复 ID 和后续含工具调用的歧义批次；原有带 index 的增量协议继续保留。

回归先复现失败，再修复：24 项定向测试中 2 项接受完整无 index 调用的测试失败；修复后，协议、实际下一轮 HTTP 消息配对及原流式客户端的 42 项定向测试全部通过。随后重新执行真实 API，两种场景均通过。

兼容修改后的完整后端回归：314 项，310 通过，0 失败/错误，4 项条件跳过（2 项真实 API 用例已另外运行并通过，2 项 Nginx 用例的此前结果见原验收报告）。普通完整回归未开启付费 API 开关。

随后 `mvn -DskipTests package` 成功，重新生成含本次兼容修复的可执行 jar。改动尚未提交、推送或部署。失败、修复及成功日志保留在 `.superpowers/sdd/2026-10-06-knowledge-base-react/`，包括 `gemini-index-red.log`、`gemini-index-green.log`、`live-gemini-final.log`、`gemini-full-regression.log` 和 `gemini-package.log`。

## 复现

测试默认关闭，必须同时设置显式开关和临时密钥环境变量才会发出真实付费请求。`SHADOWRAG_LIVE_GEMINI_MODEL` 可省略，默认使用上述模型；代理变量仅在当前网络需要时设置。

```powershell
# 先在当前终端设置 SHADOWRAG_LIVE_GEMINI_KEY，不要把实际密钥写入仓库。
$env:SHADOWRAG_LIVE_GEMINI_MODEL = 'gemini-2.5-flash-lite'
$env:SHADOWRAG_LIVE_GEMINI_PROXY = 'http://127.0.0.1:7890'
try {
    mvn '-Dchat.gemini.live=true' '-Dtest=LiveGeminiReActTest' '-Dchat.acceptance.metrics-dir=.superpowers/sdd/2026-10-06-knowledge-base-react' test
} finally {
    Remove-Item Env:SHADOWRAG_LIVE_GEMINI_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:SHADOWRAG_LIVE_GEMINI_MODEL -ErrorAction SilentlyContinue
    Remove-Item Env:SHADOWRAG_LIVE_GEMINI_PROXY -ErrorAction SilentlyContinue
}
```

每次模型请求上限 1024 token，整个循环共用 90 秒期限，测试有 120 秒保护。托管 Agent 探测另外设置总 token 预算 10000。本次结束后临时凭据环境已清除。

用户 Python 示例中的 `os.environ.get(...)` 参数应为环境变量名，而不是密钥值。例如：`api_key=os.environ["GEMINI_API_KEY"]`，并预先设置该环境变量。

## 验证边界

本次证明真实 Gemini 的流式协议和项目连续工具循环可以配合工作。检索数据为合成资料，不包含用户私有文档；本次真实 API 用例不经过浏览器/MVC 保存链路，也不验证真实 Elasticsearch、权限过滤或数据库保存。权限、最终保存、取消和代理行为的确定性验收见 [R1～R3 验收](knowledge-base-react-acceptance.md)。其他 Gemini 模型版本与生产并发未做此项真实测试。

参考官方文档：[OpenAI 兼容接口](https://ai.google.dev/gemini-api/docs/openai)、[Antigravity Agent](https://ai.google.dev/gemini-api/docs/antigravity-agent)。
