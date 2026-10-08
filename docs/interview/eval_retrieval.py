"""
ShadowRAG 检索评测脚本
默认保留 legacy ES + Ollama 的 BM25 / KNN / RRF 模式。
--java-results 模式读取真实 Java 调用，比较 BM25 / hybrid_rerank 的 Hit@5 与 MRR@5。
"""
import json
import os

import argparse
import base64
import hashlib
import math
from pathlib import Path
import re
import sys
import time
import uuid
from collections import Counter
from urllib import request
from urllib.error import HTTPError
from urllib.parse import urlsplit

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
    import requests
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
    import requests
    """策略 A：纯 BM25 全文检索"""
    body = {
        "size": top_k,
        "query": {"match": {"textContent": query}}
    }
    resp = requests.post(f"{ES_URL}/{INDEX}/_search", json=body, auth=ES_AUTH, timeout=15)
    return [hit_key(h) for h in resp.json()["hits"]["hits"]]

def search_knn(query_vec, top_k):
    import requests
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
    import numpy as np
    print("LEGACY RRF: standalone ES/Ollama evaluation; no Java permission or rerank parity")
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



# Java export mode uses only the standard library and never creates traces.
STRATEGIES = ('bm25', 'hybrid_rerank')
KEY_PATTERN = re.compile(r'[0-9a-fA-F]{32}:[0-9]+\Z')
# ES fileMd5 is an opaque String; public benchmark fixtures also use safe file IDs.
RETRIEVED_KEY_PATTERN = re.compile(r'[A-Za-z0-9_.-]{1,128}:[0-9]+\Z')


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _summary(rows, total):
    valid = [r for r in rows if r['score_status'] == 'scored']
    return {
        'total_count': total, 'valid_count': len(valid),
        'error_count': sum(r['score_status'] == 'retrieval_error' for r in rows),
        'retrieval_status_counts': dict(Counter(r['retrieval_status'] for r in rows)),
        'rerank_status_counts': dict(Counter(r['rerank_status'] for r in rows)),
        'hit_at_5': sum(r['hit_at_5'] for r in valid) / len(valid) if valid else None,
        'mrr_at_5': sum(r['mrr_at_5'] for r in valid) / len(valid) if valid else None,
    }


def evaluate_java_results(results_path, dataset_path):
    """Validate one complete Java run, score ranks 1..5 and retain all evidence."""
    results_path, dataset_path = Path(results_path), Path(dataset_path)
    dataset_bytes = dataset_path.read_bytes()
    dataset = json.loads(dataset_bytes.decode('utf-8-sig'))
    _require(isinstance(dataset, list) and dataset, 'dataset must be a nonempty array')
    labels = {}
    for sample in dataset:
        _require(isinstance(sample, dict), 'invalid dataset sample')
        qid = sample.get('query_id')
        _require(type(qid) in (int, str) and str(qid).strip(), 'invalid query_id')
        sample_id = str(qid)
        _require(sample_id not in labels, 'duplicate query_id')
        relevant = sample.get('relevant')
        _require(isinstance(relevant, list) and relevant and all(isinstance(k, str) and KEY_PATTERN.fullmatch(k) for k in relevant), 'relevant labels must be known chunk keys')
        _require(len(relevant) == len(set(relevant)), 'duplicate relevant labels')
        _require(isinstance(sample.get('query'), str) and sample['query'].strip(), 'query is required')
        _require(sample.get('difficulty') in ('easy', 'medium', 'hard'), 'unknown difficulty label')
        labels[sample_id] = sample
    metadata = json.loads((results_path.parent / 'metadata.json').read_text(encoding='utf-8-sig'))
    _require(isinstance(metadata, dict) and metadata.get('dataset_sha256') == hashlib.sha256(dataset_bytes).hexdigest(), 'dataset checksum mismatch')
    _require(isinstance(metadata.get('config'), dict), 'metadata config is required')
    for name, expected in (('export_status', 'complete'), ('preflight_status', 'success'), ('permission_unchanged', True), ('index_snapshot_unchanged', True)):
        if name in metadata:
            _require(type(metadata[name]) is type(expected) and metadata[name] == expected, 'export integrity metadata rejected')
    for name, expected in (('top_k', 10), ('metric_top_k', 5)):
        if name in metadata['config']:
            _require(type(metadata['config'][name]) is int and metadata['config'][name] == expected, 'metadata top_k mismatch')
    scored, seen, run_ids = [], set(), set()
    for line in results_path.read_text(encoding='utf-8-sig').splitlines():
        _require(bool(line.strip()), 'blank result row')
        row = json.loads(line)
        _require(isinstance(row, dict), 'invalid result row')
        _require(all(k in row for k in ('run_id', 'sample_id', 'strategy', 'trace_id', 'retrieved', 'top_k', 'error', 'retrieval_status', 'rerank_status')), 'missing result field')
        _require(isinstance(row['run_id'], str) and row['run_id'].strip(), 'invalid run_id')
        run_ids.add(row['run_id'])
        _require(isinstance(row['sample_id'], str) and row['sample_id'] in labels, 'unknown sample_id')
        _require(row['strategy'] in STRATEGIES, 'unknown strategy')
        identity = (row['sample_id'], row['strategy'])
        _require(identity not in seen, 'duplicate sample strategy')
        seen.add(identity)
        _require(isinstance(row['trace_id'], str) and re.fullmatch(r'[0-9a-f]{32}', row['trace_id']) and int(row['trace_id'], 16) != 0, 'invalid trace_id')
        _require(type(row['top_k']) is int and row['top_k'] == 10, 'Java top_k must be 10')
        _require(row['retrieval_status'] in ('success', 'bm25_fallback', 'error'), 'unknown retrieval status')
        _require(row['rerank_status'] in ('success', 'skipped', 'fallback', 'not_applicable', 'unknown'), 'unknown rerank status')
        _require(row['error'] is None or isinstance(row['error'], str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', row['error']), 'error must be a controlled code')
        _require((row['retrieval_status'] == 'error') == (row['error'] is not None), 'inconsistent error status')
        retrieved = row['retrieved']
        _require(isinstance(retrieved, list) and len(retrieved) <= 10, 'invalid retrieved array')
        keys = []
        for hit in retrieved:
            _require(isinstance(hit, dict) and isinstance(hit.get('key'), str) and RETRIEVED_KEY_PATTERN.fullmatch(hit['key']), 'invalid chunk key')
            score = hit.get('score')
            _require(type(score) in (int, float) and math.isfinite(score), 'invalid retrieval score')
            keys.append(hit['key'])
        _require(len(keys) == len(set(keys)), 'duplicate retrieved key')
        sample = labels[row['sample_id']]
        if 'langfuse_dataset_id' in sample:
            _require(isinstance(row.get('observation_id'), str) and re.fullmatch(r'[0-9a-f]{16}', row['observation_id']) and int(row['observation_id'], 16) != 0, 'invalid experiment root observation')
            for field in ('dataset_id', 'dataset_item_id', 'dataset_version'):
                _require(row.get(field) == sample.get('langfuse_' + field), 'experiment dataset identity mismatch')
            _require(isinstance(row.get('experiment_id'), str) and bool(row['experiment_id']), 'missing experiment_id')
            _require(row.get('experiment_name') == row['run_id'] + '-' + row['strategy'], 'experiment name mismatch')
        valid = row['retrieval_status'] != 'error'
        scored.append({**row, 'query_id': sample['query_id'], 'query': sample['query'], 'difficulty': sample['difficulty'], 'relevant': sample['relevant'],
                       'score_status': 'scored' if valid else 'retrieval_error',
                       'actual_strategy': 'bm25' if row['strategy'] == 'bm25' or row['retrieval_status'] == 'bm25_fallback' else ('hybrid_rerank' if row['rerank_status'] == 'success' else 'hybrid_without_successful_rerank'),
                       'hit_at_5': compute_hit(keys[:5], sample['relevant']) if valid else None,
                       'mrr_at_5': compute_rr(keys[:5], sample['relevant']) if valid else None})
    _require(len(run_ids) == 1, 'expected one run_id')
    if 'run_id' in metadata:
        _require(metadata['run_id'] == next(iter(run_ids)), 'metadata run_id mismatch')
    _require(seen == {(sid, strategy) for sid in labels for strategy in STRATEGIES}, 'missing sample strategy rows')
    _require(len({r['trace_id'] for r in scored}) == len(scored), 'duplicate trace_id')
    experiment_rows = [r for r in scored if 'experiment_id' in r]
    if experiment_rows:
        _require(len(experiment_rows) == len(scored), 'mixed experiment and legacy rows')
        ids_by_strategy = [{r['experiment_id'] for r in scored if r['strategy'] == s} for s in STRATEGIES]
        _require(all(len(ids) == 1 for ids in ids_by_strategy) and ids_by_strategy[0].isdisjoint(ids_by_strategy[1]), 'inconsistent experiment identity')
    by_pair = {(r['sample_id'], r['strategy']): r for r in scored}
    paired_ids = [sid for sid in labels if all(by_pair[(sid, strategy)]['retrieval_status'] == 'success' for strategy in STRATEGIES) and by_pair[(sid, 'hybrid_rerank')]['rerank_status'] == 'success']
    paired = {strategy: _summary([by_pair[(sid, strategy)] for sid in paired_ids], len(labels)) for strategy in STRATEGIES}
    report = {'run_id': next(iter(run_ids)), 'metadata': metadata, 'metric_top_k': 5, 'java_top_k': 10,
              'strategies': {strategy: _summary([r for r in scored if r['strategy'] == strategy], len(labels)) for strategy in STRATEGIES},
              'paired': {'total_count': len(labels), 'valid_count': len(paired_ids), 'excluded_count': len(labels) - len(paired_ids), 'sample_ids': paired_ids, 'strategies': paired,
                         'hit_at_5_lift_percentage_points': (paired['hybrid_rerank']['hit_at_5'] - paired['bm25']['hit_at_5']) * 100 if paired_ids else None,
                         'mrr_at_5_lift': paired['hybrid_rerank']['mrr_at_5'] - paired['bm25']['mrr_at_5'] if paired_ids else None}}
    return scored, report


def score_payloads(scored):
    payloads = []
    for row in scored:
        if row['score_status'] != 'scored':
            continue
        for metric in ('hit_at_5', 'mrr_at_5'):
            identity = json.dumps([row['run_id'], row['sample_id'], row['strategy'], metric], separators=(',', ':'), ensure_ascii=False)
            score_id = str(uuid.UUID(hex=hashlib.sha256(identity.encode('utf-8')).hexdigest()[:32]))
            payloads.append({'id': score_id, 'traceId': row['trace_id'], 'name': metric, 'value': float(row[metric]), 'dataType': 'NUMERIC',
                             'comment': f"run={row['run_id']}; sample={row['sample_id']}; strategy={row['strategy']}; retrieval_status={row['retrieval_status']}; rerank_status={row['rerank_status']}"})
    for payload in payloads:
        source = next(r for r in scored if r['trace_id'] == payload['traceId'])
        if 'observation_id' in source:
            _require(isinstance(source['observation_id'], str) and re.fullmatch(r'[0-9a-f]{16}', source['observation_id']) and int(source['observation_id'], 16) != 0, 'invalid observation_id')
            payload['observationId'] = source['observation_id']
    return payloads


class _NoScoreRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Do not forward Basic authentication to a redirected host or protocol.
        raise HTTPError(req.full_url, code, 'score_upload_redirect_rejected', headers, fp)


def upload_scores(payloads):
    """Attach deterministic scores to existing Java traces; never log credentials."""
    base = os.environ.get('LANGFUSE_BASE_URL', '').rstrip('/')
    public = os.environ.get('LANGFUSE_PUBLIC_KEY', '')
    secret = os.environ.get('LANGFUSE_SECRET_KEY', '')
    parsed = urlsplit(base)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or not public or not secret:
        raise ValueError('score_upload_configuration_invalid')
    auth = base64.b64encode(f'{public}:{secret}'.encode()).decode('ascii')
    opener = request.build_opener(_NoScoreRedirect())
    uploaded = 0
    for payload in payloads:
        req = request.Request(base + '/api/public/scores', data=json.dumps(payload).encode('utf-8'), headers={'Authorization': 'Basic ' + auth, 'Content-Type': 'application/json'}, method='POST')
        for attempt in range(3):
            try:
                with opener.open(req, timeout=10) as response:
                    if not 200 <= response.status < 300:
                        raise RuntimeError('score_upload_http_error')
                break
            except HTTPError as error:
                retry_after = error.headers.get('Retry-After') if error.headers else None
                retryable = error.code == 429 and attempt < 2
                error.close()
                if not retryable:
                    raise RuntimeError('score_upload_failed') from None
                try:
                    delay = float(retry_after)
                    if not math.isfinite(delay) or delay < 0:
                        delay = 30.0
                except (TypeError, ValueError):
                    delay = 30.0
                time.sleep(min(delay, 60.0))
            except Exception:
                raise RuntimeError('score_upload_failed') from None
        uploaded += 1
    return uploaded


def main(argv=None):
    parser = argparse.ArgumentParser(description='Java permission-matched retrieval evaluation; no arguments runs legacy RRF')
    parser.add_argument('--java-results', type=Path)
    parser.add_argument('--dataset', type=Path)
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--upload-scores', action='store_true')
    args = parser.parse_args(argv)
    if args.java_results is None:
        if args.dataset or args.output_dir or args.upload_scores:
            parser.error('--java-results, --dataset and --output-dir are required together')
        run()
        return 0
    if args.dataset is None or args.output_dir is None:
        parser.error('--java-results, --dataset and --output-dir are required together')
    try:
        scored, report = evaluate_java_results(args.java_results, args.dataset)
    except (ValueError, OSError, TypeError, KeyError):
        print('Java evaluation input rejected; check dataset, metadata and result contract.', file=sys.stderr)
        return 2
    args.output_dir.mkdir(parents=True, exist_ok=True)
    payloads = score_payloads(scored)
    for filename, content in (('scored_results.json', scored), ('report.json', report), ('scores.json', payloads)):
        (args.output_dir / filename).write_text(json.dumps(content, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    (args.output_dir / 'java_results.jsonl').write_bytes(args.java_results.read_bytes())
    (args.output_dir / 'dataset.json').write_bytes(args.dataset.read_bytes())
    (args.output_dir / 'metadata.json').write_text(json.dumps(report['metadata'], ensure_ascii=False, indent=2), encoding='utf-8')
    upload = {'status': 'disabled', 'score_count': len(payloads)}
    exit_code = 0
    if args.upload_scores:
        try:
            upload.update(status='success', uploaded_count=upload_scores(payloads))
        except Exception:
            upload.update(status='failed', error='score_upload_failed', retry='rerun with the same Java results; score IDs are stable')
            print('Score upload failed; local results preserved. Retry with the same results.', file=sys.stderr)
            exit_code = 1
    (args.output_dir / 'upload_status.json').write_text(json.dumps(upload, indent=2), encoding='utf-8')
    print(f"Java retrieval evaluation saved; paired valid samples: {report['paired']['valid_count']}/{report['paired']['total_count']}.")
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
