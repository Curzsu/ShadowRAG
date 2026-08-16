# 金融场景 PDF 解析与 RAG 切分策略方案设计

> 生成日期：2026-06-15
> 适用场景：金融文档（财报、招股书、研究报告、基金公告）的 RAG 知识库构建
> 核心目标：解决表格被当文本切碎、数字归列靠猜、无法溯源等问题

---

## 零、原有切分策略

> 在分析问题之前，先完整梳理项目当前的切分策略——它做了什么、怎么做的、适用于什么场景。后续的硬伤分析（第一节）和增强方案（第二节起）都建立在这套基础之上。

### 0.1 核心配置

```yaml
# application.yml
file:
  parsing:
    chunk-size: 512             # 目标分块大小（字符数）
    parent-chunk-size: 1048576  # 父块大小（1MB，流式处理用）
    buffer-size: 8192
    max-memory-threshold: 0.8
```

### 0.2 策略本质：把整个文档当"一坨纯文本"处理

无论文件是 PDF、Word、txt，最终都被 MinerU/Tika 转成一段 **markdown 纯文本字符串**，然后交给 `ParseService.splitTextIntoChunksWithSemantics(content, 512)` 切。

**它完全不知道哪段是表格、哪段是标题、哪段是正文——全部一视同仁按字符切。**

```
PDF → MinerU/Tika → 一整段 markdown 字符串 → 按 512 字符切 → 78 个 text_content 块
         （结构信息在这里全部丢失）
```

### 0.3 切分的四级递进逻辑（`ParseService.java:309-453`）

这是一个**尽力保持语义边界**的贪心算法，按优先级逐级降级：

```
第1级：按段落切（\n\n+ 为分隔符）
   ↓ 某个段落超过 512 字符？
第2级：按句子切（。！？；.!?; 为分隔符）
   ↓ 某个句子超过 512 字符？
第3级：用 HanLP 中文分词，按词切
   ↓ HanLP 异常？
第4级：按字符硬切（兜底）
```

详细规则：

| 级别 | 触发条件 | 切分依据 | 代码位置 |
|------|---------|---------|---------|
| **段落级** | 默认 | `\n\n+`（空行分段），段落能塞下就累积，塞不下就开新块 | `splitTextIntoChunksWithSemantics` L309-354 |
| **句子级** | 单段落 > 512 | 按 `。！？；` 和 `.!?;` 切句，句子能塞下就累积 | `splitLongParagraph` L359-390 |
| **词级** | 单句子 > 512 | HanLP `StandardTokenizer.segment()` 分词，按词累积 | `splitLongSentence` L395-428 |
| **字符级** | HanLP 异常 | 逐字符硬切，每 512 一个 | `splitByCharacters` L433-453 |

### 0.4 分词器选型：切分用 HanLP，检索用 IK

项目里其实**同时用了两套分词器**，各管一摊。这点必须区分清楚，因为"切分阶段的分词"和"检索阶段的分词"是完全不同的两个场景，对分词器的诉求也不同。

| 场景 | 分词器 | 用在哪 | 干什么 |
|------|--------|--------|--------|
| **切分阶段** | HanLP | `ParseService`（Java 进程内） | 把超长文本切成 512 字符的 chunk |
| **检索阶段** | IK | ES `knowledge_base` 索引 | 对 `textContent` 建倒排索引 + 查询分词 |

#### 为什么检索端"被 ES 绑定了"——索引分词和查询分词必须同源

这是中文搜索的核心原理。ES 对 `textContent` 的配置是：

```json
"textContent": {
  "type": "text",
  "analyzer": "ik_max_word",       // 索引时：最细粒度，尽量多切
  "search_analyzer": "ik_smart"    // 搜索时：智能切，切得粗
}
```

**索引阶段**（写入文档时）：

```
原文："开放式指数基金跟踪中证香港银行指数"

用 ik_max_word 切词（最细粒度）：
  开放 / 开放式 / 指数 / 基金 / 指数基金 / 跟踪 / 中证 / 香港 / 银行 / 指数

→ 所有可能的词都建进倒排索引，后续无论用户怎么搜都能匹配
```

**查询阶段**（用户搜索时）：

```
用户输入："香港银行基金"

用 ik_smart 切词（智能模式）：
  香港 / 银行 / 基金

→ 拿这三个词去倒排索引找，全部命中 → 召回
```

**为什么索引和查询必须用同源分词器？**

如果索引端用 IK、查询端用 HanLP，会因为**两个分词器的词典和切词规则不一致**，导致查询时切出来的词在索引里找不到：

```
场景：文档里有专业术语"可转债"

索引（IK 切）：  可转债          ← IK 词典里有"可转债"，切成一个词
查询（HanLP 切）：可 / 转债       ← HanLP 词典可能没有"可转债"，切成两个

→ 用户搜"可转债"，HanLP 切成 [可, 转债]
→ "可"命中一堆无关文档（可以/可能/可行...）
→ "转债"在索引里根本不存在（索引存的是"可转债"整个词）
→ 该召回的召不回，不该召回的召一堆，BM25 打分全乱
```

**类比**：编目录（索引）用什么规则，查目录（查询）就必须用什么规则。两套规则认知不一致，目录里的词条和用户查的词对不上，自然查不到。

→ **结论：检索端的分词器选择，不是"自由选型"，而是被 ES 索引绑定的硬约束**——索引用 IK，查询就得用 IK（或同源的 IK 变体）。

#### 为什么切分端可以独立选 HanLP

切分阶段**没有这个约束**。它只是把长文本切成 512 字符的块，不需要和任何索引保持一致。所以可以自由选型，标准是"轻量、独立、够用"：

| 候选 | 是否适合切分阶段 | 原因 |
|------|-----------------|------|
| **HanLP** ✅ 选它 | 适合 | 独立 Java 库，引入一个 Maven 依赖直接调；纯本地、无网络、无外部服务；`StandardTokenizer.segment()` 轻量稳定 |
| **IK** ❌ | 不适合 | 为 ES 生态设计，强依赖 Lucene Analyzer 体系；切分阶段用 IK 要么远程调 ES 分词（增加网络依赖和故障点），要么引入 lucene-analyzer 在 JVM 跑（配置比 HanLP 重） |
| **jieba** ❌ | 不适合 | Python 生态，Java 项目要用得跨进程（起 Python 服务）；其 Java 移植版 `jieba-analysis` 社区维护已不活跃，词典更新和兼容性不如 HanLP |

**核心取舍**：切分是在文件解析流程里同步做的，需要一个零外部依赖、纯本地的轻量分词器。HanLP 完美契合，而 IK 的优势在"和 ES 深度绑定"，这个优势在切分阶段用不上。所以让 **IK 专注检索、HanLP 专注切分**，各司其职。

> 补充：HanLP 在切分中只是"第 3 级降级"，不是主力。大部分文本走段落级（第 1 级）和句子级（第 2 级）就够了，只有超长无标点文本才兜底走 HanLP 分词。选 HanLP 是因为兜底场景下"够用、稳、轻"。

### 0.5 流式处理大文件（父子文档策略）

为避免大文件 OOM，Tika 解析路径采用流式处理：

```
Tika 流式输出 → StreamingContentHandler
   ├─ characters() 攒数据到 buffer
   ├─ buffer 达到 1MB（parentChunkSize）→ 触发 processParentChunk()
   │    ├─ 把 1MB 父块按上述四级逻辑切成子切片（512字符）
   │    └─ 批量写入 document_vectors
   └─ endDocument() flush 剩余数据 + 保存全文到 MinIO
```

> 注意：此流式策略仅适用于 Tika 解析路径（`parseAndSave`）。MinerU 路径（`parseAndSaveByMinerU`）是先拿到完整 markdown 字符串再一次性切分，不走流式。

### 0.6 策略的优点（不是一无是处）

1. **实现简单**：纯字符串处理，不依赖文档结构解析
2. **对纯文本友好**：txt、md、问答列表、段落文章切得还不错
3. **三级降级兜底**：HanLP 挂了还有字符切，不会崩
4. **流式处理大文件**：避免 OOM

### 0.7 策略的致命缺陷（金融场景）

1. **表格被切碎**——HTML 表格遇到 `\n\n` 或 512 上限就被腰斩，切出来的是"半截无表头表格"残片
2. **无类型感知**——标题、正文、表格、图片说明没有区别对待
3. **无页码/无溯源**——切完只剩 `chunk_id` 和 `text_content`，不知道内容来自原文哪里
4. **章节上下文丢失**——切到某章节下的内容时，文本块里不带章节标题上下文，embedding 不知道在讲什么

> 以上缺陷的实测证据见 **第 1.3 节**。后续章节的增强方案，正是针对这些缺陷设计的。

---

## 一、现状与硬伤分析

### 1.1 当前数据流

```
前端分片上传 → MinIO 合并 → Kafka 任务 → FileProcessingConsumer
  → MinerU 解析（/file_parse）
  → ParseService.splitTextIntoChunksWithSemantics（按 \n\n + 512字符切）
  → document_vectors 表入库
  → EmbeddingClient（Ollama bge-m3）批量生成向量
  → Elasticsearch knowledge_base 索引
```

### 1.2 三个核心硬伤

| 硬伤 | 现状（代码证据） | 金融场景后果 |
|------|------------------|-------------|
| **① MinerU 调用时没开 content_list** | `MinerUClient.java:56-67` 只传了 `files`，所有 `return_*` 参数走默认值（全 false）→ 只返回 md | 表格行列结构丢失，数字归列靠猜 |
| **② 表格被按 512 字符拦腰切** | `ParseService.java:172` 把整个 markdown 当一坨文本，走 `splitTextIntoChunksWithSemantics` 按 `\n\n`+512 切 | 一个 20 行持仓表被切成 4 段，每段都是无表头的数字残片，embedding 出来全是垃圾 |
| **③ ES 文档无类型/无定位** | `EsDocument.java` 只有 `textContent/chunkId/vector/权限`，没有 block 类型、页码、坐标 | 无法回答"这个数在第几页哪个表"，无法对表格做特殊检索 |

> **②是致命的**——它让任何带大表的金融文档在向量库里变成一堆无法理解的碎片。

### 1.3 实测案例：鹏华基金评估报告的切分效果

> 用真实金融文档验证硬伤②，数据来自 `document_vectors` 表实际查询结果。
> 测试文件：`鹏华香槟银行指数基金评估报告.pdf`（fileMd5: `ac137cbd7c684470941de1c6deaa67f6`）

#### 整体切分统计

| 指标 | 数据 |
|------|------|
| 总分块数 | 31 |
| 含表格标签的块 | 9 |
| **表格被拦腰切断的** | **3 个（占表格类块的 33%）** |
| 完全无法理解的残片 | 至少 3 块 |
| 块长度范围 | 57 ~ 512 字符 |
| 平均块长度 | 351 字符 |

#### 🔴 被切断的表格案例（3 个）

**案例 A：基金基本信息表（chunk 3 → 4 被截断）**

| chunk | 长度 | 内容特征 | 问题 |
|-------|------|---------|------|
| chunk 3 | 512 | `<table>基金全称...跟踪标的: 中证香港银行投资指数<` | 512 字符卡住，表头 `<table>` 在此，但末尾 `<td>` 被截断 |
| chunk 4 | **57** | `td></tr><tr><td>投资范围</td><td>港股通银行类上市公司</td></tr></table>` | **只剩 57 字符的残片**，无表头、无上下文，是孤立的 HTML 碎片 |

→ embedding 出来完全是垃圾。检索"投资范围是什么"时，召回到的就是 `td></tr><tr><td>投资范围...` 这种残片。

**案例 B：前十大持仓表（chunk 11 → 12 被截断）—— 最致命**

| chunk | 长度 | 内容特征 | 问题 |
|-------|------|---------|------|
| chunk 11 | 507 | 表头 + 第 1-5 名（汇丰 16.13%、建行 15.32%、工行 12.98%、中行 9.06%、中银香港 7.82%），第 6 名农业银行被截断在 `01288.HK<` | 有表头有前 5 名，但第 6 名被截断 |
| chunk 12 | 357 | 第 6-10 名（农行 6.54%、恒生 5.23%、招行 4.87%、渣打 3.95%、交行 3.62%）`</table>` | **完全没有表头**！召回到此块时，只看到一堆裸百分数和银行名，不知道这是"前十大持仓"，不知道列名 |

→ 问"前十大持仓占比"，若召回 chunk 12，LLM 看到 `6.54%、5.23%、4.87%...` 这些裸数字，**无法知道它们对应哪个银行、属于什么类别**。

**案例 C：评估打分表（chunk 26 → 27 被截断）**

| chunk | 长度 | 内容特征 | 问题 |
|-------|------|---------|------|
| chunk 26 | 512 | 表头 + 前 5 个评估维度（估值吸引力、业绩、宏观、资金面、持仓集中度），第 6 个"房地产风险"被截断 | 512 截断 |
| chunk 27 | 240 | `</td>10% 58 5.8...基金管理质量、汇率与地缘风险</table>` | 后半截，表头在另一块，维度名"房地产风险"被劈成 `房地产风险` + `</td>` |

#### 🟢 完整保留的表格（2 个，靠运气）

| chunk | 表格 | 没被切的原因 |
|-------|------|-------------|
| chunk 7 | 业绩表现表（8 行） | 长度 504，**恰好卡在 512 以内** |
| chunk 29 | 投资建议表（7 行） | 长度 503，**同样恰好没超** |

> 这两个表格完整保留纯属运气（长度刚好在阈值以下）。任何超过 ~500 字符的表格都会被破坏。

#### 结论

现有切分策略对金融文档是**系统性破坏**：

1. **表格被腰斩概率高**：只要表格超过 ~500 字符（约 8-10 行），就必然被切。金融文档的表格大多超此规模。
2. **切断后产生无表头残片**：后半段表格丢失表头和上下文，变成裸数字，embedding 无意义。
3. **召回质量灾难性下降**：被切碎的表格块，要么召不到，要么召到半截残片，LLM 无法基于它回答数字类问题。
4. **完全靠运气**：唯一完整的两个表格是因为长度恰好没超 512，这是不可靠的。

### 1.4 现有 ES mapping（待增强）

```json
{
  "knowledge_base": {
    "mappings": {
      "properties": {
        "chunkId":      { "type": "integer" },
        "fileMd5":      { "type": "keyword" },
        "id":           { "type": "text" },
        "isPublic":     { "type": "boolean" },
        "modelVersion": { "type": "keyword" },
        "orgTag":       { "type": "keyword" },
        "textContent":  { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
        "userId":       { "type": "keyword" },
        "vector":       { "type": "dense_vector", "dims": 1024, "index": true, "similarity": "cosine" }
      }
    }
  }
}
```

---

## 二、方案总体架构：结构感知存储 + 双轨切分

核心思想：**不要把所有内容都当文本切。表格有表格的存法，文本有文本的切法。**

```
MinerU（开启 content_list）
   │
   ├─ md_content          → 存 MinIO（全文预览，保留）
   ├─ content_list[]      → 结构化块列表（新增，核心）
   │     每块带 type=text/table/figure/title + page_idx + bbox
   │
   ▼
结构化路由切分（ParseService 新逻辑）
   │
   ├─ type=text/title  → 现有语义切分（文本 chunkSize，保留）
   ├─ type=table       → 整表保留，不切断（新增）
   │      转成「自然语言描述 + markdown + 原始二维数组」三件套
   └─ type=figure      → 暂时跳过（金融场景图片多为示意图，价值低）
   │
   ▼
统一写入 ES（mapping 增强）
   每条文档带：chunkType(向量检索可过滤)、pageIdx、parentTableId
```

---

## 三、关键设计点详解

### 3.1 MinerU 调用改造（最小改动，最大收益）

**改动位置**：`MinerUClient.parseToMarkdown`

调用 `/file_parse` 时新增 query param：

```java
.uri(uriBuilder -> uriBuilder.path("/file_parse")
    .queryParam("return_content_list", "true")   // 新增：返回结构化块列表
    .queryParam("return_md", "true")             // 保留 markdown
    .build())
```

**各 `return_*` 参数的取舍依据：**

| 参数 | 是否开启 | 理由 |
|------|---------|------|
| `return_md` | ✅ 开 | markdown 全文，供预览和文本切分 |
| `return_content_list` | ✅ 开 | 轻量结构化块列表，带 type/page_idx，切分核心依赖 |
| `return_middle_json` | ❌ 不开 | 版面分析中间产物，几十KB~MB级，金融场景用不上 |
| `return_images` | ❌ 不开 | 按页截图，金融示意图价值低，存储爆炸 |
| `response_format_zip` | ❌ 不开 | 返回 ZIP 会破坏现有 JSON 解析逻辑 |

**存储策略**：
- content_list 整体存一份到 MinIO（`parsed/{md5}.content_list.json`）
- 解析时读入内存按块处理
- **不进 DB 大字段**（避免 MySQL 膨胀）

---

### 3.2 表格的"三件套"表示（金融场景的灵魂）

一个表格块不能只存 HTML，要存三样东西：

```
对一个表格块（比如"前十大持仓"），生成：

① 自然语言摘要（用于 embedding 和语义检索）
   "本表为前十大持仓明细。包含股票代码、股票名称、所属行业、
    持仓权重、近一年涨跌幅等字段。持仓第一名贵州茅台(600519)，
    占比8.7%，属于食品饮料行业..."

② markdown 表格（喂给 LLM 生成回答时用，保留视觉结构）
   | 排名 | 股票代码 | 股票名称 | 行业 | 持仓权重 | 近一年涨幅 |
   |------|---------|---------|------|---------|-----------|
   | 1    | 600519  | 贵州茅台 | 食品饮料 | 8.7%  | +12.3% |

③ 原始二维数组（用于精确数值检索和结构化查询）
   [["排名","股票代码",...],["1","600519","贵州茅台",...]]
```

**为什么三件套？金融问答的三种典型问题各需要不同表示：**

| 问题类型 | 例子 | 靠哪个表示召回 |
|---------|------|---------------|
| 语义模糊查询 | "这个基金重仓白酒吗" | ① 自然语言摘要（embedding 召回） |
| 结构化事实 | "前十大持仓是哪些" | ② markdown（召回后整表返回给 LLM） |
| 精确数值 | "贵州茅台占比多少""持仓>5%的有几个" | ③ 二维数组（可做数值过滤/计算） |

**① 自然语言摘要的生成方式：**

- **轻量版（规则生成，零成本，推荐 MVP）**：
  模板化，从表头 + 前几行数据自动拼接。例如：
  `[文件名]的[表格标题]表，含[N]行数据，字段包括[表头]。首行数据：[第一行]。`
- **重量版（LLM 生成，准但慢，建议后期）**：
  把表格喂给小模型生成一段描述。准确但增加解析耗时和成本。

> MVP 阶段用规则生成，在语义检索上已比裸 HTML 强一个数量级。

---

### 3.3 双轨切分策略（替代现有"一刀切"）

这是对 `ParseService.splitTextIntoChunksWithSemantics` 的根本性重构。

**现状的问题**：拿到 markdown 字符串 → 按 `\n\n` 分段 → 512 切。这个逻辑对纯文本对，对表格错。

**新策略：按 content_list 的每个 block 路由**

```java
// 伪代码：新的切分入口
List<EsDocument> splitByStructure(List<ContentBlock> blocks) {
    List<EsDocument> result = new ArrayList<>();
    StringBuilder textBuffer = new StringBuilder();

    for (ContentBlock block : blocks) {
        switch (block.type) {
            case "text":
            case "title":
                // 累积文本块，攒够 chunkSize 再切（复用现有 splitTextIntoChunksWithSemantics）
                textBuffer.append(block.text);
                if (textBuffer.length() >= chunkSize) {
                    result.addAll(flushTextBuffer(textBuffer));  // 文本走老逻辑
                }
                break;

            case "table":
                // 1. 先把累积的文本 flush 掉（表格和文本不混在一个 chunk）
                if (textBuffer.length() > 0) {
                    result.addAll(flushTextBuffer(textBuffer));
                }
                // 2. 表格整体作为一个 chunk，无论多大
                TableChunk tc = buildTableChunk(block);  // 三件套
                result.add(tc);
                break;

            case "image":
                // MVP 跳过，或存个占位"[图片：第N页]"
                break;
        }
    }
    // flush 剩余文本
    if (textBuffer.length() > 0) {
        result.addAll(flushTextBuffer(textBuffer));
    }
    return result;
}
```

**表格不切断的取舍**：一个持仓表可能有 50 行、3000 字符，超过 chunkSize。**不切**。原因：
- 切了就破坏行列对应，前半截有表头后半截没有，召回后半截时 LLM 根本看不懂
- bge-m3 支持 8192 tokens（约 4000 中文字符），3000 字符的表在限制内
- 如果遇到超巨型表（>8192 token），才做"按行保留表头"的二次切分（每个子表都带表头行）

---

### 3.4 ES mapping 增强

现有 mapping（10个字段）新增 4 个：

```json
{
  "chunkType":     { "type": "keyword" },      // text | table | title —— 检索时可过滤"只要表格"
  "pageIdx":       { "type": "integer" },      // 原文页码 —— 溯源用
  "parentTableId": { "type": "keyword" },      // 表格块的归属ID —— 大表切分子块时关联
  "tableData":     {                           // 表格的原始二维数组（仅table块有）
    "type": "object",
    "enabled": false                            // 不索引，仅存储+原样返回给LLM
  }
}
```

**关键设计：`tableData` 设 `enabled:false`**
- 金融表格数字多，做全文索引没意义（不会按 "8.7" 检索）
- 但 LLM 生成回答时需要原始数据
- 所以只存不索引，省索引开销，召回时直接返回

**检索增益：支持"混合检索 + 类型过滤"**

```json
// 查"贵州茅台持仓"，优先返回表格块
{
  "query": { "bool": {
    "must": [...向量+BM25...],
    "filter": [ {"term": {"chunkType": "table"}} ]   // 可选：只要表格
  }}
}
```

这让"查数字"类问题能精准命中表格，而非正文里提到茅台的某句话。

---

## 四、切分策略的关键决策

### Q1：chunkSize 还是 512 吗？

**文本块降到 350-400，表格块放开到 8192 token 上限。**

| 类型 | chunkSize | 理由 |
|------|-----------|------|
| 文本块 | 350-400 字符 | 金融文本信息密度高，512 字符容易塞两个不相关论点；350 更适合 bge-m3 语义聚焦 |
| 表格块 | 整块（上限 8192 token） | 必须整块，切断就破坏行列对应 |

### Q2：表格三件套里的"自然语言摘要"放哪？embed 什么？

**只 embed 自然语言摘要，不 embed markdown/二维数组。**

- ES 一条文档的 `textContent`（被 embedding 的字段）= 自然语言摘要
- markdown 和 tableData 作为附加字段存储
- 召回后拼进 LLM 的 context

这样向量空间干净（语义描述），但返回给 LLM 的信息完整（带结构）。

### Q3：标题块（title）怎么处理？要不要单独成块？

**标题不单独成块，但作为"上下文锚点"注入后续文本块。**

金融文档强依赖标题层级：
```
二、投资组合分析 → （一）股票持仓 → 1. 前十大重仓
```

切文本块时，把当前章节路径拼到 chunk 前面：
```
[章节: 二、投资组合分析 / （一）股票持仓]
正文内容...
```

这让每个文本块自带"我在讲什么"的上下文，召回质量大幅提升。content_list 里 title 块天然带层级，利用起来零成本。

### Q4：MinerU 解析失败/降级到 Tika 时怎么办？

**降级路径走纯文本模式，表格能力丢失但流程不断。**

- 保留现有 `shouldUseMinerU()` + Tika fallback 的设计
- 金融场景下 Tika 降级时表格质量差
- 在文档上标个 `parseBackend=tika` 提示用户"该文档表格解析可能不准"
- 不阻断流程

---

## 五、MinerU content_list 字段说明

通过 `/openapi.json` 确认 MinerU 2.5+ 的 `/file_parse` 接口支持以下参数：

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `files` | array[binary] | 必填 | 上传的文件 |
| `return_md` | boolean | true | 返回 markdown |
| `return_content_list` | boolean | false | 返回结构化块列表 |
| `return_middle_json` | boolean | false | 返回版面分析中间 JSON |
| `return_images` | boolean | false | 返回提取的图片 |
| `return_model_output` | boolean | false | 返回模型原始输出 |
| `response_format_zip` | boolean | false | 以 ZIP 代替 JSON |
| `backend` | string | hybrid-auto-engine | 解析后端 |
| `parse_method` | string | auto | 解析方法 (auto/txt/ocr) |
| `start_page_id` | int | 0 | 起始页 |
| `end_page_id` | int | 99999 | 结束页 |

**content_list 中每个 block 的典型结构：**

```json
{
  "type": "table",            // text | title | table | image
  "text": "...",              // 文本内容（table 为 HTML/markdown）
  "table_body": [[...]],      // 仅 table：带行列坐标的二维数组
  "table_caption": "...",     // 仅 table：表标题
  "page_idx": 0,              // 在原文第几页（0-based）
  "bbox": [100, 200, 500, 600]// 在页面上的位置框 [x0, y0, x1, y1]
}
```

---

## 六、实施路径（分阶段）

| 阶段 | 做什么 | 收益 | 工作量 |
|------|--------|------|--------|
| **P0** | MinerU 加 `return_content_list=true`；切分逻辑按 type 路由（表格整存） | 表格不再被切碎，召回质量立即提升 | 2-3 天 |
| **P1** | ES mapping 加 chunkType/pageIdx；表格三件套（规则版摘要） | 支持表格过滤检索 + 溯源 | 2-3 天 |
| **P2** | 章节路径注入；chunkSize 文本/表格分离调优 | 文本块语义聚焦，上下文完整 | 1-2 天 |
| **P3** | LLM 生成表格摘要（替换规则版）；大表按行二次切分 | 复杂表格召回准确率再提一档 | 3-5 天 |

> **P0 必须先做**——现在表格被切碎的问题，让任何带表的金融文档在知识库里都是半残废。

---

## 七、涉及改造的文件清单

| 文件 | 改造内容 | 阶段 |
|------|---------|------|
| `MinerUClient.java` | 调用加 `return_content_list=true`；解析 content_list 并返回结构化对象 | P0 |
| `ParseService.java` | 新增 `splitByStructure` 按块类型路由；表格走 `buildTableChunk` 三件套 | P0/P1 |
| `EsDocument.java` | 新增 `chunkType / pageIdx / parentTableId / tableData / tableMarkdown` 字段 | P1 |
| ES mapping（`knowledge_base`） | 新增 4 个字段定义，需做 mapping 迁移 | P1 |
| `ElasticsearchService.java` | bulkIndex 适配新字段；检索时支持 chunkType 过滤 | P1/P2 |
| `VectorizationService.java` | embedding 只取自然语言摘要，不再 embed 整个 markdown | P0 |
| `FileProcessingConsumer.java` | 调用链适配（顺带修事务回滚问题） | P0 |

---

## 八、一句话总结

> **金融场景的核心矛盾是：表格是结构化数据，但被当文本切了。**
>
> 方案的解法是：让 MinerU 返回结构化的 content_list，切分时按 block 类型路由——文本走语义切分，表格整存并生成"语义摘要 + markdown + 二维数组"三件套，ES 加类型/页码字段支持过滤检索和溯源。
