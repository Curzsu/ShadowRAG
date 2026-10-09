# GitHub Actions CI

> 与本分支代码同步。以下自动检查描述工作流配置；文末的本地验证是历史记录，不代表本次运行或远端 CI 已通过。统一入口见[文档索引](index.md)。

入口：`.github/workflows/ci.yml`。推送到 `main` / `master`、创建或更新 Pull Request，以及 Actions 页面手动运行时触发。

## 自动检查

三个任务并行执行，任何文档错误、测试、lint error、类型检查或构建错误都会使对应任务失败。未启用 `continue-on-error` 或自动修复 lint。

| 任务 | 内容 |
| --- | --- |
| Documentation links | 独立 Node 24 任务；锁定安装 Markdown 解析依赖，验证检查器，再扫描全部受管文档的本地链接与必要入口 |
| Backend tests and build | Java 21，运行所有可独立执行的后端测试并构建 JAR；包括真实本地 HTTP/SSE、取消、并发和资源释放测试 |
| Frontend checks and build | Node 24、pnpm 10.28.0；按锁文件安装，完整只读 lint、生产构建、TypeScript 和聊天/静态 SSE 测试 |

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
pnpm build
pnpm typecheck
pnpm test:chat
```

生产构建必须先于类型检查：Vite 插件会生成被 Git 忽略的 `frontend/src/typings/auto-imports.d.ts` 和 `components.d.ts`。全新检出没有这两个文件，直接运行 `pnpm typecheck` 会报 `ref`、`defineStore` 等名称未定义；已有本地开发产物会掩盖此问题。类型检查和测试仍是必过步骤，全部通过后才上传前端产物。

需要完整应用启动验收时，先按项目 README 配好并启动真实依赖，然后单独执行：

```sh
mvn --batch-mode --no-transfer-progress test -Dtest=SmartPaiApplicationTests
```

Actions 用法参考官方文档：[checkout](https://github.com/actions/checkout)、[setup-java](https://github.com/actions/setup-java)、[setup-node](https://github.com/actions/setup-node)、[pnpm/action-setup](https://github.com/pnpm/action-setup)、[upload-artifact](https://github.com/actions/upload-artifact)。

## 文档检查

无需启动业务服务、GPU 或模型 API。从项目根目录执行：

```sh
npm --prefix scripts/docs ci --ignore-scripts --no-audit --no-fund
npm --prefix scripts/docs test
npm --prefix scripts/docs run check
# 只读报告：显示问题及豁免，存在问题时仍返回成功；不能代替上面的阻断检查。
npm --prefix scripts/docs run report
```

实现见[检查脚本](../scripts/docs/check-links.mjs)和[行为用例](../scripts/docs/check-links.test.mjs)。复用 [markdown-it](https://github.com/markdown-it/markdown-it) 解析链接，检查范围为根 README、AGENTS、CHANGELOG 及 Git 跟踪的 `docs/**/*.md`；本地同时纳入未忽略的新文件，便于提交前检查。删除文件后仍扫描其他文档，因此能发现未修改来源中的断链。

检查 Markdown 行内／引用式链接与图片目标，支持中文、空格、URL 编码；代码块和行内代码示例不作为链接。链接目标必须在仓库公开文件清单中，目录导航须包含公开文件；本机存在但被 Git 忽略的私有文件不能满足检查。README 必须连接索引，索引必须连接首批指南、维护约定和变更记录。

首版不检查标题锚点、原始 HTML 的 href/src、外部网站可达性、文档日期或语义准确性；安装依赖需要网络，检查过程不访问外部链接。命令和能力表述仍结合源码审查。

失败时按报告中的来源和目标修复链接、补真实入口或删除误导引用，不应只修改检查范围绕过问题。[历史问题清单](../scripts/docs/link-baseline.json)当前为空：本轮已修复可确认的仓库链接，外部参考仓库的机器路径保留为原始文字定位。

将来遇到无法立即修复的历史问题时，豁免项须逐条包含 `source`、规范化 `target`、`type`、`reason`，经维护者审查；只匹配这项错误，不豁免整篇或整个目录。当前入口和指南不能豁免。修复后移除对应条目，检查会提示遗留豁免；禁止把报告自动写回基线来吸收新增错误。

## 本次本地验证（2026-10-05）

- 后端同等 CI 命令（使用已有 Maven 缓存，附加 `-o`）：228 项，226 通过、0 失败、0 错误、2 项 Nginx 条件测试跳过；JAR 打包成功。
- 前端锁定安装、只读 lint、类型检查、79 项聊天测试、生产构建全部 exit 0。lint 为 0 error、190 项既有 warning。
- 官方 actionlint 1.7.12 校验 exit 0；下载文件已核对发布方 SHA256。未运行其可选 shellcheck/pyflakes 子检查。
- 本地使用 Windows、Java 21.0.7、Node 24.11.1、pnpm 10.28.0。GitHub 的 Ubuntu runner 尚未实际运行，须提交并推送后才能验证远端 Action 下载、缓存及产物上传。
- 当前变更未提交或推送；未启动项目服务。

### 全新检出场景补充验证（2026-10-05）

移走本地 `auto-imports.d.ts` 和 `components.d.ts` 后，原顺序的类型检查复现 205 条错误（exit 2）。调整为先生产构建后类型检查，确认两个声明文件重新生成，构建、类型检查和 79 项聊天/SSE 测试均 exit 0；只读 lint 为 0 error、190 项既有 warning。此前本地验证使用了已有生成文件，未覆盖此场景。远端 CI 仍需推送后重跑确认。
