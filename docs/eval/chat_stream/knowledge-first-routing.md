# 事实性问答优先检索

日期：2026-10-06。专用分支：`codex/knowledge-first-routing`，当前基点 `79d7e4d`（包含此前拖拽上传与 Gemini 签名修复）。用户确认“事实性问题默认先检索，寒暄、纯计算、翻译和改写可直接回答；检索未命中时明确说明，不猜测同名人物”。本次改动尚未提交。

## 问题和调整

“苏哲是谁”直接返回同名文学角色，原因是系统提示词和工具说明同时强调“仅检索明确指定资料”“通用知识直接回答”“不要因为不确定而调用工具”。模型因此没有查询用户上传的人物资料。

修改 `application.yml` 的系统规则和 `KnowledgeBaseSearchTool` 的工具说明：

- 人物身份、组织、产品、项目、数据、概念和技术问题默认先检索，无需用户额外指定“根据知识库”。提取核心姓名或主题，例如先查询“苏哲”。
- 命中后判断资料与问题是否相关，按资料回答并引用；不把同名人物混为一人，不用模型记忆补齐资料缺失的事实。
- 没有依据时说明知识库未找到相关信息，可追问或建议补充文件；不拿公众人物、文学角色或无关文档补答案，不编造引用。
- 纯寒暄、纯计算、翻译和输入文本改写可以直接完成。

沿用模型自动选择工具和查询的 ReAct 循环，不增加 Java 关键词路由，也不改权限、检索预算、签名传递和保存逻辑。事实性问答通常增加检索和下一轮模型请求，因此会比直接回答慢；返回资料继续受用户权限和上下文预算约束。

## 真实模型验收

`LiveGeminiKnowledgeFirstTest` 使用实际生产 YAML 规则、Java 工具定义及 `gemini-3.1-flash-lite`。文档边界使用合成人物与技术说明；没有把私有 PDF 用在这些用例中。测试须同时提供临时 key 环境变量和 `-Dchat.gemini.live=true`，普通回归默认关闭付费调用。

先复现旧规则：5 项中 2 项失败，人物命中和技术问题跳过检索。修改后 5/5 通过：

| 问题 | 检索 | 行为 |
| --- | --- | --- |
| 苏哲是谁 | 1 次，query=苏哲 | 根据合成人物资料回答，引用联调人物.pdf，没有琅琊榜解释 |
| 青岚联调人物九号是谁 | 1 次 | 明确未找到相关信息，未编造来源 |
| 什么是 ReAct | 1 次 | 根据合成技术说明回答并引用 |
| 你好 | 0 次 | 直接回复 |
| 计算 7 乘 8 | 0 次 | 直接给出 56 |

结果：[人物命中](knowledge-first-person-hit.json)、[人物未命中](knowledge-first-person-miss.json)、[技术事实](knowledge-first-technical-fact.json)、[寒暄](knowledge-first-greeting.json)、[计算](knowledge-first-calculation.json)。先失败后通过日志：`.superpowers/sdd/2026-10-06-knowledge-base-react/knowledge-first-red.log` 和 `knowledge-first-green.log`。

该策略由提示词和工具说明引导模型，未对所有模型或所有输入证明百分之百检索。旧 `docs/eval/agent_routing` 的 v1/v2 标签和模型报告保留为历史记录，不能用来评估新策略；已在该目录 README 标注重新复核标签要求。

## 当前项目验收

完整后端回归：319 项，314 通过，0 失败/错误，5 项条件跳过（2 项 Nginx、2 项原真实 Gemini 用例及 1 个关闭的参数化路由容器；该容器的 5 个真实路由场景已在上文单独全部通过）。`mvn -DskipTests package` 成功；停止本次启动的旧后端后打包，避免 Windows jar 占用。新后端启动在 8081，前端继续在 9527。

用户此前明确授权将该 PDF 的检索片段发给当前 Gemini 做验证。本次通过前端 `/proxy-default`、正常管理员登录和真实 MVC/Embedding/Elasticsearch 链路，在新验证会话输入原句“苏哲是谁”：

- 1 次工具检索开始/结束，日志确认命中用户上传 PDF 的块。
- intermediate → final，HTTP 200、completion=finished、无 error。
- 最终回答 881 字符，带来源引用，没有琅琊榜、梅长苏、林殊或江左盟解释；与读取的会话记录完全相同，只保存一次。
- 单次总耗时 15118ms；验证登录正常退出，报告不输出令牌或 PDF 正文。[运行验收记录](knowledge-first-runtime.json)。

最终日志：`.superpowers/sdd/2026-10-06-knowledge-base-react/knowledge-first-full.log`、`knowledge-first-package.log`、`app-backend-knowledge-first.out.log`。前端地址：`http://127.0.0.1:9527/`。
