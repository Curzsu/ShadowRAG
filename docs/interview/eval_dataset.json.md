# 检索评测数据集（基于数据库真实 chunk 构造）

> 生成日期：2026-06-15
> 数据来源：ShadowRAG 数据库 `document_vectors` 表的真实 chunk 内容
> 两个文件：
> - **面经-小红书收集.pdf**（file_md5=`f85f05936fc325cf34792163c880042c`，78 个 chunk）—— 各公司 AI/Agent/后端面经
> - **鹏华香港银行指数C基金评估报告.pdf**（file_md5=`ac137cbd7c684470941de1c6deaa67f6`，31 个 chunk）—— 结构化金融研报

---

## 数据集 A：面经文档（25 条 query）

```json
[
  {
    "query_id": 1,
    "query": "滴滴实习面试问了哪些RAG相关的问题",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:12", "f85f05936fc325cf34792163c880042c:13"],
    "difficulty": "easy"
  },
  {
    "query_id": 2,
    "query": "面试官问vector怎么设计的，向量维度选多少",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:12"],
    "difficulty": "medium"
  },
  {
    "query_id": 3,
    "query": "面试中被问到MCP和Skill的区别",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:2", "f85f05936fc325cf34792163c880042c:15"],
    "difficulty": "medium"
  },
  {
    "query_id": 4,
    "query": "哪些公司的面试涉及到了LangGraph",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:4", "f85f05936fc325cf34792163c880042c:7", "f85f05936fc325cf34792163c880042c:8"],
    "difficulty": "hard"
  },
  {
    "query_id": 5,
    "query": "面试中被追问Function Calling怎么匹配工具参数",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:2"],
    "difficulty": "easy"
  },
  {
    "query_id": 6,
    "query": "怎么评判预训练模型效果，LoRA的数学原理",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:7"],
    "difficulty": "medium"
  },
  {
    "query_id": 7,
    "query": "蔚来AI应用开发的面试题",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:7", "f85f05936fc325cf34792163c880042c:8"],
    "difficulty": "easy"
  },
  {
    "query_id": 8,
    "query": "面试官问Attention机制的核心作用和处理长文本的优化策略",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:8", "f85f05936fc325cf34792163c880042c:9"],
    "difficulty": "medium"
  },
  {
    "query_id": 9,
    "query": "怎么评估RAG系统的检索质量和生成质量",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:9", "f85f05936fc325cf34792163c880042c:10"],
    "difficulty": "medium"
  },
  {
    "query_id": 10,
    "query": "美团实习面试关于AI Coding和Vibe Coding的问题",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:6"],
    "difficulty": "easy"
  },
  {
    "query_id": 11,
    "query": "搜狐畅游面试问的FunctionCall到MCP到Skills三个阶段",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:15"],
    "difficulty": "easy"
  },
  {
    "query_id": 12,
    "query": "面试官追问MCP的描述携带会挤占上下文怎么办",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:16"],
    "difficulty": "medium"
  },
  {
    "query_id": 13,
    "query": "大模型幻觉问题怎么处理",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:18"],
    "difficulty": "easy"
  },
  {
    "query_id": 14,
    "query": "面试问AI拆解任务时怎么判断技术方案合不合理",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:19"],
    "difficulty": "medium"
  },
  {
    "query_id": 15,
    "query": "HNSW算法和IVF_FLAT算法的对比",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:20"],
    "difficulty": "medium"
  },
  {
    "query_id": 16,
    "query": "ReAct模式和Plan-Execute架构的区别",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:20", "f85f05936fc325cf34792163c880042c:40"],
    "difficulty": "medium"
  },
  {
    "query_id": 17,
    "query": "布隆过滤器的原理和怎么实现删除功能",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:2", "f85f05936fc325cf34792163c880042c:21"],
    "difficulty": "medium"
  },
  {
    "query_id": 18,
    "query": "Transformer除了Attention还有什么创新，残差连接的作用",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:30"],
    "difficulty": "medium"
  },
  {
    "query_id": 19,
    "query": "synchronized锁升级过程，为什么要锁升级",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:60"],
    "difficulty": "easy"
  },
  {
    "query_id": 20,
    "query": "MySQL事务隔离级别有哪几种，分别怎么实现的",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:70", "f85f05936fc325cf34792163c880042c:78"],
    "difficulty": "medium"
  },
  {
    "query_id": 21,
    "query": "Redisson分布式锁的原理和使用场景",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:60", "f85f05936fc325cf34792163c880042c:78"],
    "difficulty": "medium"
  },
  {
    "query_id": 22,
    "query": "会话记忆具体怎么实现的，滑动窗口设置几轮",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:3"],
    "difficulty": "medium"
  },
  {
    "query_id": 23,
    "query": "什么场景下需要用锁，常见的锁有哪些",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:60"],
    "difficulty": "easy"
  },
  {
    "query_id": 24,
    "query": "面试官建议学习LLM业务工程上的先进设计理念",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:3"],
    "difficulty": "hard"
  },
  {
    "query_id": 25,
    "query": "多智能体系统为什么要多agent，主agent和子agent共享上下文吗",
    "relevant_chunks": ["f85f05936fc325cf34792163c880042c:4", "f85f05936fc325cf34792163c880042c:20"],
    "difficulty": "hard"
  }
]
```

**难度分布**：easy 7 条 / medium 14 条 / hard 4 条

---

## 数据集 B：基金研报文档（25 条 query）

```json
[
  {
    "query_id": 26,
    "query": "鹏华香港银行指数C基金的基金代码是多少",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:2", "ac137cbd7c684470941de1c6deaa67f6:3"],
    "difficulty": "easy"
  },
  {
    "query_id": 27,
    "query": "这只基金跟踪的是什么指数",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:2"],
    "difficulty": "easy"
  },
  {
    "query_id": 28,
    "query": "基金经理是谁，管理经验怎么样",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:5"],
    "difficulty": "easy"
  },
  {
    "query_id": 29,
    "query": "这只基金2025年涨了多少，收益表现如何",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:6", "ac137cbd7c684470941de1c6deaa67f6:7"],
    "difficulty": "easy"
  },
  {
    "query_id": 30,
    "query": "基金的最大回撤是多少，波动大不大",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:8", "ac137cbd7c684470941de1c6deaa67f6:9"],
    "difficulty": "medium"
  },
  {
    "query_id": 31,
    "query": "这只基金的风险指标，夏普比率怎么样",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:8", "ac137cbd7c684470941de1c6deaa67f6:9"],
    "difficulty": "medium"
  },
  {
    "query_id": 32,
    "query": "第一大重仓股是哪个，占比多少",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:13"],
    "difficulty": "easy"
  },
  {
    "query_id": 33,
    "query": "前十大重仓股都有哪些",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:11", "ac137cbd7c684470941de1c6deaa67f6:12"],
    "difficulty": "easy"
  },
  {
    "query_id": 34,
    "query": "汇丰控股的股息率和ROE是多少",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:13"],
    "difficulty": "medium"
  },
  {
    "query_id": 35,
    "query": "内银股的估值水平，市盈率和市净率",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:14"],
    "difficulty": "medium"
  },
  {
    "query_id": 36,
    "query": "五大内银股合计占基金净值多少比例",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:14"],
    "difficulty": "medium"
  },
  {
    "query_id": 37,
    "query": "恒生银行和中银香港的占比和业绩",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:15"],
    "difficulty": "medium"
  },
  {
    "query_id": 38,
    "query": "恒生指数目前多少点，估值水平如何",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:16"],
    "difficulty": "easy"
  },
  {
    "query_id": 39,
    "query": "2026年A股市场表现怎么样，科技股和银行股谁强",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:17", "ac137cbd7c684470941de1c6deaa67f6:20"],
    "difficulty": "medium"
  },
  {
    "query_id": 40,
    "query": "南向资金流入港股的情况，偏好什么类型的股票",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:18"],
    "difficulty": "medium"
  },
  {
    "query_id": 41,
    "query": "港股银行股和A股银行股相比有什么优势",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:19", "ac137cbd7c684470941de1c6deaa67f6:20"],
    "difficulty": "medium"
  },
  {
    "query_id": 42,
    "query": "银行板块面临的房地产风险有多大",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:25"],
    "difficulty": "medium"
  },
  {
    "query_id": 43,
    "query": "这只基金综合评分多少，处于什么区间",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:28"],
    "difficulty": "easy"
  },
  {
    "query_id": 44,
    "query": "现在可以买入这只基金吗，投资建议是什么",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:28"],
    "difficulty": "easy"
  },
  {
    "query_id": 45,
    "query": "稳健型投资者应该怎么配置这只基金",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:30"],
    "difficulty": "medium"
  },
  {
    "query_id": 46,
    "query": "持有这只基金期间需要监测哪些关键指标",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:31"],
    "difficulty": "medium"
  },
  {
    "query_id": 47,
    "query": "基金的股票仓位是多少，行业配置集中吗",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:10"],
    "difficulty": "easy"
  },
  {
    "query_id": 48,
    "query": "渣打集团2025年股价表现怎么样",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:15"],
    "difficulty": "medium"
  },
  {
    "query_id": 49,
    "query": "美联储降息对香港本地银行有什么影响",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:15"],
    "difficulty": "hard"
  },
  {
    "query_id": 50,
    "query": "持有满一年的盈利概率是多少",
    "relevant_chunks": ["ac137cbd7c684470941de1c6deaa67f6:6"],
    "difficulty": "hard"
  }
]
```

**难度分布**：easy 10 条 / medium 13 条 / hard 2 条

---

## 设计说明（面试时怎么讲这套数据集的构造）

### 三种查询类型覆盖

| 类型 | 占比 | 示例 | 设计意图 |
|---|---|---|---|
| **关键词直接匹配** | 约 40% | "鹏华香港银行指数C基金的基金代码" | BM25 强项，验证基线 |
| **语义换说法** | 约 45% | "这只基金2025年涨了多少"（文档原文"净值增长率达到34.20%"） | KNN 强项，验证语义增益 |
| **多 chunk 交叉 / 隐式** | 约 15% | "美联储降息对香港本地银行的影响"（需要从 chunk15 的"息差收窄vs资产质量改善"推理） | 验证 RRF 融合 + rerank 对复杂查询的能力 |

### 标注规范

- `relevant_chunks` 用 `fileMd5:chunkId` 格式，与评测脚本 `retrieve()` 返回值对齐；
- 多数 query 标注 1 个主相关 chunk，约 30% 标注 2 个（跨 chunk 的连续内容，如 Q26 的基金代码在 chunk2 和 chunk3 都有）；
- 难度判定标准：easy=关键词直接命中；medium=需要语义理解或跨 1-2 个 chunk；hard=需要推理或多文档综合。

### 为什么这个数据集有效

1. **基于真实文档内容**：query 不是凭空造的，每条都能对应到数据库里真实存在的 chunk，避免"标注的相关 chunk 系统里根本没有"的致命错误；
2. **覆盖两种典型文档**：面经是**非结构化杂糅文本**（多家公司、多个主题混在一起），基金报告是**结构化长文**（有目录、表格、章节）——两种都是企业知识库的典型场景；
3. **难度分布合理**：easy/medium/hard 三档，medium 占主体（符合真实用户提问分布），能暴露各策略在不同难度下的差异。
