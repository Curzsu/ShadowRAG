package com.yizhaoqi.smartpai.observability;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.service.OrgTagCacheService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalEvaluationExportTest {
    @Test void experimentUsesCloudItemIdentityAndScoresRootWithChildContext() throws Exception {
        var memory=InMemorySpanExporter.create();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(memory)).build()) {
            var tracing=new LangfuseTracing(provider.get("test"),provider,"test");
            var sample=new ObjectMapper().readTree("{\"langfuse_dataset_id\":\"cloud-dataset\",\"langfuse_dataset_item_id\":\"cloud-item\",\"langfuse_dataset_version\":\"2026-10-08T00:00:00Z\",\"relevant\":[\"md5:1\"],\"difficulty\":\"easy\"}");
            var row=new RetrievalEvaluationExporter(tracing).export("run","1","hybrid_rerank","approved query","hash",sample,() -> {
                var child=tracing.startSearchStage("retrieval"); child.end();
                return List.of(new SearchResult("md5",1,"private chunk",0.8));
            });
            var root=memory.getFinishedSpanItems().stream().filter(s -> s.getName().equals("retrieval.evaluation")).findFirst().orElseThrow();
            var child=memory.getFinishedSpanItems().stream().filter(s -> s.getName().equals("retrieval")).findFirst().orElseThrow();
            assertEquals(root.getSpanId(),row.get("observation_id"));
            assertEquals("cloud-item",root.getAttributes().get(AttributeKey.stringKey("langfuse.experiment.item.id")));
            assertEquals(root.getSpanId(),child.getAttributes().get(AttributeKey.stringKey("langfuse.experiment.item.root_observation_id")));
            assertEquals(root.getAttributes().get(AttributeKey.stringKey("langfuse.experiment.id")),child.getAttributes().get(AttributeKey.stringKey("langfuse.experiment.id")));
            assertEquals(root.getSpanId(),child.getParentSpanId());
            assertFalse(root.getParentSpanContext().isValid());
            assertFalse(root.getAttributes().toString().contains("private chunk"));
            assertNotNull(root.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")));
            assertNull(io.opentelemetry.api.baggage.Baggage.current().getEntryValue("langfuse.experiment.id"));
        }
    }
    @Test void exportsActualOrderedTopTenResultsWithoutContent() throws Exception {
        var calls=new AtomicInteger();
        var service=new HybridSearchService() {
            @Override public List<SearchResult> searchWithPermission(String query,String user,int topK) {
                assertEquals("private query",query); assertEquals("explicit-user",user); assertEquals(10,topK);
                calls.incrementAndGet(); LangfuseTracing.metadata(Span.current(), "rerank_status", "success");
                return List.of(new SearchResult("md5",3,"private chunk",0.8),new SearchResult("md5",1,"secret",0.2));
            }
        };
        var memory=InMemorySpanExporter.create();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(memory)).build()) {
            var tracing=new LangfuseTracing(provider.get("test"),provider,"test");
            var ambient=provider.get("test").spanBuilder("ambient").startSpan();
            Map<String,Object> row;
            try(Scope ignored=ambient.makeCurrent()) {
                row=new RetrievalEvaluationExporter(tracing).export("run","1","hybrid_rerank","private query","hash",
                        () -> service.searchWithPermission("private query","explicit-user",10));
                assertSame(ambient,Span.current());
            } finally { ambient.end(); }
            assertEquals(10,row.get("top_k"));
            assertEquals(List.of(Map.of("key","md5:3","score",0.8),Map.of("key","md5:1","score",0.2)),row.get("retrieved"));
            assertEquals("success",row.get("rerank_status")); assertEquals("success",row.get("retrieval_status"));
            assertNull(row.get("error")); assertTrue(row.get("trace_id").toString().matches("[a-f0-9]{32}"));
            String json=new ObjectMapper().writeValueAsString(row);
            assertFalse(json.contains("private query")); assertFalse(json.contains("private chunk"));
            var root=memory.getFinishedSpanItems().stream().filter(s -> s.getName().equals("retrieval.evaluation")).findFirst().orElseThrow();
            assertFalse(root.getParentSpanContext().isValid());
            assertEquals(LangfuseTracing.hash("private query"),root.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.query_sha256")));
            assertFalse(root.getAttributes().toString().contains("private query")); assertEquals(1,calls.get());
        }
    }

    @Test void capturesSkippedRerankFallbackAndSwallowedSearchErrorSeparately() {
        try(var provider=SdkTracerProvider.builder().build()) {
            var exporter=new RetrievalEvaluationExporter(new LangfuseTracing(provider.get("test"),provider,"test"));
            var disabled=exporter.export("run","1","hybrid_rerank","query","hash",() -> {
                LangfuseTracing.metadata(Span.current(),"rerank_status","skipped"); return List.of();
            });
            assertEquals("skipped",disabled.get("rerank_status")); assertEquals("success",disabled.get("retrieval_status"));
            var fallback=exporter.export("run","1","hybrid_rerank","query","hash",() -> {
                LangfuseTracing.metadata(Span.current(),"retrieval_status","bm25_fallback"); return List.of();
            });
            assertEquals("bm25_fallback",fallback.get("retrieval_status")); assertEquals("skipped",fallback.get("rerank_status"));
            assertNull(fallback.get("error"));
            var failed=exporter.export("run","1","hybrid_rerank","query","hash",() -> {
                LangfuseTracing.error(Span.current(),"SEARCH_ERROR"); return List.of();
            });
            assertEquals("error",failed.get("retrieval_status")); assertEquals("SEARCH_ERROR",failed.get("error"));
            assertNotEquals(disabled.get("trace_id"),fallback.get("trace_id"));
            assertNotEquals(fallback.get("trace_id"),failed.get("trace_id"));
        }
    }

    @Test void baselineIsIndependentAndExceptionsAreControlled() throws Exception {
        try(var provider=SdkTracerProvider.builder().build()) {
            var exporter=new RetrievalEvaluationExporter(new LangfuseTracing(provider.get("test"),provider,"test"));
            var baseline=exporter.export("run","1","bm25","query","hash",List::of);
            assertEquals("not_applicable",baseline.get("rerank_status")); assertEquals("success",baseline.get("retrieval_status"));
            var failed=exporter.export("run","2","bm25","query","hash",() -> {throw new IllegalStateException("secret raw source");});
            assertEquals("error",failed.get("retrieval_status")); assertEquals("SEARCH_ERROR",failed.get("error"));
            assertEquals(List.of(),failed.get("retrieved"));
            assertFalse(new ObjectMapper().writeValueAsString(failed).contains("secret"));
        }
        assertThrows(IllegalStateException.class,() -> new RetrievalEvaluationExporter(LangfuseTracing.noop())
                .export("run","1","bm25","query","hash",List::of));
    }

    /** This nested context is never loaded by ordinary Maven tests. */
    @Nested
    @EnabledIfEnvironmentVariable(named="LANGFUSE_RETRIEVAL_EVAL",matches="true")
    @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
            "spring.jpa.show-sql=false","spring.kafka.listener.auto-startup=false","logging.level.org.hibernate.SQL=OFF"})
    class RealServices {
        @Autowired HybridSearchService service;
        @Autowired LangfuseTracing tracing;
        @Autowired ElasticsearchClient es;
        @Autowired UserRepository users;
        @Autowired OrgTagCacheService orgTags;
        @Autowired ObjectMapper mapper;
        @Autowired Environment environment;

        @Test void exportsBothStrategiesFromFrozenDatasetWithExplicitUser() throws Exception {
            Path dataset=Path.of(required("RETRIEVAL_EVAL_DATASET"));
            Path outputDir=Path.of(required("RETRIEVAL_EVAL_OUTPUT_DIR"));
            String username=required("RETRIEVAL_EVAL_USER"), run=required("RETRIEVAL_EVAL_RUN_ID");
            Files.createDirectories(outputDir);
            Path rows=outputDir.resolve("java-results.jsonl"), metadataFile=outputDir.resolve("metadata.json");
            assertFalse(Files.exists(rows),"OUTPUT_ALREADY_EXISTS");
            byte[] datasetBytes=Files.readAllBytes(dataset);
            JsonNode samples=mapper.readTree(datasetBytes);
            validateDataset(samples);
            var metadata=new LinkedHashMap<String,Object>();
            metadata.put("run_id",run); metadata.put("dataset_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datasetBytes)));
            metadata.put("user_sha256",LangfuseTracing.hash(username)); metadata.put("sample_count",samples.size());
            var config=new LinkedHashMap<String,Object>();
            config.put("top_k",10); config.put("recall_k",300); config.put("rrf_k",60);
            config.put("bm25_min_score",0.3); config.put("index","knowledge_base");
            config.put("embedding_model",environment.getProperty("embedding.api.model","unknown"));
            config.put("embedding_dimension",environment.getProperty("embedding.api.dimension","unknown"));
            config.put("embedding_batch_size",environment.getProperty("embedding.api.batch-size","100"));
            config.put("reranker_enabled",environment.getProperty("reranker.api.enabled",Boolean.class,true));
            config.put("reranker_model",environment.getProperty("reranker.api.model","unspecified: model selected by TEI server"));
            config.put("embedding_endpoint_sha256",LangfuseTracing.hash(environment.getProperty("embedding.api.url")));
            config.put("reranker_endpoint_sha256",LangfuseTracing.hash(environment.getProperty("reranker.api.url")));
            var sourceHashes=new TreeMap<String,String>();
            for(String source:List.of("src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java",
                    "src/main/java/com/yizhaoqi/smartpai/client/EmbeddingClient.java",
                    "src/main/java/com/yizhaoqi/smartpai/client/RerankerClient.java",
                    "src/main/java/com/yizhaoqi/smartpai/observability/LangfuseTracing.java")) {
                sourceHashes.put(source,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(source)))));
            }
            config.put("source_sha256",sourceHashes);
            metadata.put("config",config);
            String configHash=LangfuseTracing.hash(mapper.writeValueAsString(config)); metadata.put("config_sha256",configHash);
            try {
                var user=users.findByUsername(username);
                assertTrue(user.isPresent(),"EVALUATION_USERNAME_NOT_FOUND");
                String dbId=ReflectionTestUtils.invokeMethod(service,"getUserDbId",username);
                assertTrue(user.orElseThrow().getId().toString().equals(dbId),"EVALUATION_USERNAME_RESOLUTION_MISMATCH");
                List<String> tags=ReflectionTestUtils.invokeMethod(service,"getUserEffectiveOrgTags",username);
                assertTrue(new TreeSet<>(orgTags.getUserEffectiveOrgTags(username)).equals(new TreeSet<>(tags)),"PERMISSION_RESOLUTION_FAILED");
                String permissionHash=permissionHash(dbId,tags);
                metadata.put("permission_sha256_before",permissionHash);
                metadata.put("index_before",indexSnapshot());
                Query permission=ReflectionTestUtils.invokeMethod(service,"buildPermissionFilter",dbId,tags);
                preflightLabels(samples,permission,metadata);
                metadata.put("preflight_status","success");
                mapper.writerWithDefaultPrettyPrinter().writeValue(metadataFile.toFile(),metadata);
                var exporter=new RetrievalEvaluationExporter(tracing);
                try(var writer=Files.newBufferedWriter(rows,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                    for(var sample:samples) {
                        String sampleId=sample.path("query_id").asText(),query=sample.path("query").asText();
                        for(String strategy:List.of("bm25","hybrid_rerank")) {
                            var result=exporter.export(run,sampleId,strategy,query,configHash,sample,() -> strategy.equals("bm25")
                                    ? ReflectionTestUtils.<List<SearchResult>>invokeMethod(service,"textOnlySearchWithPermission",query,dbId,tags,10)
                                    : service.searchWithPermission(query,username,10));
                            writer.write(mapper.writeValueAsString(result)); writer.newLine(); writer.flush();
                        }
                    }
                }
                String afterId=ReflectionTestUtils.invokeMethod(service,"getUserDbId",username);
                List<String> afterTags=ReflectionTestUtils.invokeMethod(service,"getUserEffectiveOrgTags",username);
                metadata.put("permission_sha256_after",permissionHash(afterId,afterTags));
                metadata.put("index_after",indexSnapshot());
                metadata.put("permission_unchanged",permissionHash.equals(metadata.get("permission_sha256_after")));
                metadata.put("index_snapshot_unchanged",metadata.get("index_before").equals(metadata.get("index_after")));
                metadata.put("snapshot_limit","UUID/settings/mapping/document count and primary shard max_seq_no are checked before and after retrieval");
                metadata.put("cloud_flush_success",tracing.forceFlush().join(5,TimeUnit.SECONDS).isSuccess());
                assertEquals(true,metadata.get("permission_unchanged"),"EVALUATION_PERMISSIONS_CHANGED");
                assertEquals(true,metadata.get("index_snapshot_unchanged"),"EVALUATION_INDEX_CHANGED");
                metadata.put("export_status","complete");
            } catch(Exception | AssertionError failure) {
                metadata.put("export_status","error"); metadata.put("error","EVALUATION_EXPORT_ERROR");
                throw new IllegalStateException("EVALUATION_EXPORT_ERROR");
            } finally {
                mapper.writerWithDefaultPrettyPrinter().writeValue(metadataFile.toFile(),metadata);
            }
        }

        private void validateDataset(JsonNode samples) {
            assertTrue(samples.isArray() && !samples.isEmpty(),"INVALID_DATASET");
            var ids=new HashSet<String>();
            for(var sample:samples) {
                assertTrue(!sample.path("query_id").asText().isBlank() && ids.add(sample.path("query_id").asText()),"INVALID_SAMPLE_ID");
                assertFalse(sample.path("query").asText().isBlank(),"MISSING_QUERY");
                assertTrue(sample.path("relevant").isArray() && !sample.path("relevant").isEmpty(),"MISSING_LABELS");
                for(var key:sample.path("relevant")) assertTrue(key.asText().matches("[a-fA-F0-9]{32}:[0-9]+"),"INVALID_LABEL_KEY");
            }
        }

        private void preflightLabels(JsonNode samples,Query permission,Map<String,Object> metadata) throws Exception {
            var checked=new HashSet<String>();
            var unavailable=new ArrayList<Map<String,String>>();
            for(var sample:samples) for(var key:sample.path("relevant")) {
                if(!checked.add(key.asText())) continue;
                String[] pair=key.asText().split(":",2);
                var visible=es.count(c -> c.index("knowledge_base").query(q -> q.bool(b -> b
                        .filter(permission).filter(f -> f.term(t -> t.field("fileMd5").value(pair[0])))
                        .filter(f -> f.term(t -> t.field("chunkId").value(Long.parseLong(pair[1]))))))).count();
                if(visible==0) unavailable.add(Map.of("sample_id",sample.path("query_id").asText(),"key",key.asText()));
            }
            metadata.put("missing_or_invisible_labels",unavailable);
            assertTrue(unavailable.isEmpty(),"LABEL_MISSING_OR_INVISIBLE");
        }

        private Map<String,Object> indexSnapshot() throws Exception {
            var index=es.indices().get(g -> g.index("knowledge_base")).get("knowledge_base");
            var snapshot=new LinkedHashMap<String,Object>();
            snapshot.put("index_uuid",index.settings().index().uuid());
            snapshot.put("settings_sha256",LangfuseTracing.hash(index.settings().toString()));
            snapshot.put("mapping_sha256",LangfuseTracing.hash(index.mappings().toString()));
            snapshot.put("document_count",es.count(c -> c.index("knowledge_base")).count());
            var stats=es.indices().stats(s -> s.index("knowledge_base").level(co.elastic.clients.elasticsearch._types.Level.Shards))
                    .indices().get("knowledge_base");
            var sequenceNumbers=new TreeMap<String,Long>();
            stats.shards().forEach((id,copies) -> copies.stream().filter(shard -> shard.routing().primary())
                    .forEach(shard -> sequenceNumbers.put(id,shard.seqNo().maxSeqNo())));
            assertFalse(sequenceNumbers.isEmpty(),"INDEX_SEQUENCE_SNAPSHOT_MISSING");
            snapshot.put("primary_max_seq_no",sequenceNumbers);
            return snapshot;
        }
        private String permissionHash(String id,List<String> tags) throws Exception {
            return LangfuseTracing.hash(mapper.writeValueAsString(Map.of("user_db_id",id,"effective_org_tags",new TreeSet<>(tags))));
        }
        private String required(String key) {
            String value=System.getenv(key); assertTrue(value!=null && !value.isBlank(),"MISSING_ENV_"+key); return value;
        }
    }
}
