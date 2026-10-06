# 去除Flux阶段2结果

日期：2026-10-06。生产聊天已完整切换。

- DeepSeekClient聊天与摘要委托JDK HttpClient；ChatHandler使用普通方法、完整模型结果和增量Consumer回调。
- ChatRequestContext保存普通请求资源；截止时间从注册开始，排队也计时。
- 生成池默认16线程/64队列，发送池16线程/1024队列，保持单发送者、有界事件队列和MVC transportReady。
- 初始发送任务被拒绝时不启动生成；生成任务提交前登记FutureTask，取消及迟到HTTP流可释放。
- 生成成功且事件队列排空后进入COMPLETING，正常清理不取消提交；Redis提交后失败沿用不重复追加规则。
- 相关定向测试189项通过，失败/错误/跳过均为0。新增普通API测试先观察旧接口失败；发送拒绝顺序有独立行为RED→GREEN证明。
- 原模型、聊天、注册表、MVC、浏览器夹具测试已迁移普通回调；保留原协议和行为断言。

此为阶段2结束时的记录；后续完整验证见 [阶段3验收](remove-flux-phase-3-acceptance.md)。Embedding、Reranker、MinerU仍保留WebClient；ReAct/MCP未实施。
