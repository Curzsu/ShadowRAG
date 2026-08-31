# ShadowRAG 路由固定测试集 v2：火山 Coding Plan glm-5-3-flash

## 总览

| 指标 | 值 |
|---|---:|
| 用例数 | 120 |
| 正确数 | 120 |
| Accuracy | 100.00% |
| Macro-F1 | 100.00% |

## 分类指标

| 路由 | Support | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| SEARCH | 60 | 100.00% | 100.00% | 100.00% |
| DIRECT | 60 | 100.00% | 100.00% | 100.00% |

## 混淆矩阵

| Expected \ Predicted | SEARCH | DIRECT |
|---|---:|---:|
| SEARCH | 60 | 0 |
| DIRECT | 0 | 60 |

## 标签切片

| 标签 | 用例数 | 正确数 | Accuracy |
|---|---:|---:|---:|
| calculation | 3 | 3 | 100.00% |
| chitchat | 1 | 1 | 100.00% |
| citation | 7 | 7 | 100.00% |
| colloquial | 1 | 1 | 100.00% |
| comparison | 9 | 9 | 100.00% |
| explicit_document | 9 | 9 | 100.00% |
| fact_lookup | 32 | 32 | 100.00% |
| finance_general | 2 | 2 | 100.00% |
| general_advice | 18 | 18 | 100.00% |
| general_knowledge | 24 | 24 | 100.00% |
| hard_negative | 38 | 38 | 100.00% |
| hard_positive | 14 | 14 | 100.00% |
| health_general | 1 | 1 | 100.00% |
| internal_policy | 11 | 11 | 100.00% |
| multi_turn | 18 | 18 | 100.00% |
| named_document | 10 | 10 | 100.00% |
| project_specific | 8 | 8 | 100.00% |
| pronoun_reference | 6 | 6 | 100.00% |
| summarization | 4 | 4 | 100.00% |
| synthesis | 11 | 11 | 100.00% |
| technical_question | 25 | 25 | 100.00% |
| uploaded_file | 16 | 16 | 100.00% |
| writing | 4 | 4 | 100.00% |

## 错误用例

无。
