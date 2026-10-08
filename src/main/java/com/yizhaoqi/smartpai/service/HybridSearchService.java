package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankerClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import com.yizhaoqi.smartpai.observability.LangfuseTracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private RerankerClient rerankerClient;

    @Autowired
    private LangfuseTracing tracing = LangfuseTracing.noop();

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 该方法确保用户只能搜索其有权限访问的文档（自己的文档、公开文档、所属组织的文档）
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，用户ID: {}, topK: {}", userId, topK);
        
        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                LangfuseTracing.metadata(Span.current(), "retrieval_status", "bm25_fallback");
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }

            logger.debug("向量生成成功，开始执行混合搜索（Java 端 RRF 融合）");

            List<SearchResult> results;
            Span retrieval = tracing.startSearchStage("retrieval");
            LangfuseTracing.metadata(retrieval, "retrieval_strategy", "hybrid_rrf");
            try (Scope ignored = retrieval.makeCurrent()) {
                // 构建权限过滤 Query（KNN 和 BM25 共用）
                Query permissionFilter = buildPermissionFilter(userDbId, userEffectiveTags);
                int recallK = topK * 30;

                // 1. KNN 向量搜索
                SearchResponse<EsDocument> knnResponse = esClient.search(s -> s
                        .index("knowledge_base")
                        .knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                                .filter(permissionFilter)
                        )
                        .size(recallK),
                        EsDocument.class);
                logger.debug("KNN 搜索完成，命中: {}", knnResponse.hits().hits().size());

                // 2. BM25 文本搜索
                SearchResponse<EsDocument> bm25Response = esClient.search(s -> s
                        .index("knowledge_base")
                        .query(q -> q.bool(b -> b
                                .must(mst -> mst.match(m -> m.field("textContent").query(query)))
                                .filter(permissionFilter)
                        ))
                        .size(recallK),
                        EsDocument.class);
                logger.debug("BM25 搜索完成，命中: {}", bm25Response.hits().hits().size());

                // 3. Java 端 RRF 融合
                results = fuseWithRRF(
                        knnResponse.hits().hits(), bm25Response.hits().hits(), topK);
                logger.debug("RRF 融合后返回搜索结果数量: {}", results.size());
            } catch (Exception error) {
                LangfuseTracing.error(retrieval, "RETRIEVAL_ERROR");
                throw error;
            } finally {
                retrieval.end();
            }

            // 4. Cross-Encoder 精排（可选，需 TEI 服务可用）
            results = applyRerank(query, results, topK);
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            LangfuseTracing.metadata(Span.current(), "retrieval_status", "bm25_fallback");
            LangfuseTracing.metadata(Span.current(), "rerank_status", "skipped");
            logger.error("带权限的搜索失败，异常类型: {}", e.getClass().getSimpleName());
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearchWithPermission(query, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                LangfuseTracing.error(Span.current(), "SEARCH_ERROR");
                logger.error("后备搜索也失败，异常类型: {}", fallbackError.getClass().getSimpleName());
                return Collections.emptyList();
            }
        }
    }

    /**
     * 仅使用文本匹配的带权限搜索方法
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId, List<String> userEffectiveTags, int topK) {
        Span searchSpan = Span.current();
        Span retrieval = tracing.startSearchStage("retrieval");
        LangfuseTracing.metadata(retrieval, "retrieval_strategy", "bm25_fallback");
        List<SearchResult> results;
        try (Scope ignored = retrieval.makeCurrent()) {
            logger.debug("开始执行纯文本搜索，用户数据库ID: {}, 标签: {}", userDbId, userEffectiveTags);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q
                            .bool(b -> b
                                    // 匹配内容相关性
                                    .must(m -> m
                                            .match(ma -> ma
                                                    .field("textContent")
                                                    .query(query)
                                            )
                                    )
                                    // 权限过滤
                                    .filter(f -> f
                                            .bool(bf -> bf
                                                    // 条件1: 用户可以访问自己的文档
                                                    .should(s1 -> s1
                                                            .term(t -> t
                                                                    .field("userId")
                                                                    .value(userDbId)
                                                            )
                                                    )
                                                    // 条件2: 用户可以访问公开的文档
                                                    .should(s2 -> s2
                                                            .term(t -> t
                                                                    .field("public")
                                                                    .value(true)
                                                            )
                                                    )
                                                    // 条件3: 用户可以访问其所属组织的文档（包含层级关系）
                                                    .should(s3 -> {
                                                        if (userEffectiveTags.isEmpty()) {
                                                            return s3.matchNone(mn -> mn);
                                                        } else if (userEffectiveTags.size() == 1) {
                                                            // 单个标签使用 term 查询
                                                            return s3.term(t -> t
                                                                    .field("orgTag")
                                                                    .value(userEffectiveTags.get(0))
                                                            );
                                                        } else {
                                                            // 多个标签使用 bool should 组合多个 term 查询
                                                            return s3.bool(innerBool -> {
                                                                userEffectiveTags.forEach(tag ->
                                                                        innerBool.should(sh -> sh.term(t -> t
                                                                                .field("orgTag")
                                                                                .value(tag)
                                                                        ))
                                                                );
                                                                return innerBool;
                                                            });
                                                        }
                                                    })
                                            )
                                    )
                            )
                    )
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class
            );

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}", 
                response.hits().total().value(), response.hits().maxScore());

            results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        logger.debug("纯文本搜索结果 - 文件: {}, 块: {}, 分数: {}",
                            hit.source().getFileMd5(), hit.source().getChunkId(), hit.score());
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic()
                        );
                    })
                    .toList();

            logger.debug("返回纯文本搜索结果数量: {}", results.size());
        } catch (Exception e) {
            logger.error("纯文本搜索失败，异常类型: {}", e.getClass().getSimpleName());
            LangfuseTracing.error(searchSpan, "SEARCH_ERROR");
            LangfuseTracing.error(retrieval, "RETRIEVAL_ERROR");
            return new ArrayList<>();
        } finally {
            retrieval.end();
        }
        attachFileNames(results);
        return results;
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);
            
            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, topK);
            }

            int recallK = topK * 30;

            // KNN 向量搜索
            SearchResponse<EsDocument> knnResponse = esClient.search(s -> s
                    .index("knowledge_base")
                    .knn(kn -> kn
                            .field("vector")
                            .queryVector(queryVector)
                            .k(recallK)
                            .numCandidates(recallK)
                    )
                    .size(recallK),
                    EsDocument.class);

            // BM25 文本搜索
            SearchResponse<EsDocument> bm25Response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.match(m -> m.field("textContent").query(query)))
                    .size(recallK),
                    EsDocument.class);

            // Java 端 RRF 融合
            return fuseWithRRFSimple(knnResponse.hits().hits(), bm25Response.hits().hits(), topK);
        } catch (Exception e) {
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 仅使用文本匹配的搜索方法
     */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q
                        .match(m -> m
                                .field("textContent")
                                .query(query)
                        )
                )
                .size(topK),
                EsDocument.class
        );

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score()
                    );
                })
                .toList();
    }

    /**
     * Java 端 RRF 融合（带权限信息的 SearchHit）
     * 公式: score = Σ 1/(k + rank_i)，k=60
     */
    private List<SearchResult> fuseWithRRF(
            List<co.elastic.clients.elasticsearch.core.search.Hit<EsDocument>> knnHits,
            List<co.elastic.clients.elasticsearch.core.search.Hit<EsDocument>> bm25Hits,
            int topK) {
        final int K = 60;
        // key = fileMd5:chunkId
        Map<String, double[]> scoreMap = new HashMap<>();
        Map<String, EsDocument> docMap = new HashMap<>();

        // KNN 排名累加
        for (int i = 0; i < knnHits.size(); i++) {
            EsDocument doc = knnHits.get(i).source();
            if (doc == null) continue;
            String key = doc.getFileMd5() + ":" + doc.getChunkId();
            scoreMap.computeIfAbsent(key, k -> new double[1])[0] += 1.0 / (K + i + 1);
            docMap.putIfAbsent(key, doc);
        }

        // BM25 排名累加
        for (int i = 0; i < bm25Hits.size(); i++) {
            EsDocument doc = bm25Hits.get(i).source();
            if (doc == null) continue;
            String key = doc.getFileMd5() + ":" + doc.getChunkId();
            scoreMap.computeIfAbsent(key, k -> new double[1])[0] += 1.0 / (K + i + 1);
            docMap.putIfAbsent(key, doc);
        }

        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, double[]>comparingByValue(
                        (a, b) -> Double.compare(b[0], a[0])))
                .limit(topK)
                .map(entry -> {
                    String key = entry.getKey();
                    EsDocument doc = docMap.get(key);
                    logger.debug("RRF 结果 - 文件: {}, 块: {}, 分数: {}",
                            doc.getFileMd5(), doc.getChunkId(), entry.getValue()[0]);
                    return new SearchResult(
                            doc.getFileMd5(), doc.getChunkId(), doc.getTextContent(),
                            entry.getValue()[0], doc.getUserId(), doc.getOrgTag(), doc.isPublic());
                })
                .toList();
    }

    /**
     * Java 端 RRF 融合（简化版，无权限信息）
     */
    private List<SearchResult> fuseWithRRFSimple(
            List<co.elastic.clients.elasticsearch.core.search.Hit<EsDocument>> knnHits,
            List<co.elastic.clients.elasticsearch.core.search.Hit<EsDocument>> bm25Hits,
            int topK) {
        final int K = 60;
        Map<String, double[]> scoreMap = new HashMap<>();
        Map<String, EsDocument> docMap = new HashMap<>();

        for (int i = 0; i < knnHits.size(); i++) {
            EsDocument doc = knnHits.get(i).source();
            if (doc == null) continue;
            String key = doc.getFileMd5() + ":" + doc.getChunkId();
            scoreMap.computeIfAbsent(key, k -> new double[1])[0] += 1.0 / (K + i + 1);
            docMap.putIfAbsent(key, doc);
        }

        for (int i = 0; i < bm25Hits.size(); i++) {
            EsDocument doc = bm25Hits.get(i).source();
            if (doc == null) continue;
            String key = doc.getFileMd5() + ":" + doc.getChunkId();
            scoreMap.computeIfAbsent(key, k -> new double[1])[0] += 1.0 / (K + i + 1);
            docMap.putIfAbsent(key, doc);
        }

        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, double[]>comparingByValue(
                        (a, b) -> Double.compare(b[0], a[0])))
                .limit(topK)
                .map(entry -> {
                    EsDocument doc = docMap.get(entry.getKey());
                    return new SearchResult(
                            doc.getFileMd5(), doc.getChunkId(), doc.getTextContent(),
                            entry.getValue()[0]);
                })
                .toList();
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text) {
        Span embedding = tracing.startSearchStage("embedding");
        try (Scope ignored = embedding.makeCurrent()) {
            List<float[]> vecs = embeddingClient.embed(List.of(text));
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                LangfuseTracing.error(embedding, "EMBEDDING_UNAVAILABLE");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败，异常类型: {}", e.getClass().getSimpleName());
            LangfuseTracing.error(embedding, "EMBEDDING_ERROR");
            return null;
        } finally {
            embedding.end();
        }
    }
    
    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }
            
            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败，用户ID: {}, 异常类型: {}", userId, e.getClass().getSimpleName());
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败，用户ID: {}, 异常类型: {}", userId, e.getClass().getSimpleName());
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    /**
     * 构建权限过滤 Query：用户自己的文档 OR 公开文档 OR 所属组织的文档
     * KNN filter 和 BM25 filter 共用此方法
     */
    private Query buildPermissionFilter(String userDbId, List<String> userEffectiveTags) {
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        // 条件1: 用户可访问自己的文档
        boolBuilder.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
        // 条件2: 公开文档
        boolBuilder.should(s2 -> s2.term(t -> t.field("public").value(true)));
        // 条件3: 组织标签
        boolBuilder.should(s3 -> {
            if (userEffectiveTags.isEmpty()) {
                return s3.matchNone(mn -> mn);
            } else if (userEffectiveTags.size() == 1) {
                return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
            } else {
                return s3.bool(inner -> {
                    userEffectiveTags.forEach(tag -> inner.should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));
                    return inner;
                });
            }
        });
        return Query.of(q -> q.bool(boolBuilder.build()));
    }

    /**
     * 使用 Cross-Encoder 精排模型对 RRF 融合结果重排序
     * 如果 RerankerClient 不可用或调用失败，返回原始 RRF 排序结果
     */
    private List<SearchResult> applyRerank(String query, List<SearchResult> rrfResults, int topK) {
        Span searchSpan = Span.current();
        if (!rerankerClient.isEnabled() || rrfResults.isEmpty()) {
            LangfuseTracing.metadata(searchSpan, "rerank_status", "skipped");
            return rrfResults;
        }

        Span rerank = tracing.startSearchStage("rerank");
        try (Scope ignored = rerank.makeCurrent()) {
            // 提取文档文本用于 rerank
            List<String> documents = rrfResults.stream()
                    .map(SearchResult::getTextContent)
                    .toList();

            List<RerankerClient.RerankResult> rerankResults = rerankerClient.rerank(query, documents, topK);
            if (rerankResults == null) {
                LangfuseTracing.metadata(searchSpan, "rerank_status", "fallback");
                LangfuseTracing.error(rerank, "RERANK_UNAVAILABLE");
                logger.debug("Rerank 未执行或失败，保持 RRF 原始排序");
                return rrfResults;
            }

            // 按 rerank 结果重排序：用 rerank 分数替换 RRF 分数
            List<SearchResult> reranked = new ArrayList<>();
            for (RerankerClient.RerankResult rr : rerankResults) {
                SearchResult original = rrfResults.get(rr.index());
                reranked.add(new SearchResult(
                        original.getFileMd5(), original.getChunkId(), original.getTextContent(),
                        rr.score(), original.getUserId(), original.getOrgTag(), original.getIsPublic()
                ));
            }
            logger.debug("Cross-Encoder 精排完成，返回 {} 个结果", reranked.size());
            LangfuseTracing.metadata(searchSpan, "rerank_status", "success");
            return reranked;
        } catch (RuntimeException error) {
            LangfuseTracing.error(rerank, "RERANK_ERROR");
            throw error;
        } finally {
            rerank.end();
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName));
            // 填充文件名
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败，结果数量: {}, 异常类型: {}", results.size(), e.getClass().getSimpleName());
        }
    }
}
