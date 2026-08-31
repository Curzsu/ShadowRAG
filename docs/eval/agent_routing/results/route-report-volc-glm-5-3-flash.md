# ShadowRAG 路由候选集初测：火山 Coding Plan glm-5-3-flash

## 总览

| 指标 | 值 |
|---|---:|
| 用例数 | 120 |
| 正确数 | 93 |
| Accuracy | 77.50% |
| Macro-F1 | 76.30% |

## 分类指标

| 路由 | Support | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| SEARCH | 60 | 68.97% | 100.00% | 81.63% |
| DIRECT | 60 | 100.00% | 55.00% | 70.97% |

## 混淆矩阵

| Expected \ Predicted | SEARCH | DIRECT |
|---|---:|---:|
| SEARCH | 60 | 0 |
| DIRECT | 27 | 33 |

## 标签切片

| 标签 | 用例数 | 正确数 | Accuracy |
|---|---:|---:|---:|
| calculation | 3 | 2 | 66.67% |
| chitchat | 1 | 1 | 100.00% |
| citation | 7 | 7 | 100.00% |
| colloquial | 1 | 1 | 100.00% |
| comparison | 9 | 9 | 100.00% |
| explicit_document | 9 | 9 | 100.00% |
| fact_lookup | 32 | 32 | 100.00% |
| finance_general | 2 | 2 | 100.00% |
| general_advice | 18 | 7 | 38.89% |
| general_knowledge | 24 | 15 | 62.50% |
| hard_negative | 38 | 17 | 44.74% |
| hard_positive | 14 | 14 | 100.00% |
| health_general | 1 | 1 | 100.00% |
| internal_policy | 11 | 11 | 100.00% |
| multi_turn | 18 | 18 | 100.00% |
| named_document | 10 | 10 | 100.00% |
| project_specific | 8 | 8 | 100.00% |
| pronoun_reference | 6 | 6 | 100.00% |
| summarization | 4 | 4 | 100.00% |
| synthesis | 11 | 11 | 100.00% |
| technical_question | 25 | 14 | 56.00% |
| uploaded_file | 16 | 16 | 100.00% |
| writing | 4 | 3 | 75.00% |

## 错误用例

| ID | Expected | Predicted | Tags | Query |
|---|---|---|---|---|
| test-direct-001 | DIRECT | SEARCH | general_knowledge, hard_negative | 试用期和正式劳动合同期限通常是什么关系？ |
| test-direct-003 | DIRECT | SEARCH | general_knowledge, hard_negative | 发票清单一般需要包含哪些字段？ |
| test-direct-005 | DIRECT | SEARCH | general_knowledge, hard_negative | 数据留存期限应该依据哪些因素制定？ |
| test-direct-009 | DIRECT | SEARCH | writing, hard_negative | 后端简历怎样描述 Spring Cloud 项目更有说服力？ |
| test-direct-012 | DIRECT | SEARCH | general_advice, hard_negative | 如何从会议记录里提取行动项？ |
| test-direct-013 | DIRECT | SEARCH | technical_question, general_knowledge | RAG 的父子分块策略有什么优缺点？ |
| test-direct-019 | DIRECT | SEARCH | technical_question, general_knowledge | 数据库迁移为什么通常需要双写阶段？ |
| test-direct-020 | DIRECT | SEARCH | general_advice, hard_negative | 内推奖励机制怎样设计更公平？ |
| test-direct-021 | DIRECT | SEARCH | technical_question, general_knowledge | API 版本升级时如何保持向后兼容？ |
| test-direct-022 | DIRECT | SEARCH | calculation, general_knowledge | 客服投诉环比增长率怎么算？ |
| test-direct-023 | DIRECT | SEARCH | general_advice, hard_negative | 敏感数据导出审批流程一般有哪些控制点？ |
| test-direct-025 | DIRECT | SEARCH | technical_question, general_advice | 排查支付超时时，怎样区分网关和数据库问题？ |
| test-direct-030 | DIRECT | SEARCH | general_advice, hard_negative | 方案评审时常见的否决原因有哪些？ |
| test-direct-032 | DIRECT | SEARCH | technical_question, general_knowledge | 置信度阈值过高会带来什么问题？ |
| test-direct-033 | DIRECT | SEARCH | technical_question, hard_negative | 查询改写在 RAG 中通常有哪些作用？ |
| test-direct-034 | DIRECT | SEARCH | technical_question, hard_negative | Elasticsearch 索引命名有哪些通用建议？ |
| test-direct-035 | DIRECT | SEARCH | technical_question, hard_negative | 重排服务超时时常见的降级方案是什么？ |
| test-direct-036 | DIRECT | SEARCH | general_advice, hard_negative | 转正审批流程一般如何设计？ |
| test-direct-040 | DIRECT | SEARCH | general_advice, hard_negative | P0 故障的行业通用响应流程是什么？ |
| test-direct-041 | DIRECT | SEARCH | general_advice, hard_negative | 多家供应商报价应该如何综合比较？ |
| test-direct-042 | DIRECT | SEARCH | general_knowledge, hard_negative | 隐私政策里为什么要提供撤回授权入口？ |
| test-direct-043 | DIRECT | SEARCH | technical_question, hard_negative | Kafka 消息积压事故通常暴露哪些治理问题？ |
| test-direct-047 | DIRECT | SEARCH | technical_question, hard_negative | HyDE 在什么情况下可能导致语义漂移？ |
| test-direct-050 | DIRECT | SEARCH | general_advice, hard_negative | 住宿报销标准每年调整时要考虑哪些因素？ |
| test-direct-052 | DIRECT | SEARCH | technical_question, hard_negative | 混合检索中 BM25 权重应该如何调参？ |
| test-direct-053 | DIRECT | SEARCH | general_advice, hard_negative | 事故升级时应该先通知哪些角色？ |
| test-direct-054 | DIRECT | SEARCH | general_advice, hard_negative | 如何识别银行流水里的重复交易？ |
