# [bug · high] 级别问题清单（共 134 条）

> 来源：`shadowrag的code review.txt`（已翻译版）

## 后端 (src/main/java) — 41 条

1. **L286** `src/main/resources/test.html:405-406` — WebSocket重连逻辑缺陷：`intentionalClosure`设置为true，然后重置为
2. **L372** `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java:166-168` — 块处理方法（例如，processChunk、processToolChunk）中的异常是
3. **L380** `src/main/java/com/yizhaoqi/smartpai/client/MinerUClient.java:108-112` — 响应解析中的 NPE 风险：“结果”和遗留“数据”分支都可以返回
4. **L467** `src/main/java/com/yizhaoqi/smartpai/config/CompressionProperties.java:19-19` — 默认情况下，`threadPool` 字段未初始化。如果配置没有
5. **L526** `src/main/java/com/yizhaoqi/smartpai/client/EmbeddingClient.java:93-94` — 缺失“嵌入”字段的静默跳过导致返回的向量计数不同
6. **L541** `src/main/java/com/yizhaoqi/smartpai/config/EsIndexInitializer.java:72-72` — 当应用程序打包并从某个位置运行时，使用 `mappingResource.getFile()` 会失败
7. **L607** `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java:257-271` — 工具调用处理仅处理每个增量中的第一个工具调用
8. **L747** `src/main/java/com/yizhaoqi/smartpai/config/LoggingInterceptor.java:40-41` — setRequestContext() 用 requestId/userId/sessionId 填充 MDC，但是非常
9. **L837** `src/main/java/com/yizhaoqi/smartpai/config/OrgTagAuthorizationFilter.java:271-271` — Bug：extractResourceIdFromPath 还返回数字文档 ID
10. **L916** `src/main/java/com/yizhaoqi/smartpai/config/KafkaConfig.java:95-95` — DeadLetterPublishingRecoverer 始终保留原始源分区（新
11. **L951** `src/main/java/com/yizhaoqi/smartpai/controller/AuthController.java:81-81` — Integer.parseInt 在用户提供的“代码”上被调用两次，而没有任何验证或
12. **L993** `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java:93-96` — 事务回滚丢弃失败状态更新。 `processTask` 被注释
13. **L1031** `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java:191-193` — isPlainTextFile 将没有扩展名的文件视为纯文本（返回 true）。二进制
14. **L1056** `src/main/java/com/yizhaoqi/smartpai/config/WebConfig.java:54-57` — 在Spring MVC中，重写configureMessageConverters时，提供的`converters`
15. **L1160** `src/main/java/com/yizhaoqi/smartpai/controller/AdminController.java:66-66` — 在 findAll() 返回的托管 JPA 实体上设置 password=null 会改变
16. **L1172** `src/main/java/com/yizhaoqi/smartpai/controller/AdminController.java:90-91` — 令牌提取和 validateAdmin() 在 try-catch 块之外执行
17. **L1184** `src/main/java/com/yizhaoqi/smartpai/controller/AdminController.java:526-528` — parseDateTime() 在无法解析的输入上抛出 CustomException（错误请求），但是
18. **L1215** `src/main/java/com/yizhaoqi/smartpai/controller/DocumentController.java:68-68` — 删除端点首先查询`findByFileMd5AndUserId(fileMd5, userId)`，其中
19. **L1249** `src/main/java/com/yizhaoqi/smartpai/controller/ConversationController.java:35-42` — 当授权标头丢失或令牌丢失时，extractUsername() 返回 null
20. **L1349** `src/main/java/com/yizhaoqi/smartpai/model/ChunkInfo.java:27-33` — 缺失 (fileMd5, chunkIndex) 的唯一约束。在分片上传场景中，若相同文件相同分片被重复配置（如重试/梯度上传），会在 chunk_info
21. **L1405** `src/main/java/com/yizhaoqi/smartpai/controller/UserController.java:230-230` — `getUserOrgTags` 返回的 `orgTags` 值已经是一个 `List<String>` （参见
22. **L1426** `src/main/java/com/yizhaoqi/smartpai/model/FileUpload.java:90-91` — @UpdateTimestamp 在实体的任何更新（例如，更改
23. **L1585** `src/main/java/com/yizhaoqi/smartpai/repository/RedisRepository.java:25-25` — RedisRepository 中未经检查的强制转换为 String：RedisTemplate 中的值在不进行强制转换的情况下进行强制转换
24. **L1594** `src/main/java/com/yizhaoqi/smartpai/repository/FileUploadRepository.java:70-71` — fileMd5 上的操作不是用户范围的。 updateParseStatusByFileMd5,
25. **L1614** `src/main/java/com/yizhaoqi/smartpai/service/UserService.java:371-371` — NPE 风险：UserService 中多个方法调用 user.getOrgTags().split(',') 而没有
26. **L1691** `src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java:154-157` — 竞态条件（先检查后执行）：从 Redis 读取当前会话 ID 并
27. **L1818** `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:323-325` — 竞争条件：updateConversationHistory 执行 Redis 的读取-修改-写入
28. **L1828** `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:114-115` — 停止标志仅阻止向客户端发送块，但responseBuilder仍然
29. **L1837** `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:450-453` — 停止标志生命周期缺陷：无论什么情况，该标志都会在固定的 2s 延迟后被删除
30. **L1882** `src/main/java/com/yizhaoqi/smartpai/service/OrgTagCacheService.java:193-199` — 递归父标签收集没有循环检测。如果组织标签层次结构
31. **L1915** `src/main/java/com/yizhaoqi/smartpai/service/OrgTagCacheService.java:122-125` — deleteUserOrgTagsCache 仅删除组织标签和主组织键，但不删除
32. **L1950** `src/main/java/com/yizhaoqi/smartpai/service/VectorizationService.java:61-61` — 没有对 embeddingClient.embed 返回的向量数量进行验证
33. **L2079** `src/main/java/com/yizhaoqi/smartpai/service/UploadService.java:74-74` — 竞争条件：FileUpload 的先检查后插入不是原子的。并发
34. **L2093** `src/main/java/com/yizhaoqi/smartpai/service/UploadService.java:491-491` — 块大小从未根据所使用的假定 5MB 块大小进行验证
35. **L2104** `src/main/java/com/yizhaoqi/smartpai/service/UploadService.java:593-594` — MinIO 的 composeObject() 要求除最后一个之外的每个源对象至少为 5MB
36. **L2137** `src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java:160-162` — TOCTOU 竞赛：doCompress 读取 JSON 快照（`currentHistory`）并计算
37. **L2150** `src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java:98-100` — 不协调并发修改：硬阈值路径 (`syncTruncate`, line
38. **L2186** `src/main/java/com/yizhaoqi/smartpai/service/UserService.java:658-661` — 文档使用检查是一个存根：`fileCount` 被硬编码为 0，因此守卫 `if
39. **L7542** `src/main/resources/logback-spring.xml:125-125` — 使用 <logger name="root"> 降低 prod 配置文件中的 root 级别是无效的：
40. **L7552** `src/main/resources/logback-spring.xml:84-87` — 业务和性能记录器设置additivity=“false”并且不附加
41. **L7649** `src/main/resources/application-docker.yml:98-98` — 格式错误的 YAML 键：`tika=DEBUG:` 将等号和冒号合并为单个键，

## 前端 (frontend/) — 78 条

1. **L158** `frontend/packages/materials/src/libs/admin-layout/index.module.css:27-29` — 移动端的 Z-index 级联冲突。在index.vue中，当移动端为
2. **L2379** `frontend/src/sockert.js:9-14` — 广播逻辑放置在 'message' 事件处理程序内部，因此每次传入
3. **L2388** `frontend/src/sockert.js:25-28` — 当客户端断开连接时，setTimeout 回调既不会被跟踪也不会被清除。
4. **L2397** `frontend/src/sockert.js:26-28` — client.send(randomText) 被调用，没有任何错误处理。如果套接字已经
5. **L2799** `frontend/package.json:118-118` — 预提交钩子运行 `pnpm lint` ，它执行 `eslint 。 --fix` 因此
6. **L2811** `frontend/packages/scripts/package.json:4-6` — bin 脚本 `bin.ts` 以 shebang `#!/usr/bin/env tsx` 开头，这意味着
7. **L3084** `frontend/build/config/proxy.ts:46-49` — 当enableLog为假时，代理“错误”事件处理程序提前返回，默默地
8. **L3102** `frontend/build/config/proxy.ts:52-52` — proxyPattern 直接插值到 RegExp 构造函数中，无需转义。如果
9. **L3314** `frontend/packages/axios/src/options.ts:16-18` — 当调用者显式传递一个属性集时，Object.assign clobbers 默认为
10. **L3361** `frontend/packages/axios/src/index.ts:37-41` — 变量遮蔽 bug：内部 `const requestId = nanoid()` 遮蔽外部 `let
11. **L3409** `frontend/packages/axios/src/type.ts:109-113` — 在 axios 中，`AxiosError.response` 是可选的（`response?: AxiosResponse`）。开
12. **L3488** `frontend/packages/color/src/palette/recommend.ts:26-26` — `matchColor`的`find`使用非空断言，但输入的`color`可能不
13. **L3503** `frontend/packages/color/src/palette/recommend.ts:76-76` — `sRatio = s1 / s2` 除以最接近的调色板饱和度 `s2`。对于
14. **L3561** `frontend/packages/color/src/palette/recommend.ts:88-88` — 色调调整逻辑不正确：两个分支计算出相同的结果。当
15. **L3572** `frontend/packages/hooks/src/use-context.ts:88-90` — `inject` 返回 `T | undefined` 当未提供上下文键时（即 `useStore`
16. **L3691** `frontend/packages/color/src/palette/antd.ts:78-78` — 差一错误：`patterns` 是从 0 开始的（patterns[i] 对应于颜色索引 i+1，
17. **L3701** `frontend/packages/color/src/palette/antd.ts:167-173` — `getValue` 仅限制上限。对于深色，`value = hsv.v -
18. **L3719** `frontend/packages/hooks/src/use-count-down.ts:20-24` — 计时是基于帧计数的：每个 rAF 刻度都会减少 fps 一次，并假定固定
19. **L3754** `frontend/packages/hooks/src/use-table.ts:94-110` — getData() 没有 try/catch/finally。如果 apiFn、transformer 或 onFetched 抛出异常（例如，
20. **L3784** `frontend/packages/hooks/src/use-table.ts:138-140` — 不等待或捕获立即调用。由于 getData() 可以拒绝（参见
21. **L3796** `frontend/packages/hooks/src/use-table.ts:99-105` — 并发 getData() 调用存在竞争条件（例如重复
22. **L3899** `frontend/packages/hooks/src/use-signal.ts:136-139` — 在 `useSignal` 中，当通过可写的 `useCompulated` 路径创建信号时，
23. **L3979** `frontend/packages/scripts/src/commands/update-pkg.ts:4-4` — 在没有 `await` 的情况下调用异步函数。这意味着承诺拒绝是
24. **L3999** `frontend/packages/scripts/src/commands/git-commit.ts:58-62` — 如果用户中止提示，则 result.description.startsWith 将会抛出异常（描述
25. **L4055** `frontend/packages/scripts/src/config/index.ts:12-13` — 否定模式 '!node_modules/**' 只排除根级别下的内容
26. **L4145** `frontend/packages/scripts/src/commands/router.ts:62-68` — 目录分割逻辑将多级路由和组路由扁平化，相互矛盾
27. **L4250** `frontend/src/hooks/business/auth.ts:11-15` — 访问 `authStore.userInfo.role` 时未验证 `userInfo` 是否存在。如果`isLogin`
28. **L4279** `frontend/packages/utils/src/storage.ts:35-37` — 错误值被视为缺失。当有效值如“0”、“false”、“''”或
29. **L4302** `frontend/packages/utils/src/storage.ts:47-49` — `stg.clear()` 删除存储区域中的所有条目，而不仅仅是那些前缀为
30. **L4316** `frontend/packages/utils/src/storage.ts:71-75` — `localforage.config()` 改变了全局共享的 localforage 单例。呼唤
31. **L4343** `frontend/src/hooks/common/router.ts:97-97` — `route.value.query?.redirect` 的类型为 `LocationQueryValue |位置查询值[] |
32. **L4381** `frontend/src/layouts/context/index.ts:19-20` — 空安全：selectedKey 是从route.meta 和route.name 计算得出的。当
33. **L4425** `frontend/src/hooks/common/echarts.ts:183-187` — 竞争条件：`changeTheme`、`renderChartBySize` 和 `destroy` 都是异步的，并且
34. **L4438** `frontend/src/hooks/common/echarts.ts:144-146` — 潜在的 null 取消引用：在 `await render()` 之后（以及在 `updateOptions` 之后）
35. **L4447** `frontend/src/hooks/common/form.ts:59-60` — 当确认密码字段为空/清除时，`value.trim()` 将抛出 TypeError，
36. **L4612** `frontend/src/router/elegant/transform.ts:76-77` — 如果单级路由组件字符串不包含`$`分割符，`view`（和
37. **L4643** `frontend/src/store/modules/knowledge-base/index.ts:65-65` — 防止访问可能已被删除的tasks.value条目（例如
38. **L4654** `frontend/src/store/modules/tab/index.ts:104-107` — 在删除/改变选项卡之前检查路线导航的结果；仅删除/更新
39. **L4763** `frontend/src/service/request/shared.ts:18-18` — fetchRefreshToken 通过请求层执行 HTTP 请求，可以拒绝 on
40. **L4784** `frontend/src/service/request/shared.ts:35-39` — 如果handleRefreshToken()拒绝（例如，网络错误，见上文），则`await
41. **L4829** `frontend/src/service/request/index.ts:90-98` — 令牌过期重试逻辑无法防止无限循环。如果重试
42. **L4849** `frontend/src/store/modules/chat/index.ts:19-19` — WebSocket URL 在商店设置时使用“store.token”构建一次，捕获
43. **L4892** `frontend/src/router/guard/route.ts:19-20` — async beforeEach 守卫和 initRoute 缺乏 try/catch 错误处理。如果
44. **L4987** `frontend/src/store/modules/auth/index.ts:136-138` — 在 `loginByToken` 中，新令牌被写入 `localStg` *before* `getUserInfo()`
45. **L5068** `frontend/src/store/modules/route/index.ts:306-308` — 缺少 fetchIsRouteExist 的错误处理。获取失败时，“error”字段为
46. **L5452** `frontend/src/utils/common.ts:105-106` — `dayjs` 在 `formatDate` 中使用，但未在此文件中导入（仅 `SparkMD5` 和
47. **L5551** `frontend/src/typings/vite-env.d.ts:28-28` — 类型文字不匹配：`.env` 中的实际值是
48. **L5638** `frontend/packages/materials/src/libs/page-tab/chrome-tab-bg.vue:10-10` — SVG ID 是全局范围的并且是硬编码的。如果此组件（或其他组件，如 WaveBg）
49. **L5710** `frontend/packages/materials/src/libs/page-tab/index.vue:17-17` — 声明了 `commonClass` 属性（默认为 'transition-all-300'），但从未声明过
50. **L5846** `frontend/packages/materials/src/libs/admin-layout/index.vue:26-28` — 缺少几个布尔配置属性（fixedFooter、fullContent、isMobile）
51. **L5908** `frontend/src/components/custom/better-scroll.vue:38-40` — BScroll 实例在挂载时创建，但从未销毁。 BScroll 附加原生
52. **L6001** `frontend/src/components/custom/org-tag-cascader.vue:21-32` — props.options 和 props.excludePrivate 仅在 onMounted 内部处理一次。如果
53. **L6027** `frontend/src/components/custom/org-tag-cascader.vue:27-31` — excexPrivate 逻辑仅根据 tagId 将 TOP-LEVEL 选项标记为禁用
54. **L6078** `frontend/src/components/common/pin-toggler.vue:17-21` — 该组件名为“PinToggler”并采用“pin”道具，但它从不发出任何信号
55. **L6107** `frontend/src/components/custom/count-to.vue:68-71` — 当`startValue`改变时，`start()`只将`props.endValue`分配给`source`，而不
56. **L6149** `frontend/src/components/custom/the-select.vue:39-46` — 监视源是 `() => params`，仅当 `params` 对象存在时才会触发
57. **L6168** `frontend/src/components/custom/the-select.vue:35-35` — `selectFirst` 假设 `opts.value` 是一个非空数组，并且 `attrs['value-field']`
58. **L6182** `frontend/src/components/custom/the-select.vue:29-30` — `fetchOpts` 中不存在请求取消或排序保护。如果“params”改变
59. **L6228** `frontend/src/components/custom/svg-icon.vue:30-36` — 如果 VITE_ICON_LOCAL_PREFIX 未定义（例如 .env 中缺失或不存在于
60. **L6358** `frontend/src/layouts/modules/global-header/components/user-avatar.vue:47-47` — window.$dialog 通过可选链访问。如果全局对话框不是
61. **L6374** `frontend/src/layouts/modules/global-header/components/user-avatar.vue:52-54` — authStore.logout() 在 onPositiveClick 内等待，无需 try/catch。如果注销
62. **L6493** `frontend/src/layouts/modules/global-menu/modules/horizontal-menu.vue:17-17` — 传送目标 (#GLOBAL_HEADER_MENU_ID / #GLOBAL_SIDER_MENU_ID) 不是
63. **L6601** `frontend/src/layouts/modules/global-search/components/search-result.vue:27-29` — 单击一个项目导航到“activePath”（通过发出的“enter”事件），但是
64. **L6663** `frontend/src/views/_builtin/login/modules/register.vue:37-42` — 如果异步请求抛出或拒绝，加载状态不会重置；将请求包装在
65. **L6692** `frontend/src/views/_builtin/login/modules/reset-pwd.vue:40-41` — 表单验证通过抛出的 Promise 被拒绝，但未被捕获，导致未处理
66. **L6762** `frontend/src/views/user/index.vue:58-58` — 未定义/空日期字段上的 dayjs 格式呈现“无效日期”；守卫与
67. **L6770** `frontend/src/views/chat-history/index.vue:39-41` — watchEffect 中的异步请求未关联/清理，导致响应过时
68. **L6799** `frontend/src/views/chat/modules/input-box.vue:28-32` — websocket 数据观察器假设最后一个列表元素是辅助消息
69. **L6867** `frontend/src/layouts/modules/theme-drawer/modules/config-operation.vue:18-18` — Clipboard 实例在 onMounted 中创建，但从未销毁。由于 Clipboard.js
70. **L7102** `frontend/src/views/_builtin/login/modules/code-login.vue:35-39` — handleSubmit仅验证表单并显示成功消息；没有实际的
71. **L7110** `frontend/src/layouts/modules/global-tab/index.vue:130-142` — 上下文菜单竞争条件：每次右键单击都会安排一个独立的 setTimeout。当
72. **L7177** `frontend/src/views/_builtin/login/modules/reset-pwd.vue:33-37` — `code` 字段没有在计算规则中定义的验证规则。为空或无效
73. **L7191** `frontend/src/views/_builtin/login/modules/reset-pwd.vue:41-43` — 提交处理程序仅显示成功消息，而不调用任何后端
74. **L7257** `frontend/src/views/chat/modules/chat-message.vue:58-59` — 计算属性内的副作用：调用 `chatStore.scrollToBottom?.()` 和
75. **L7372** `frontend/src/views/knowledge-base/index.vue:175-179` — 潜在崩溃：在 `if (index !== -1)` 块之后，访问 `tasks.value[index]`
76. **L7387** `frontend/src/views/knowledge-base/index.vue:175-179` — 当任务没有上传的 chunk 时，分支会从本地列表中删除该任务并
77. **L7416** `frontend/src/views/org-tag/modules/org-tag-operate-dialog.vue:60-60` — 将 `rowData` 属性直接分配给表单模型可以使表单的 v-model 编辑
78. **L7478** `frontend/src/views/chat/modules/input-box.vue:20-20` — 在 wsData 观察器中调用 JSON.parse 时没有使用 try/catch。如果服务器发送任何

## 官网 (homepage/) — 8 条

1. **L3** `homepage/css/index.css:170-172` — 不透明度值无效：`opacity` 必须介于 0 和 1 之间。`opacity: 100` 无效
2. **L14** `homepage/css/index.css:159-164` — `transition:transform 0.5;`使用没有时间单位的数字。持续时间必须是
3. **L2423** `homepage/index.js:15-17` — 外部单击处理程序不排除折叠按钮本身。当标题
4. **L2579** `homepage/scripts/components.js:44-44` — 空指针/TypeError: 当 `selectIcon` 为 null 并且没有
5. **L2622** `homepage/public/js/particles.min.js:0-0` — BUG：`requestAnimFrame(check)`引用了未定义的`check`函数。 “检查”是
6. **L2644** `homepage/public/js/particles.min.js:0-0` — BUG/LEAK: `destroypJS` 仅取消绘制动画帧和 `t.remove()`，但它
7. **L2656** `homepage/vite.config.js:22-22` — ES 模块中未定义 `__dirname`。由于此文件使用 ESM `import`，
8. **L2742** `homepage/tailwind.config.js:20-20` — `firefox` 变体选择器 `:-moz-any(&)` 不可靠。 `:-moz-any()`

## 文档 (docs/) — 5 条

1. **L2958** `docs/interview/eval_retrieval.py:95-96` — `search_bm25`和`search_knn`直接访问`resp.json()["hits"]["hits"]`，无需
2. **L3010** `docs/databases/ddl.sql:31-32` — file_upload 和 document_vectors 中类型不匹配且缺少外键：user_id
3. **L3025** `docs/databases/ddl.sql:34-34` — merged_at 是用 ON UPDATE CURRENT_TIMESTAMP 声明的，因此它被默默地重写为
4. **L3035** `docs/databases/ddl.sql:43-43` — chunk_info 对 (file_md5, chunk_index) 没有唯一约束，因此重复的 chunk
5. **L7579** `docs/docker-compose.yaml:49-49` — Redis 是通过 `--requirepass` 启动的，但是此健康检查运行 `redis-cli ping`

## .gitignore — 1 条

1. **L7765** `.gitignore:30-30` — 过于宽泛的模式：`dist`（无前导斜杠）匹配任何名为的文件或目录

## .gitattributes — 1 条

1. **L7787** `.gitattributes:1-1` — 双引号不被视为 .gitattributes 模式语法中的引用分隔符

---
统计：前端 78 · 后端 41 · 官网 8 · 文档 5 · 其他 2
