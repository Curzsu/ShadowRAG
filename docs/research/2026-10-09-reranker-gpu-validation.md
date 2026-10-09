# RTX 5060 精排 GPU 验证

目标：在本机 NVIDIA RTX 5060 Laptop GPU 上验证 BAAI/bge-reranker-v2-m3 的 GPU 推理可用性、耗时和排序效果。验证后已按用户要求将正式精排服务切至 GPU，保留原 localhost:8082 接口，后端配置不变。

## 运行环境

- GPU：RTX 5060 Laptop，8151 MiB 显存，驱动 591.74；基线空闲约 7502 MiB。
- CPU 服务：8082，TEI 1.9.3 CPU 镜像、Candle CPU、float32。
- GPU 验证服务：127.0.0.1:8083，容器 shadowrag-tei-gpu-validation，TEI 1.9.4、float16、CUDA FlashBert。启动日志确认 CUDA 后端，健康检查通过；服务启动后显卡总显存占用约 1.7 GiB（包含桌面等其他进程，不等于模型独占显存）。
- 官方 RTX 50 镜像为 `120-1.9`，属于 experimental；本次固定实际镜像摘要 `sha256:bd8e5b1954146f7fe8590b64b959bc194433c6c38c036592a84d736841ca9400`。
- GPU 使用现有 pai_smart_tei-models 模型缓存，max_batch_tokens=16384、max_concurrent_requests=32；未截断文本、未降低候选数量。

注意：本次比较当前 CPU float32 / TEI 1.9.3 与新 GPU float16 / TEI 1.9.4 **两种部署配置**，不属于固定版本、固定精度的纯硬件对照。精排速度不能直接当作聊天 TTFT 的降幅。

官方硬件支持：https://huggingface.co/docs/text-embeddings-inference/supported_models

## 固定候选对照

采用既有真实实验的样本 1、4，各 10 个候选，完整正文相同，每种配置预热一次、每题重复 3 次，CPU/GPU 顺序交替，单并发。输出只含编号、候选 key、分数差、耗时和标签指标；正文仅在忽略的 target/rerank-diagnosis/inputs.local.json。

| 指标 | CPU | GPU |
|---|---:|---:|
| 6 次请求中位耗时 | 11.1965 秒 | 0.2617 秒 |
| 超过原 Java 10 秒预算 | 6/6 | 0/6 |

中位耗时比约 42.8 倍（该小样本、单并发、本机配置），6/6 完整候选排名一致，6/6 Top5 排名及 Hit@5/RR@5 一致。不能据此声称全量、所有文本长度或线上 P95 保证。

额外发现：GPU 验证容器只发布 IPv4 回环端口，使用 localhost 时会发生约 2 秒连接延迟，而 127.0.0.1 的健康请求约 3ms。初次 localhost 测试中位 GPU 请求为 2.296 秒；真实推理并不需要这 2 秒。正式对照及 Java GPU 测试均显式使用 127.0.0.1。

证据：

- ../eval/retrieval/diagnostics/2026-10-09-gpu-loopback/measurements.json
- ../eval/retrieval/diagnostics/2026-10-09-gpu-loopback/summary.json
- ../eval/retrieval/diagnostics/2026-10-09-gpu/runtime.json
- ../eval/retrieval/diagnostics/2026-10-09-gpu/measurements.json（初次 localhost 连接对照）

复跑：

```powershell
python scripts/langfuse/reranker_gpu_benchmark.py --inputs target/rerank-diagnosis/inputs.local.json --output-dir docs/eval/retrieval/diagnostics/新的目录 --repeats 3
```

## 真实 Java 链路

使用既有冻结的 10 条 retrieval-eval-50 样本、同一测试用户、原 10 秒精排超时。测试 JVM 临时覆盖 reranker.api.url=http://127.0.0.1:8083，运行实际 BM25/混合检索/精排并上传新实验；临时环境退出后恢复。

新运行 `retrieval-gpu-20261009-01`，GPU 精排 10/10 成功、0 降级。Langfuse 回读全部 10 个 rerank 子节点，中位耗时 0.160 秒，最大 0.181 秒。10 条配对样本结果如下：

| 策略 | Hit@5 | MRR@5 |
|---|---:|---:|
| 同权限 BM25 | 0.70 | 0.495 |
| 实际 Java 混合检索 + GPU 精排 | 0.70 | 0.625 |

这次 MRR 高于同权限 BM25，但 Hit@5 没有提升，不能将 GPU 加速声称为必然提高命中率。它是小样本验证，不是 50 条完整正式报告，也不是 CPU/GPU 全量精度等价保证。

与上一 CPU 实验检查：数据集文件 SHA256、用户 SHA256、权限哈希及索引快照完全一致；Java 源码与其余已记录检索配置均相同，config 唯一改变项是 reranker endpoint hash。CPU 旧实验的混合链路含 8 条降级，不能把旧报告的混合平均分当作全成功 CPU 精排精度基线；精度等价证据以固定候选的两题六次配对为准。

Cloud 已核验 2 个实验、20 个实际 trace、40 个 Hit/MRR score、样本与冻结版本关联。可在 retrieval-eval-50 → Experiments 查看 `retrieval-gpu-20261009-01-bm25` 和 `retrieval-gpu-20261009-01-hybrid_rerank`。初次回读遇到异步入库延迟，随后原样回读已通过，没有重跑或补造调用。

完整结果：../eval/retrieval/results/retrieval-gpu-20261009-01/report.md；云端证明：cloud-verification.json；全部精排节点耗时：gpu-rerank-latencies.json。

测试：实际 Java 检索测试 5 个全通过（0 skipped）；scripts/langfuse 下 10 个 Python 测试通过；Compose GPU 合并配置及容器 curl 健康检查通过。验证期间没有 CUDA/显存错误。embedding 加载后采样 GPU 总占用 2608 MiB、空闲 5292 MiB；该瞬时值不是峰值监测。

## 可选部署与回退

已准备 `docs/docker-compose.gpu.yaml`：仅覆盖精排镜像、float16、GPU 设备和批处理上限，保留正式服务 8082 端口。配置合并已校验。**2026-10-09 已按用户要求执行正式切换**：验证容器停止，现有 tei-reranker 重建为固定摘要的 GPU 镜像。以后重建服务需同时加载 GPU 覆盖文件，单独使用基础文件会恢复 CPU 配置。

```powershell
# 本机 RTX 50 GPU 切换（只重建精排服务）
docker compose -f docs/docker-compose.yaml -f docs/docker-compose.gpu.yaml up -d --no-deps tei-reranker

# 回到 CPU
docker compose -f docs/docker-compose.yaml up -d --no-deps tei-reranker
```

验证 GPU 容器已停止释放显存，8083 不再提供服务。该镜像针对 RTX 50，不能直接作为所有 NVIDIA 显卡的通用配置。

正式切换后检查：8082 健康请求 200，DTYPE=float16，GPU DeviceRequests 已设置，镜像摘要匹配，启动日志确认 CUDA FlashBert。通过后端原来的 `http://localhost:8082/rerank` 请求两组真实 10 候选，耗时分别 0.358 / 0.145 秒，均在原 10 秒预算内且完整排名与 CPU 对照一致。原服务发布 IPv4/IPv6 端口，未出现验证容器 localhost 的约 2 秒回退延迟。

部署证据：../eval/retrieval/diagnostics/2026-10-09-gpu-loopback/deployment-verification.json。
