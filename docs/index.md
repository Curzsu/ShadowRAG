# 文档索引

> 当前指南与本分支代码同步。设计、计划和实验结论有各自的适用范围，不代表当前功能或完整上线验收。

## 当前指南

| 你要做什么 | 入口 |
| --- | --- |
| 了解项目、开始本地开发 | [项目 README](../README.md) |
| 准备依赖、配置、CPU/GPU 与前端发布 | [部署指南](deployment.md) |
| 理解聊天接口、流式事件、取消与保存 | [聊天指南](chat.md) |
| 启用 Langfuse、理解追踪与运行评测 | [可观测指南](observability.md) |
| 运行测试和文档检查、理解 CI 边界 | [CI 说明](ci.md) |
| 修改项目时同步维护文档 | [维护约定](../AGENTS.md) |
| 查看使用者可感知的变化 | [变更记录](../CHANGELOG.md) |

## 设计、计划与历史资料

以下资料用于追溯背景和证据，使用方法优先以上方指南为准。目录中的材料不保证全部适用于当前代码。

- [设计记录](superpowers/specs/)与[实施计划](superpowers/plans/)：包含已实施和待实施内容，以各文档范围和当前代码为准。
- [研究与实验记录](research/)：单次调研、接入过程和部署对照；历史数字不能直接外推。
- [聊天验收记录](eval/chat_stream/README.md)：保留各阶段测试范围、已知限制和原始证据入口。
- [路由评测资产](eval/agent_routing/README.md)：含历史策略与候选标签，正式准确率需先确认策略和人工标注。
- [检索评测教程](retrieval-evaluation-tutorial.md)：使用前核对数据、配置与当前评测入口。
- [旧 RAG 评估](Agentic_RAG_评估报告.md)、[面试说明](interview-prep.md)和[面试资料目录](interview/)：含历史实现、审计与表达材料，不能直接作为当前能力清单。
- [文档维护方案](superpowers/plans/2026-10-09-documentation-maintenance.md)：本轮整理范围与进度。

新主题确实需要独立维护时再建立指南并加入上方表格。`eval/`、`interview/`、`research/` 中有脚本读取或写入的路径，整理前先检查依赖。

## 仓库文件放在哪里

| 位置 | 放什么 |
| --- | --- |
| 项目根目录 | README、AGENTS、CHANGELOG、LICENSE、`pom.xml`，以及需要从根目录加载的本地配置和工具入口 |
| `docs/` | 公开指南、历史设计和评测资料、现有部署配置 |
| `scripts/maintenance/` | 辅助维护工具；原根目录 `translate_review.py` 已移入，运行前仍需检查其个人输入输出路径 |
| `local/notes/`、`local/reviews/` | 本地笔记、旧问题清单和生成脚本，Git 忽略 |
| `local/scripts/`、`local/samples/` | 临时测试脚本和样本，Git 忽略 |
| `local/scratch/` | 意外命名的副本、空文件，保留待确认，Git 忽略 |
| `logs/archive/` | 原根目录的历史日志和测试输出，Git 忽略 |

私有配置 `application-local.yml`、`.env.langfuse.local` 和本地 AI 工具入口 `CLAUDE.md` 保留原位置，避免改变启动与工具发现行为。本地归档说明和移动清单放在 `local/` 内，不加入公开导航链接。新增临时产物放到这些目录，避免继续堆在根目录。
