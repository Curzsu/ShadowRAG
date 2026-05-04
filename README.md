# ShadowRAG

ShadowRAG 是一个企业级 AI 知识管理系统，基于 RAG（检索增强生成）技术构建，提供智能文档处理与语义检索能力。

用户上传文档后，系统自动完成解析、分块、向量化并存入 Elasticsearch，随后可通过自然语言对话精准检索文档内容，获得基于文档的 AI 生成回答。

## 功能特性

- **文档管理**：支持多种格式文档上传，自动解析与索引，支持分片上传和断点续传
- **Agentic RAG**：LLM 通过 Function Calling 自主决定是否搜索知识库，两阶段工具调用流程
- **混合检索 + 精排**：KNN 向量检索 + BM25 全文检索 → Java 端 RRF 融合 → Cross-Encoder 精排
- **长记忆异步压缩**：ConcurrentHashMap 会话级任务去重 + CompletableFuture 异步压缩早期对话为摘要，Redis Lua 脚本原子头部替换，软硬双阈值 + 失败重试 + 同步截断兜底
- **AI 对话**：集成 DeepSeek LLM，通过 WebSocket 实时流式输出回答
- **多租户隔离**：基于组织标签的数据隔离，支持公开/私有文档权限控制
- **异步处理**：Kafka 驱动的文档异步解析与向量化流水线
- **文档解析**：MinerU 优先解析（支持 PDF/图片等复杂排版），Tika 自动回退

## 技术栈

### 后端

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 3.4.2 (Java 17+) |
| 数据库 | MySQL 8.0 + Spring Data JPA |
| 缓存 | Redis 7.0.11 |
| 搜索引擎 | Elasticsearch 8.10.0 |
| 消息队列 | Apache Kafka 3.2.1 |
| 文件存储 | MinIO 8.5.12 |
| 文档解析 | MinerU / Apache Tika 2.9.1 |
| 安全认证 | Spring Security + JWT |
| LLM | DeepSeek API / 本地 Ollama |
| Embedding | Ollama bge-m3（1024 维） |
| Reranker | HuggingFace TEI bge-reranker-v2-m3 |
| 实时通信 | WebSocket |
| 响应式 | Spring WebFlux |
| 中文处理 | HanLP 1.8.6 |

### 前端

| 类别 | 技术 |
|------|------|
| 框架 | Vue 3 + TypeScript 5.8 |
| 构建工具 | Vite 6.3 |
| UI 组件 | Naive UI 2.41 |
| 状态管理 | Pinia 3.0 |
| 路由 | Vue Router 4.5 |
| 样式 | UnoCSS + SCSS |
| 图表 | ECharts 5.6 |
| 图标 | Iconify |
| Markdown 渲染 | Markdown-it + KaTeX |

## 系统架构

### RAG 核心流程

```
文档上传 → MinerU/Tika 解析 → 文本分块 → BGE-M3 向量化 → 存入 Elasticsearch
                                                                    ↓
用户提问 → WebSocket → LLM 自主决策是否搜索（Agentic RAG）
                            ↓ 是
                      KNN + BM25 检索 → RRF 融合 → Cross-Encoder 精排 → LLM 生成回答（流式输出）
                            ↓ 否
                      LLM 直接回答
```

### 长记忆压缩流程

```
每轮对话结束 → 写入 Redis → syncToMySQL → 检查阈值
                                              ↓
                                    消息 < 30 条 → 跳过
                                    消息 ≥ 30 条 + Token < 50000 → 异步压缩（LLM 摘要 + Lua 原子替换）
                                    Token ≥ 50000 → 同步截断（保留近 6 轮）
```

### 项目结构

```
ShadowRAG/
├── src/main/java/com/yizhaoqi/smartpai/   # 后端
│   ├── SmartPaiApplication.java            # 应用入口
│   ├── client/                             # 外部 API 客户端（DeepSeek, Embedding, MinerU）
│   ├── config/                             # 配置类（Security, JWT, ES, Kafka, MinIO, Redis, WS）
│   ├── consumer/                           # Kafka 消费者（异步文档处理）
│   ├── controller/                         # REST API 控制器
│   ├── entity/                             # JPA 实体
│   ├── exception/                          # 自定义异常
│   ├── handler/                            # WebSocket 处理器（AI 对话）
│   ├── model/                              # 领域模型 / DTO
│   ├── repository/                         # 数据访问层
│   ├── service/                            # 业务逻辑层
│   └── utils/                              # 工具类
├── frontend/                               # 前端
│   ├── src/
│   │   ├── assets/                         # 静态资源
│   │   ├── components/                     # Vue 组件（common/advanced/custom）
│   │   ├── layouts/                        # 页面布局
│   │   ├── router/                         # 路由配置
│   │   ├── service/                        # API 集成
│   │   ├── store/                          # Pinia 状态管理
│   │   ├── views/                          # 页面组件
│   │   ├── hooks/                          # Composition API Hooks
│   │   ├── enum/                           # TypeScript 枚举
│   │   └── constants/                      # 常量
│   └── ...
├── docs/                                   # Docker Compose 部署配置
├── pom.xml                                 # Maven 依赖
└── README.md
```

## 快速开始

### 前置条件

- Java 17+（推荐 Java 21）
- Maven 3.8.6+
- Node.js 18.20.0+
- pnpm 8.7.0+
- Docker & Docker Compose（用于运行基础设施服务）
- Ollama（用于本地 Embedding 模型，需拉取 bge-m3）

### 1. 启动基础设施服务

```bash
cd docs && docker-compose up -d
```

这将启动以下服务：

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL | 3307（容器内 3306） | 主数据库，密码 `123456` |
| Redis | 6379 | 缓存 |
| Elasticsearch | 9200 | 搜索与向量存储 |
| Kafka | 9092 | 消息队列 |
| MinIO | 19000 / 19001 | 文件存储（API / 控制台） |
| MinerU | 8000 | 文档解析（需 GPU） |

### 2. 启动本地 Embedding 服务

```bash
# 拉取并运行 bge-m3 模型
ollama pull bge-m3
ollama serve
```

### 3. 启动后端

```bash
mvn spring-boot:run
```

后端运行在 `http://localhost:8081`。

### 4. 启动前端

```bash
cd frontend && pnpm install && pnpm dev
```

### 5. 访问应用

浏览器打开 `http://localhost:9527`，使用默认管理员账号登录：

- 用户名：`admin`
- 密码：`REDACTED`

## 配置说明

主要配置文件为 `src/main/resources/application.yml`，关键配置项：

| 配置项 | 说明 |
|--------|------|
| `deepseek.api.url` | LLM API 地址，支持官方 API 或本地 Ollama |
| `deepseek.api.model` | 模型名称，如 `deepseek-chat` 或 `deepseek-r1:7b` |
| `embedding.api.url` | Embedding 服务地址（Ollama） |
| `embedding.api.model` | Embedding 模型名称，默认 `bge-m3` |
| `mineru.api.url` | MinerU 文档解析服务地址 |
| `mineru.api.enabled` | 是否启用 MinerU，`false` 时回退到 Tika |
| `file.parsing.chunk-size` | 文本分块大小，默认 512 字符 |
| `ai.compression.soft-threshold` | 压缩软阈值，默认 30 条消息触发异步压缩 |
| `ai.compression.hard-threshold-token` | 压缩硬阈值，默认 50000 Token 触发同步截断 |
| `ai.compression.keep-rounds` | 压缩时保留最近对话轮数，默认 6 轮 |
| `reranker.api.enabled` | 是否启用 Cross-Encoder 精排 |

## 架构设计

### 分层架构

系统采用经典的分层架构，职责清晰：

- **Controller 层**：处理 HTTP 请求，参数校验，委托给 Service 层
- **Service 层**：业务逻辑核心，事务管理，协调多个 Repository 和外部服务
- **Repository 层**：Spring Data JPA 数据访问，支持自定义查询
- **Entity 层**：JPA 实体映射数据库表

### 多租户设计

通过组织标签（OrganizationTag）实现租户隔离：

- 每个用户可属于多个组织
- 每个组织拥有独立的知识库
- 文档支持公开或绑定到特定组织
- 查询时自动过滤用户可见的文档范围

### 异步处理流程

文档上传后的处理流程通过 Kafka 异步执行：

```
文件上传 → Kafka 消息 → 消费者接收 → MinerU/Tika 解析 → 文本分块 → 向量化 → ES 索引
```

## 构建部署

### 构建

```bash
# 后端打包
mvn clean package

# 前端构建
cd frontend && pnpm build
```

### Docker 部署

```bash
# 启动所有服务
cd docs && docker-compose up -d

# 查看服务状态
docker-compose ps

# 停止所有服务
docker-compose down
```

## License

本项目仅供学习和研究使用。
