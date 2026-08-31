# ShadowRAG 路由开发集 v2：火山 Coding Plan glm-5-3-flash

## 总览

| 指标 | 值 |
|---|---:|
| 用例数 | 60 |
| 正确数 | 60 |
| Accuracy | 100.00% |
| Macro-F1 | 100.00% |

## 分类指标

| 路由 | Support | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| SEARCH | 30 | 100.00% | 100.00% | 100.00% |
| DIRECT | 30 | 100.00% | 100.00% | 100.00% |

## 混淆矩阵

| Expected \ Predicted | SEARCH | DIRECT |
|---|---:|---:|
| SEARCH | 30 | 0 |
| DIRECT | 0 | 30 |

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
| finance_general | 2 | 2 | 100.00% |
| general_advice | 4 | 4 | 100.00% |
| general_knowledge | 14 | 14 | 100.00% |
| general_task | 3 | 3 | 100.00% |
| hard_negative | 13 | 13 | 100.00% |
| hard_positive | 4 | 4 | 100.00% |
| implicit_document | 3 | 3 | 100.00% |
| math | 1 | 1 | 100.00% |
| multi_turn | 13 | 13 | 100.00% |
| named_document | 4 | 4 | 100.00% |
| programming | 4 | 4 | 100.00% |
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

无。
