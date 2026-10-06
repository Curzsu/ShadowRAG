# 去除Flux阶段0基线

日期：2026-10-06。基线提交：`a96b80d`，分支：`codex/backend-chat-sse`。
执行前已有设计文档修改和三个未跟踪计划/接续设计，均保留。此阶段未修改生产代码。

- Java实际运行：Oracle JDK21.0.7；Maven3.9.11。项目编译使用release17，没有使用Java21 API。
- Node实际安装：E:/nodejs/node.exe，24.11.1；默认node命令指向不可访问的system32入口。pnpm包装器读用户corepack缓存被沙箱拒绝，因此使用已安装tsx执行package.json的test:chat同一组命令。
- 后端计划阶段0定向测试：169项，失败0、错误0、跳过0；读取Surefire结果，BUILD SUCCESS。
- 前端聊天测试：79项，失败0、跳过0。
- 第一次受限环境的Maven运行在testCompile报项目类不可见；主类文件存在且javap可读。在获准的沙箱外环境运行相同命令通过，无代码修复，记录为环境问题，不算业务基线失败。
- 模型测试使用127.0.0.1本地模拟供应商；没有调用付费模型或依赖生产基础设施。

完整输出保留在`.superpowers/sdd/2026-10-06-remove-flux-chat/phase-0-backend-retry.log`与`phase-0-frontend.log`。可进入阶段1；此时生产聊天仍使用Flux。
