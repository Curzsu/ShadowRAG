# ShadowRAG 路由开发集 v1：火山 Coding Plan glm-5-3-flash

## 总览

| 指标 | 值 |
|---|---:|
| 用例数 | 60 |
| 正确数 | 53 |
| Accuracy | 88.33% |
| Macro-F1 | 88.17% |

## 分类指标

| 路由 | Support | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| SEARCH | 30 | 81.08% | 100.00% | 89.55% |
| DIRECT | 30 | 100.00% | 76.67% | 86.79% |

## 混淆矩阵

| Expected \ Predicted | SEARCH | DIRECT |
|---|---:|---:|
| SEARCH | 30 | 0 |
| DIRECT | 7 | 23 |

## 标签切片

| 标签 | 用例数 | 正确数 | Accuracy |
|---|---:|---:|---:|
| chitchat | 1 | 1 | 100.00% |
| citation | 1 | 1 | 100.00% |
| colloquial | 5 | 5 | 100.00% |
| comparison | 4 | 4 | 100.00% |
| domain_policy | 2 | 2 | 100.00% |
| ellipsis | 1 | 1 | 100.00% |
| entity_lookup | 1 | 1 | 100.00% |
| explicit_document | 15 | 15 | 100.00% |
| fact_lookup | 12 | 12 | 100.00% |
| finance_general | 2 | 0 | 0.00% |
| general_advice | 4 | 2 | 50.00% |
| general_knowledge | 14 | 12 | 85.71% |
| general_task | 3 | 3 | 100.00% |
| hard_negative | 13 | 8 | 61.54% |
| hard_positive | 4 | 4 | 100.00% |
| implicit_document | 3 | 3 | 100.00% |
| math | 1 | 1 | 100.00% |
| multi_turn | 13 | 12 | 92.31% |
| named_document | 4 | 4 | 100.00% |
| programming | 4 | 2 | 50.00% |
| pronoun | 3 | 3 | 100.00% |
| reason_lookup | 1 | 1 | 100.00% |
| recommendation_lookup | 1 | 1 | 100.00% |
| section_reference | 2 | 2 | 100.00% |
| summarization | 4 | 4 | 100.00% |
| synthesis | 1 | 1 | 100.00% |
| technical_question | 8 | 8 | 100.00% |
| uploaded_file | 2 | 2 | 100.00% |
| writing | 4 | 4 | 100.00% |

## 错误用例

| ID | Expected | Predicted | Tags | Query |
|---|---|---|---|---|
| dev-direct-013 | DIRECT | SEARCH | programming, general_knowledge | Kafka 消费者为什么会发生 Rebalance？ |
| dev-direct-021 | DIRECT | SEARCH | multi_turn, programming | 那 volatile 能保证原子性吗？ |
| dev-direct-024 | DIRECT | SEARCH | hard_negative, finance_general | 基金的最大回撤一般怎么计算？ |
| dev-direct-025 | DIRECT | SEARCH | hard_negative, finance_general | 稳健型投资者配置基金时通常看哪些指标？ |
| dev-direct-026 | DIRECT | SEARCH | hard_negative, general_advice | 面试时被问到 MCP 和 Skill，应该怎么回答？ |
| dev-direct-027 | DIRECT | SEARCH | hard_negative, general_knowledge | 技术方案里为什么需要写风险和回滚计划？ |
| dev-direct-028 | DIRECT | SEARCH | hard_negative, general_advice | 生产变更审批流程通常如何设计？ |
