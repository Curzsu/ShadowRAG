# 部署指南

> 与本分支代码同步。主要依据：[应用配置](../src/main/resources/application.yml)、[基础 Compose](docker-compose.yaml)、[GPU 覆盖配置](docker-compose.gpu.yaml)、[前端配置](../frontend/vite.config.ts)。本指南描述操作与现有边界，不代表完成生产验收。

## 先明确运行方式

当前 Compose 启动依赖服务，Java 后端与 Vue 前端分别启动；没有打包完整应用、Nginx 或 Langfuse 服务，也没有独立网关。默认依赖地址按“后端运行在宿主机”配置；将后端放入容器时需重新配置地址，不能继续把容器内 localhost 当成宿主机。

后端使用 Java 17 语言级别，当前 CI 使用 Java 21；前端本地建议与 CI 一致使用 Node 24、pnpm 10.28.0。需要 Maven 和 Docker Compose。已有服务可复用，不必重复启动占用相同端口的容器。

| 服务 | 默认宿主机入口 |
| --- | --- |
| 后端 / 前端开发服务 | 8081 / 9527 |
| MySQL / Redis | 33060 / 6379 |
| Elasticsearch / Kafka | 9200 / 9092 |
| MinIO API / 控制台 | 19000 / 19001 |
| Ollama / TEI Reranker / MinerU | 11434 / 8082 / 8000 |
| 可选 Nginx 示例 | 8080，需要自行安装启动 |

## 准备依赖与配置

从项目根目录执行。首次先检查 Compose 中的口令、宿主机挂载目录及资源需求，不直接用于公网部署。

```sh
docker compose -f docs/docker-compose.yaml up -d mysql redis es kafka minio
```

数据库首次初始化使用 [init-db.sql](init-db.sql)，已有数据库卷不会重复执行初始化。Compose 的 Elasticsearch 会安装匹配版本的 IK 插件，需要下载条件。当前 ES 配置的 2 GB 堆与 2 GB 容器内存上限相同，需按实际可用内存留出堆外余量后再作为部署配置。

模型与解析服务按机器能力选择：

- 基础 Compose 的 TEI 使用 CPU 镜像；Ollama 和 MinerU 均声明 NVIDIA GPU 设备。直接启动全套并不是纯 CPU 路径。
- 有对应 GPU 环境时，可用基础 Compose 启动 `ollama mineru tei-reranker`。Ollama 首次启动会拉取 `bge-m3`，镜像和模型下载需要时间。
- 无 GPU 时，另行准备 CPU Ollama 或兼容的向量服务，保持 `embedding.api.url`、模型与 1024 维配置一致；将本地配置中的 `mineru.api.enabled` 设为 `false` 走 Tika；TEI 可用基础 Compose 的 CPU 服务。不要直接启动其中申请 GPU 的服务。
- MinerU 的其他部署形态见 [独立 Compose](compose-mineru.yaml)，该文件使用 profiles 和本地 `mineru:latest` 镜像，不会被基础 Compose 自动加载。

复制公开模板并在本地填写凭据：

```powershell
Copy-Item application-local.yml.example application-local.yml
```

Linux/macOS 使用 `cp application-local.yml.example application-local.yml`。模板见[本地配置示例](../application-local.yml.example)。后端从**启动工作目录**读取 `./application-local.yml`，所以以下后端命令从项目根目录执行。

必须配置模型 API Key、JWT Base64 密钥和 ES 密码；也可使用 `DEEPSEEK_API_KEY`、`JWT_SECRET_KEY`、`ES_PASSWORD`。JWT 密钥解码后至少 32 字节。MySQL、Redis、MinIO 凭据须与实际服务一致，模型 URL／模型名称按供应商配置，不能只改 Key。公开配置中的开发口令和默认管理员密码上线前应替换；私有文件不提交。

## 启动与前端发布

```sh
mvn spring-boot:run
```

前端在另一终端启动：

```sh
cd frontend
pnpm install --frozen-lockfile --ignore-scripts
pnpm dev
```

开发地址为 `http://localhost:9527`。`pnpm dev` 加载 test 模式，目标地址见 [test 环境配置](../frontend/.env.test)，开发代理由[代理配置](../frontend/build/config/proxy.ts)建立。开发代理不随构建产物发布。

发布时在前端目录执行 `pnpm build`，产物为 `frontend/dist/`。prod 模式默认 API 为 `/api/v1`，见 [prod 环境配置](../frontend/.env.prod)，需要静态服务器同时将 API 请求转发到后端。开发登录账号默认为 `admin`，密码和初始化行为以本地覆盖配置及[管理员初始化](../src/main/java/com/yizhaoqi/smartpai/config/AdminUserInitializer.java)为准。

## 可选 GPU 精排

[GPU 覆盖配置](docker-compose.gpu.yaml)只覆盖 TEI，示例针对已有 RTX 50 系列验证配置。需要 Docker NVIDIA 运行时、兼容驱动和足够显存；不是所有 GPU 的通用配置。

```sh
docker compose -f docs/docker-compose.yaml -f docs/docker-compose.gpu.yaml up -d --no-deps tei-reranker
```

回退 CPU：

```sh
docker compose -f docs/docker-compose.yaml up -d --no-deps tei-reranker
```

端口仍是 8082，不需要因此改后端 URL。切换和镜像拉取会影响服务，执行前确认当前任务；单次验证数据保留在[GPU 对照记录](research/2026-10-09-reranker-gpu-validation.md)，不能等同于聊天首字延迟改善。

## 可选 Nginx 与上线边界

[nginx.conf](nginx.conf)是需手动安装、加载的配置片段。先将静态目录改成实际 `frontend/dist/`，检查后端地址；示例监听 8080，没有 TLS 配置。

聊天端点要关闭 `proxy_buffering`、`proxy_cache` 和 gzip，使用 HTTP/1.1 并清除 Connection 头。示例读超时 90 秒，在默认 15 秒心跳下允许长检索空窗；它是两次读之间的空闲超时，不代替应用的 5 分钟生成截止时间。普通 `/api/` 也应转发到后端。

当前权限主要依赖 JWT、组织标签与查询过滤，不是独立租户数据库／索引的物理隔离。请求去重和会话生成锁是单实例内存状态，多副本前需另行协调。公网发布前需落实 TLS、实际口令、访问范围和依赖服务健康验证；诊断页 `/test.html` 是否公开也应按部署用途决定。

以上操作需要手动执行。自动测试与完整依赖验收的区别见 [CI 说明](ci.md)，追踪启用见[可观测指南](observability.md)。
