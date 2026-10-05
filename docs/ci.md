# GitHub Actions CI

入口：`.github/workflows/ci.yml`。推送到 `main` / `master`、创建或更新 Pull Request，以及 Actions 页面手动运行时触发。

## 自动检查

两个任务并行执行，任何测试、lint error、类型检查或构建错误都会使对应任务失败。未启用 `continue-on-error` 或自动修复 lint。

| 任务 | 内容 |
| --- | --- |
| Backend tests and build | Java 21，运行所有可独立执行的后端测试并构建 JAR；包括真实本地 HTTP/SSE、取消、并发和资源释放测试 |
| Frontend checks and build | Node 24、pnpm 10.28.0；按锁文件安装，完整只读 lint、TypeScript、聊天/静态 SSE 测试和生产构建 |

使用 Maven/pnpm 缓存，取消同一分支或 PR 的旧运行，并限制任务执行时间。官方 Actions 固定到已核验的完整提交 SHA。GITHUB_TOKEN 仅有 `contents: read`，checkout 不保留 Git 凭据。

成功时可在运行页面下载 `backend-jar`、`frontend-dist`（保留 7 天）。后端测试报告在失败时也上传（保留 14 天）。这些步骤不会部署、发布 Release 或推送代码。

## 凭据与测试边界

默认 CI 无需添加任何 Secrets，无需 GPU 或真实 Gemini API。模型、数据库、缓存、检索等边界由测试替身控制，HTTP/SSE 的传输与 Spring 安全链仍执行真实请求。

本地 `application-local.yml` 被 Git 忽略，不会随源码进入 GitHub；不要将其加入提交或构建产物。CI 使用官方 npm registry；`--frozen-lockfile` 防止安装时重写依赖版本，`--ignore-scripts` 避免配置本机 Git hooks。

自动 CI 的明确例外：

- `SmartPaiApplicationTests.contextLoads` 启动完整正式应用，依赖真实 MySQL、Redis、Elasticsearch、Kafka、MinIO 和初始化配置。CI 命令仅排除此类，不应将自动 CI 通过等同于完整部署验收。
- `ChatStreamingProxyTest` 的两项真实 Nginx 测试保留原有条件开关；没有显式 `chat.acceptance.proxy-url` 时跳过。运行方式和已完成的代理验收见 [SSE 验收记录](eval/chat_stream/README.md)。
- 既有 lint warnings 会显示，error 才阻断 CI。本次修复 6 个既有 error，没有关闭规则或批量修改其他警告。

`ParseServiceTest` 的六项分块用例和 `UploadServicePerformanceTest` 的模拟计算都不使用 Spring 注入，已去掉不必要的整应用启动。注册测试补齐组织标签/缓存依赖模拟，并验证用户角色、密码、私人标签及缓存写入；这些用例仍由 CI 执行。

## 本地复现

项目根目录：

```sh
mvn --batch-mode --no-transfer-progress verify '-Dtest=*,!SmartPaiApplicationTests'
```

前端目录：

```sh
pnpm install --frozen-lockfile --ignore-scripts --registry=https://registry.npmjs.org
pnpm lint:check
pnpm typecheck
pnpm test:chat
pnpm build
```

需要完整应用启动验收时，先按项目 README 配好并启动真实依赖，然后单独执行：

```sh
mvn --batch-mode --no-transfer-progress test -Dtest=SmartPaiApplicationTests
```

Actions 用法参考官方文档：[checkout](https://github.com/actions/checkout)、[setup-java](https://github.com/actions/setup-java)、[setup-node](https://github.com/actions/setup-node)、[pnpm/action-setup](https://github.com/pnpm/action-setup)、[upload-artifact](https://github.com/actions/upload-artifact)。

## 本次本地验证（2026-10-05）

- 后端同等 CI 命令（使用已有 Maven 缓存，附加 `-o`）：228 项，226 通过、0 失败、0 错误、2 项 Nginx 条件测试跳过；JAR 打包成功。
- 前端锁定安装、只读 lint、类型检查、79 项聊天测试、生产构建全部 exit 0。lint 为 0 error、190 项既有 warning。
- 官方 actionlint 1.7.12 校验 exit 0；下载文件已核对发布方 SHA256。未运行其可选 shellcheck/pyflakes 子检查。
- 本地使用 Windows、Java 21.0.7、Node 24.11.1、pnpm 10.28.0。GitHub 的 Ubuntu runner 尚未实际运行，须提交并推送后才能验证远端 Action 下载、缓存及产物上传。
- 当前变更未提交或推送；未启动项目服务。
