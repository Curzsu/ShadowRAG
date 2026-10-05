# 复现本地与真实模型验收

使用当前工作区及已有Java/Maven/Node/pnpm版本。默认浏览器fixture只调用本地SSE supplier，DB/Redis/search是内存替身；没有匿名生产测试入口，生产jar不包含这些test类。所有临时服务只绑定loopback。先完成编译再启动预览；本轮曾因在运行的target/classes上重新编译而出现预览classpath瞬时错误，所以预览期间不要同时编译同一target。

在仓库根目录先运行本地冒烟并从其Surefire报告取得完整测试classpath：

```powershell
mvn "-Dtest=ChatStreamingBrowserApplicationTest" test
[xml]$fixtureReport = Get-Content -Raw -LiteralPath 'target/surefire-reports/TEST-com.yizhaoqi.smartpai.support.ChatStreamingBrowserApplicationTest.xml'
$fixtureClassPath = [string]($fixtureReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
if (-not $fixtureClassPath) { throw 'Surefire test classpath missing' }
$fixtureArguments = @('-cp', ('"' + $fixtureClassPath.Replace('\', '/') + '"'), 'com.yizhaoqi.smartpai.support.ChatStreamingBrowserApplication')
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) 'target/chat-browser.args'), $fixtureArguments, [System.Text.UTF8Encoding]::new($false))
java '@target/chat-browser.args'
```

argfile只有classpath和test main类，没有key/token。入口默认 `127.0.0.1:18083`；专用内存账号 `fixture-browser`，测试密码 `BrowserTest123`。每次重启重新登录取得临时JWT。独立后端只映射 `/test.html`、`/static/test.html` 两个实际static路径；第一份root resource HTML的 `/test-source.html` 由下述本地Nginx alias提供，不是fixture路由。

另开终端启动真实Vue开发代理（仓库frontend目录）：

```powershell
$env:VITE_SERVICE_BASE_URL = 'http://127.0.0.1:18083/api/v1'
$env:VITE_OTHER_SERVICE_BASE_URL = '{"api":"http://127.0.0.1:18083/api/v1"}'
$env:VITE_HTTP_PROXY = 'Y'
pnpm exec vite --mode test --host 127.0.0.1 --port 19527 --strictPort
```

访问 `http://127.0.0.1:19527`，用fixture账号登录、问答/停止/切换/删除/注销并刷新历史。两个baseURL都含 `/api/v1`；主SSE与普通登录/会话API分别使用对应服务配置，缺任意一个都会访问错误地址。

生产构建使用 `pnpm build`，复制 `docs/nginx.conf` 到独立临时本地配置：将listen改为 `127.0.0.1:18084`、全部upstream改为 `127.0.0.1:18083`、root改为repo的frontend/dist绝对路径；保留stream、普通API、static helper和SPA规则。在同一server内加入以下精确alias，先将占位符替换为repo绝对路径（Windows使用正斜杠），再验证配置并启动自己管理的Nginx：

```nginx
location = /test-source.html {
    alias "<REPO_ABSOLUTE_PATH>/src/main/resources/test.html";
}
```

此alias直接提供root resource源文件；其API和 `/static/chat-stream.mjs` 由已有规则同源转发到fixture。它只用于本地第二份源HTML验收，不是新生产API或Java测试入口。原验收production监听18084、upstream18083；50/1000与真实ProxyTest用另一组18080→18082，避免抢端口。

自动化复现不需要任何外部key：

```powershell
mvn "-Dtest=ChatStreamingLoadTest" test
# 先启动独立真实Nginx，使用docs中的stream规则，指向18082
mvn "-Dtest=ChatStreamingProxyTest,ChatStreamingHttpTest,ChatStreamingHeaderOrderTest" "-Dchat.acceptance.backend-port=18082" "-Dchat.acceptance.proxy-url=http://127.0.0.1:18080" test
```

ProxyTest未提供proxy-url会跳过；跳过不能算真实代理通过。默认参数专项需要95秒实际工具等待，不缩短15秒心跳/90秒代理idle。最新数值及测试边界见 [矩阵](README.md)。

额外真实Gemini复现使用新的专用终端，先停止同端口旧fixture，然后只在该进程环境注入key。以下复制本轮成功的模型选择，不保证供应商以后仍可用：

```powershell
$env:CHAT_BROWSER_LIVE_GEMINI = 'true'
$env:GEMINI_MODEL = 'gemini-3.1-flash-lite'
$geminiKeySecure = Read-Host 'Gemini API key' -AsSecureString
$env:GEMINI_API_KEY = [System.Net.NetworkCredential]::new('', $geminiKeySecure).Password
# 若当前网络需要已有HTTP CONNECT代理，输入其URL；不加入用户名/密码
$env:CHAT_BROWSER_HTTPS_PROXY = Read-Host 'Existing HTTP proxy URL (leave empty for direct access)'
java '@target/chat-browser.args'
```

live mode才访问Google官方OpenAI-compatible API；保留TLS校验、生产模型请求/解析/取消链。当前test入口默认120秒generation/140秒emitter、最多1024输出tokens；这些是live fixture的额外边界，A30默认参数专项仍为300000/320000/15000ms。本轮直连超时、gemini-3.8-flash繁忙503和后续成功均记录在 [真实联调](live-gemini.md)。key只进入后端环境，浏览器只有临时JWT；不在URL、argfile、项目配置或命令字面值写入key/JWT。结束后Ctrl+C停止Java/Vite，关闭专用终端清除临时环境，停止自己启动的本地Nginx。内存数据随fixture进程退出消失。
