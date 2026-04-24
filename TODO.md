# TODO

 ##  Backlog

- [ ] 引入 RRF 对 KNN + BM25 进行加权融合

> 用 RRF（Reciprocal Rank Fusion），基于排名而不是原始分数做融合，天然消除量纲问题。ES 8.x 原生支持，改动量也不大，只需要把 Rescore 换成 rrf 查询。这是我已经识别出来、计划优化的一个点。

- [ ] 