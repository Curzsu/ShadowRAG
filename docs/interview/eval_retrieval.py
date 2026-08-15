"""
ShadowRAG 检索评测脚本
直接连 ES + Ollama，对比 BM25 / KNN / 混合(RRF) 三种策略的 Hit@5 和 MRR
不依赖 Java 项目，独立运行
"""
import json
import os

import requests
import numpy as np

# ============ 配置 ============
ES_URL = "http://localhost:9200"
ES_AUTH = ("elastic", os.environ.get("ES_PASSWORD", ""))
INDEX = "knowledge_base"
OLLAMA_URL = "http://localhost:11434/v1/embeddings"
EMBED_MODEL = "bge-m3:latest"
TOP_K = 5
RECALL_K = 150  # 每路召回数量（给 RRF 留候选）
RRF_K = 60      # RRF 常数

# ============ 评测数据集（50 条，基于真实 chunk 构造）============
DATASET = [
    # ---------- 数据集 A：面经文档 (f85f05936fc325cf34792163c880042c) ----------
    {"query_id": 1,  "query": "滴滴实习面试问了哪些RAG相关的问题", "relevant": ["f85f05936fc325cf34792163c880042c:12", "f85f05936fc325cf34792163c880042c:13"], "difficulty": "easy"},
    {"query_id": 2,  "query": "面试官问vector怎么设计的，向量维度选多少", "relevant": ["f85f05936fc325cf34792163c880042c:12"], "difficulty": "medium"},
    {"query_id": 3,  "query": "面试中被问到MCP和Skill的区别", "relevant": ["f85f05936fc325cf34792163c880042c:2", "f85f05936fc325cf34792163c880042c:15"], "difficulty": "medium"},
    {"query_id": 4,  "query": "哪些公司的面试涉及到了LangGraph", "relevant": ["f85f05936fc325cf34792163c880042c:4", "f85f05936fc325cf34792163c880042c:7", "f85f05936fc325cf34792163c880042c:8"], "difficulty": "hard"},
    {"query_id": 5,  "query": "面试中被追问Function Calling怎么匹配工具参数", "relevant": ["f85f05936fc325cf34792163c880042c:2"], "difficulty": "easy"},
    {"query_id": 6,  "query": "怎么评判预训练模型效果，LoRA的数学原理", "relevant": ["f85f05936fc325cf34792163c880042c:7"], "difficulty": "medium"},
    {"query_id": 7,  "query": "蔚来AI应用开发的面试题", "relevant": ["f85f05936fc325cf34792163c880042c:7", "f85f05936fc325cf34792163c880042c:8"], "difficulty": "easy"},
    {"query_id": 8,  "query": "面试官问Attention机制的核心作用和处理长文本的优化策略", "relevant": ["f85f05936fc325cf34792163c880042c:8", "f85f05936fc325cf34792163c880042c:9"], "difficulty": "medium"},
    {"query_id": 9,  "query": "怎么评估RAG系统的检索质量和生成质量", "relevant": ["f85f05936fc325cf34792163c880042c:9", "f85f05936fc325cf34792163c880042c:10"], "difficulty": "medium"},
    {"query_id": 10, "query": "美团实习面试关于AI Coding和Vibe Coding的问题", "relevant": ["f85f05936fc325cf34792163c880042c:6"], "difficulty": "easy"},
    {"query_id": 11, "query": "搜狐畅游面试问的FunctionCall到MCP到Skills三个阶段", "relevant": ["f85f05936fc325cf34792163c880042c:15"], "difficulty": "easy"},
    {"query_id": 12, "query": "面试官追问MCP的描述携带会挤占上下文怎么办", "relevant": ["f85f05936fc325cf34792163c880042c:16"], "difficulty": "medium"},
    {"query_id": 13, "query": "大模型幻觉问题怎么处理", "relevant": ["f85f05936fc325cf34792163c880042c:18"], "difficulty": "easy"},
    {"query_id": 14, "query": "面试问AI拆解任务时怎么判断技术方案合不合理", "relevant": ["f85f05936fc325cf34792163c880042c:19"], "difficulty": "medium"},
    {"query_id": 15, "query": "HNSW算法和IVF_FLAT算法的对比", "relevant": ["f85f05936fc325cf34792163c880042c:20"], "difficulty": "medium"},
    {"query_id": 16, "query": "ReAct模式和Plan-Execute架构的区别", "relevant": ["f85f05936fc325cf34792163c880042c:20", "f85f05936fc325cf34792163c880042c:40"], "difficulty": "medium"},
    {"query_id": 17, "query": "布隆过滤器的原理和怎么实现删除功能", "relevant": ["f85f05936fc325cf34792163c880042c:2", "f85f05936fc325cf34792163c880042c:21"], "difficulty": "medium"},
    {"query_id": 18, "query": "Transformer除了Attention还有什么创新，残差连接的作用", "relevant": ["f85f05936fc325cf34792163c880042c:30"], "difficulty": "medium"},
    {"query_id": 19, "query": "synchronized锁升级过程，为什么要锁升级", "relevant": ["f85f05936fc325cf34792163c880042c:60"], "difficulty": "easy"},
    {"query_id": 20, "query": "MySQL事务隔离级别有哪几种，分别怎么实现的", "relevant": ["f85f05936fc325cf34792163c880042c:70", "f85f05936fc325cf34792163c880042c:78"], "difficulty": "medium"},
    {"query_id": 21, "query": "Redisson分布式锁的原理和使用场景", "relevant": ["f85f05936fc325cf34792163c880042c:60", "f85f05936fc325cf34792163c880042c:78"], "difficulty": "medium"},
    {"query_id": 22, "query": "会话记忆具体怎么实现的，滑动窗口设置几轮", "relevant": ["f85f05936fc325cf34792163c880042c:3"], "difficulty": "medium"},
    {"query_id": 23, "query": "什么场景下需要用锁，常见的锁有哪些", "relevant": ["f85f05936fc325cf34792163c880042c:60"], "difficulty": "easy"},
    {"query_id": 24, "query": "面试官建议学习LLM业务工程上的先进设计理念", "relevant": ["f85f05936fc325cf34792163c880042c:3"], "difficulty": "hard"},
    {"query_id": 25, "query": "多智能体系统为什么要多agent，主agent和子agent共享上下文吗", "relevant": ["f85f05936fc325cf34792163c880042c:4", "f85f05936fc325cf34792163c880042c:20"], "difficulty": "hard"},
    # ---------- 数据集 B：基金研报 (ac137cbd7c684470941de1c6deaa67f6) ----------
    {"query_id": 26, "query": "鹏华香港银行指数C基金的基金代码是多少", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:2", "ac137cbd7c684470941de1c6deaa67f6:3"], "difficulty": "easy"},
    {"query_id": 27, "query": "这只基金跟踪的是什么指数", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:2"], "difficulty": "easy"},
    {"query_id": 28, "query": "基金经理是谁，管理经验怎么样", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:5"], "difficulty": "easy"},
    {"query_id": 29, "query": "这只基金2025年涨了多少，收益表现如何", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:6", "ac137cbd7c684470941de1c6deaa67f6:7"], "difficulty": "easy"},
    {"query_id": 30, "query": "基金的最大回撤是多少，波动大不大", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:8", "ac137cbd7c684470941de1c6deaa67f6:9"], "difficulty": "medium"},
    {"query_id": 31, "query": "这只基金的风险指标，夏普比率怎么样", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:8", "ac137cbd7c684470941de1c6deaa67f6:9"], "difficulty": "medium"},
    {"query_id": 32, "query": "第一大重仓股是哪个，占比多少", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:13"], "difficulty": "easy"},
    {"query_id": 33, "query": "前十大重仓股都有哪些", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:11", "ac137cbd7c684470941de1c6deaa67f6:12"], "difficulty": "easy"},
    {"query_id": 34, "query": "汇丰控股的股息率和ROE是多少", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:13"], "difficulty": "medium"},
    {"query_id": 35, "query": "内银股的估值水平，市盈率和市净率", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:14"], "difficulty": "medium"},
    {"query_id": 36, "query": "五大内银股合计占基金净值多少比例", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:14"], "difficulty": "medium"},
    {"query_id": 37, "query": "恒生银行和中银香港的占比和业绩", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:15"], "difficulty": "medium"},
    {"query_id": 38, "query": "恒生指数目前多少点，估值水平如何", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:16"], "difficulty": "easy"},
    {"query_id": 39, "query": "2026年A股市场表现怎么样，科技股和银行股谁强", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:17", "ac137cbd7c684470941de1c6deaa67f6:20"], "difficulty": "medium"},
    {"query_id": 40, "query": "南向资金流入港股的情况，偏好什么类型的股票", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:18"], "difficulty": "medium"},
    {"query_id": 41, "query": "港股银行股和A股银行股相比有什么优势", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:19", "ac137cbd7c684470941de1c6deaa67f6:20"], "difficulty": "medium"},
    {"query_id": 42, "query": "银行板块面临的房地产风险有多大", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:25"], "difficulty": "medium"},
    {"query_id": 43, "query": "这只基金综合评分多少，处于什么区间", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:28"], "difficulty": "easy"},
    {"query_id": 44, "query": "现在可以买入这只基金吗，投资建议是什么", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:28"], "difficulty": "easy"},
    {"query_id": 45, "query": "稳健型投资者应该怎么配置这只基金", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:30"], "difficulty": "medium"},
    {"query_id": 46, "query": "持有这只基金期间需要监测哪些关键指标", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:31"], "difficulty": "medium"},
    {"query_id": 47, "query": "基金的股票仓位是多少，行业配置集中吗", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:10"], "difficulty": "easy"},
    {"query_id": 48, "query": "渣打集团2025年股价表现怎么样", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:15"], "difficulty": "medium"},
    {"query_id": 49, "query": "美联储降息对香港本地银行有什么影响", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:15"], "difficulty": "hard"},
    {"query_id": 50, "query": "持有满一年的盈利概率是多少", "relevant": ["ac137cbd7c684470941de1c6deaa67f6:6"], "difficulty": "hard"},
]

# ============ 1. Embedding ============
def embed(text):
    """调用 Ollama 把文本编码成向量"""
    resp = requests.post(OLLAMA_URL, json={"model": EMBED_MODEL, "input": [text]}, timeout=30)
    resp.raise_for_status()
    return resp.json()["data"][0]["embedding"]

# ============ 2. 三种检索策略 ============
def hit_key(hit):
    """把 ES hit 转成 fileMd5:chunkId 格式"""
    src = hit["_source"]
    return f"{src['fileMd5']}:{src['chunkId']}"

def search_bm25(query, top_k):
    """策略 A：纯 BM25 全文检索"""
    body = {
        "size": top_k,
        "query": {"match": {"textContent": query}}
    }
    resp = requests.post(f"{ES_URL}/{INDEX}/_search", json=body, auth=ES_AUTH, timeout=15)
    return [hit_key(h) for h in resp.json()["hits"]["hits"]]

def search_knn(query_vec, top_k):
    """策略 B：纯 KNN 向量检索"""
    body = {
        "size": top_k,
        "knn": {
            "field": "vector",
            "query_vector": query_vec,
            "k": top_k,
            "num_candidates": top_k * 3
        }
    }
    resp = requests.post(f"{ES_URL}/{INDEX}/_search", json=body, auth=ES_AUTH, timeout=15)
    return [hit_key(h) for h in resp.json()["hits"]["hits"]]

def search_hybrid_rrf(query, query_vec, top_k):
    """策略 C：混合检索 = BM25 + KNN 召回 → RRF 融合 → 取 top_k"""
    # 两路各召回 RECALL_K 条
    bm25_hits = search_bm25(query, RECALL_K)
    knn_hits = search_knn(query_vec, RECALL_K)

    # RRF 融合：score = Σ 1/(K + rank)
    scores = {}
    for rank, key in enumerate(bm25_hits):
        scores[key] = scores.get(key, 0) + 1.0 / (RRF_K + rank + 1)
    for rank, key in enumerate(knn_hits):
        scores[key] = scores.get(key, 0) + 1.0 / (RRF_K + rank + 1)

    # 按融合分数降序，取 top_k
    ranked = sorted(scores.items(), key=lambda x: -x[1])
    return [key for key, _ in ranked[:top_k]]

# ============ 3. 指标计算 ============
def compute_hit(retrieved, relevant):
    """Hit@K：前 K 个结果里是否至少包含一个相关 chunk"""
    return 1.0 if any(r in relevant for r in retrieved) else 0.0

def compute_rr(retrieved, relevant):
    """Reciprocal Rank：第一个相关结果的排名倒数"""
    for i, key in enumerate(retrieved):
        if key in relevant:
            return 1.0 / (i + 1)
    return 0.0

# ============ 4. 主流程 ============
def run():
    print("=" * 65)
    print(f"ShadowRAG 检索评测 | 数据集 {len(DATASET)} 条 | Top-{TOP_K}")
    print("=" * 65)

    strategies = {"bm25": [], "knn": [], "hybrid": []}

    for i, tc in enumerate(DATASET, 1):
        qid, query, relevant, diff = tc["query_id"], tc["query"], tc["relevant"], tc["difficulty"]
        print(f"\n[{i}/{len(DATASET)}] Q{qid} ({diff}): {query}")

        # 策略 A：BM25
        bm25_ret = search_bm25(query, TOP_K)
        strategies["bm25"].append({
            "qid": qid, "hit": compute_hit(bm25_ret, relevant),
            "rr": compute_rr(bm25_ret, relevant), "difficulty": diff
        })
        print(f"  BM25   Hit={int(strategies['bm25'][-1]['hit'])} RR={strategies['bm25'][-1]['rr']:.2f}  ret={bm25_ret[:3]}")

        # 策略 B：KNN（需要先 embedding）
        qvec = embed(query)
        knn_ret = search_knn(qvec, TOP_K)
        strategies["knn"].append({
            "qid": qid, "hit": compute_hit(knn_ret, relevant),
            "rr": compute_rr(knn_ret, relevant), "difficulty": diff
        })
        print(f"  KNN    Hit={int(strategies['knn'][-1]['hit'])} RR={strategies['knn'][-1]['rr']:.2f}  ret={knn_ret[:3]}")

        # 策略 C：混合 RRF
        hybrid_ret = search_hybrid_rrf(query, qvec, TOP_K)
        strategies["hybrid"].append({
            "qid": qid, "hit": compute_hit(hybrid_ret, relevant),
            "rr": compute_rr(hybrid_ret, relevant), "difficulty": diff
        })
        print(f"  HYBRID Hit={int(strategies['hybrid'][-1]['hit'])} RR={strategies['hybrid'][-1]['rr']:.2f}  ret={hybrid_ret[:3]}")

    # ============ 5. 汇总输出 ============
    print("\n" + "=" * 65)
    print(f"{'策略':<10} {'Hit@5':<12} {'MRR':<12}")
    print("-" * 40)

    summary = {}
    for name, results in strategies.items():
        hit_rate = np.mean([r["hit"] for r in results])
        mrr = np.mean([r["rr"] for r in results])
        summary[name] = {"hit": hit_rate, "mrr": mrr}
        print(f"{name:<10} {hit_rate:<12.4f} {mrr:<12.4f}")

    # 对比
    print("\n" + "=" * 65)
    hit_lift = (summary["hybrid"]["hit"] - summary["bm25"]["hit"]) * 100
    mrr_lift = summary["hybrid"]["mrr"] - summary["bm25"]["mrr"]
    print(f"混合 vs BM25：Hit@5 提升 {hit_lift:+.1f} 个百分点，MRR 提升 {mrr_lift:+.4f}")
    print(f"混合 vs KNN ：Hit@5 提升 {(summary['hybrid']['hit']-summary['knn']['hit'])*100:+.1f} 个百分点，MRR 提升 {summary['hybrid']['mrr']-summary['knn']['mrr']:+.4f}")

    # ============ 6. 分难度统计 ============
    print("\n" + "=" * 65)
    print("分难度统计（Hit@5）：")
    print(f"{'难度':<10} {'BM25':<12} {'KNN':<12} {'Hybrid':<12} {'Hybrid-BM25':<12}")
    print("-" * 58)
    for diff in ["easy", "medium", "hard"]:
        row = {"bm25": [], "knn": [], "hybrid": []}
        for name in row:
            row[name] = [r["hit"] for r in strategies[name] if r["difficulty"] == diff]
        if not row["bm25"]:
            continue
        b, k, h = np.mean(row["bm25"]), np.mean(row["knn"]), np.mean(row["hybrid"])
        print(f"{diff:<10} {b:<12.4f} {k:<12.4f} {h:<12.4f} {(h-b)*100:+.1f}%")

    # 保存详细结果
    with open("eval_results.json", "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "details": strategies}, f, ensure_ascii=False, indent=2)
    print(f"\n详细结果已保存到 eval_results.json")

if __name__ == "__main__":
    run()
