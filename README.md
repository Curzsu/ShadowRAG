# ShadowRAG

ShadowRAG 是一个组织知识库问答项目，基于 Spring Boot 和 Vue，支持文档上传、异步解析、混合检索、可选精排、多轮工具调用和 POST SSE 流式聊天。

**使用说明从 [文档索引](docs/index.md) 进入。** 当前指南与所在分支代码同步；设计计划、研究和验收记录保留各自的历史范围。

## 主要能力

- 文档流水线：分片上传到 MinIO，经 Kafka 异步处理，使用 MinerU／Tika 解析、切片与建索引。
- 混合检索：Elasticsearch KNN + BM25，在应用侧进行 RRF 融合；可用 TEI Cross-Encoder 精排，失败时降级。
- Agentic RAG：模型通过 `search_knowledge_base` 决定检索和继续推理，受工具轮次、调用次数和超时预算约束。
- 对话与记忆：MySQL 保存完整轮次，Redis 维护工作集；结合上下文预算与增量摘要处理长对话。
- 流式聊天：Spring MVC `SseEmitter`，支持事件序号、心跳、取消和终态处理，详见[聊天指南](docs/chat.md)。
- 可选追踪与评测：手动 OpenTelemetry 埋点导出到 Langfuse，包含模型与检索阶段耗时、状态，以及检索和首轮路由评测入口。实际 Token／费用核算尚未完整接入，详见[可观测指南](docs/observability.md)。
- 权限：JWT 与组织标签访问控制，检索按可见范围过滤；当前不应表述为租户数据库或索引的物理隔离。

## 技术与结构

后端使用 Java 17 语言级别、Spring Boot 3.4.2；CI 使用 Java 21。依赖 MySQL、Redis、MinIO、Kafka、Elasticsearch；向量默认使用 Ollama `bge-m3`（1024 维），精排使用 TEI。前端为 Vue 3、TypeScript、Vite、Naive UI 和 Pinia。版本与实际依赖以 [pom.xml](pom.xml)、[前端清单](frontend/package.json)及锁文件为准。

| 路径 | 用途 |
| --- | --- |
| `src/` | 后端源码与测试 |
| `frontend/` | 前端源码、流式客户端与测试 |
| `docs/` | 当前指南、部署配置和历史资料，见[索引](docs/index.md) |
| `scripts/` | 评测与维护工具 |
| `local/`、`logs/` | 本地笔记、临时文件与日志，Git 忽略，分类见[文档索引](docs/index.md) |

## 本地快速开始

以下命令从项目根目录执行；需准备 Java、Maven、Docker，以及前端 Node 24／pnpm 10.28.0。已有依赖服务可直接复用。

1. 启动基础依赖：

   ```sh
   docker compose -f docs/docker-compose.yaml up -d mysql redis es kafka minio
   ```

2. 准备向量、精排和解析服务。基础 Compose 的 Ollama、MinerU 申请 NVIDIA GPU，TEI 默认 CPU；无 GPU 时按[部署指南](docs/deployment.md)准备 CPU 向量服务并关闭 MinerU、使用 Tika，不直接启动全套。
3. 复制 [application-local.yml.example](application-local.yml.example) 为根目录 `application-local.yml`，填写模型、JWT、ES 及实际依赖凭据。模型地址与名称也须匹配供应商；私有文件不提交。
4. 启动后端：

   ```sh
   mvn spring-boot:run
   ```

5. 另开终端启动前端：

   ```sh
   cd frontend
   pnpm install --frozen-lockfile --ignore-scripts
   pnpm dev
   ```

前端默认 `http://localhost:9527`，后端默认 `http://localhost:8081`。管理员账号默认 `admin`，密码以配置为准，上线前修改开发凭据。

## 后续操作

- [部署指南](docs/deployment.md)：配置加载、端口、CPU/GPU 精排、前端构建与可选 Nginx。当前 Compose 只编排依赖，不自动启动后端、前端或 Nginx。
- [可观测指南](docs/observability.md)：Langfuse 开关、数据边界、TTFT 口径、检索与路由评测命令。路由脚本支持 `Dataset`、`RunId`，没有 `Limit` 参数；真实评测会访问外部服务并可能产生费用。
- [CI 说明](docs/ci.md)：本地检查、自动任务和真实依赖验收边界。
- [变更记录](CHANGELOG.md)与[文档维护约定](AGENTS.md)：功能变化与文档同步要求。

当前没有独立网关、ELK 或完整指标告警平台。请求去重和会话生成锁是单实例保证，生产可用性及多副本协调需要按实际部署验证。

## 开源协议

本项目仅供学习与技术交流使用。
