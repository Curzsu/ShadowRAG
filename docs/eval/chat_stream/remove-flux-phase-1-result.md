# 去除Flux阶段1结果

日期：2026-10-06。已增加ChatGenerationResources、ModelSseReader、ModelHttpProperties、BlockingModelHttpClient和ModelRoundResult。

- 新组件及原聊天基线：205项测试通过，失败/错误/跳过均为0。
- 已验证真实增量输出、DONE后立即关闭、正文/响应头前取消、静默正文总超时、UTF-8、SSE限额、摘要限额、/v1路径、可选认证、HTTP代理转发及HTTPS CONNECT取消。
- 新接口测试先观察未实现导致的编译失败；取消异常分类测试观察行为失败后修正，再通过。
- JDK对HTTP目标使用标准代理转发，对HTTPS使用CONNECT。原代理配置校验不变。
- HTTP读取另有共享2线程daemon截止时间定时器，确保摘要和独立客户端调用的静默正文也受总期限约束，完成后移除定时任务。

生产入口此时仍为旧聊天实现。阶段2将一次切换客户端、编排、请求生命周期、线程池和测试夹具；全项目WebClient清理不在本阶段范围。
