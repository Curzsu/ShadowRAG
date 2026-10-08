package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankerClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.observability.LangfuseTracing;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class HybridSearchTracingTest {
    static final String PRIVATE="PRIVATE_QUERY_AND_DOCUMENT";
    InMemorySpanExporter exporter;
    SdkTracerProvider provider;
    AnnotationConfigApplicationContext beans;
    LangfuseTracing tracing;
    HybridSearchService search;
    ElasticsearchClient es;
    EmbeddingClient embedding;
    RerankerClient reranker;
    ChatRequestContext request;
    final Map<String,String> activeStepIds=new HashMap<>();

    @BeforeEach void setup() throws Exception {
        exporter=InMemorySpanExporter.create();
        provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        tracing=new LangfuseTracing(provider.get("test"),provider,"development");
        es=mock(ElasticsearchClient.class); embedding=mock(EmbeddingClient.class); reranker=mock(RerankerClient.class);
        var users=mock(UserRepository.class); var user=new User(); user.setId(7L); user.setUsername("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        var tags=mock(OrgTagCacheService.class); when(tags.getUserEffectiveOrgTags("alice")).thenReturn(List.of());
        beans=new AnnotationConfigApplicationContext();
        var factory=beans.getBeanFactory();
        factory.registerSingleton("tracing",tracing);
        factory.registerSingleton("elasticsearch",es);
        factory.registerSingleton("embedding",embedding);
        factory.registerSingleton("reranker",reranker);
        factory.registerSingleton("users",users);
        factory.registerSingleton("tags",tags);
        factory.registerSingleton("userService",mock(UserService.class));
        factory.registerSingleton("files",mock(FileUploadRepository.class));
        beans.registerBean(HybridSearchService.class); beans.refresh();
        search=beans.getBean(HybridSearchService.class);
        when(embedding.embed(anyList())).thenAnswer(call -> {
            activeStepIds.put("embedding",Span.current().getSpanContext().getSpanId());
            return List.of(new float[]{1,2});
        });
        when(es.search(any(java.util.function.Function.class),eq(EsDocument.class))).thenAnswer(call -> {
            activeStepIds.put("retrieval",Span.current().getSpanContext().getSpanId());
            return response(true);
        });
        when(reranker.isEnabled()).thenReturn(true);
        when(reranker.rerank(eq(PRIVATE),anyList(),eq(10))).thenAnswer(call -> {
            activeStepIds.put("rerank",Span.current().getSpanContext().getSpanId());
            return List.of(new RerankerClient.RerankResult(0,0.9));
        });
        request=new ChatRequestContext(new ChatCommand("alice",UUID.randomUUID().toString(),UUID.randomUUID(),PRIVATE));
        request.setTraceContext(tracing.startChat(request.command()));
    }

    @AfterEach void close() { if(request!=null) Span.fromContext(request.traceContext()).end(); beans.close(); provider.close(); }

    @Test void successfulSearchHasThreeTimedChildrenWithToolParentAndNoContent() {
        var results=runSearch();
        assertEquals(0.9,results.get(0).getScore());
        assertEquals(List.of("embedding","retrieval","rerank"),steps().stream().map(SpanData::getName).toList());
        var tool=tool();
        for(var step:steps()) {
            assertEquals(tool.getSpanId(),step.getParentSpanId());
            assertEquals(tool.getTraceId(),step.getTraceId());
            assertEquals(activeStepIds.get(step.getName()),step.getSpanId(),"Supplier must execute inside its step scope");
            assertEquals(request.command().conversationId(),attr(step,"langfuse.session.id"));
            assertEquals(request.command().requestId().toString(),attr(step,"langfuse.observation.metadata.request_id"));
            assertTrue(step.getEndEpochNanos()>=step.getStartEpochNanos());
            assertFalse(step.getAttributes().toString().contains(PRIVATE)); assertTrue(step.getEvents().isEmpty());
        }
        assertEquals("success",attr(tool,"langfuse.observation.metadata.rerank_status"));
    }

    @Test void disabledRerankDoesNotInventAnExecutedStep() {
        when(reranker.isEnabled()).thenReturn(false);
        assertEquals(1,runSearch().size());
        assertEquals(List.of("embedding","retrieval"),steps().stream().map(SpanData::getName).toList());
        assertEquals("skipped",attr(tool(),"langfuse.observation.metadata.rerank_status"));
        verify(reranker,never()).rerank(anyString(),anyList(),anyInt());
    }

    @Test void standaloneSearchDoesNotCreateDisconnectedStages() {
        assertEquals(0.9,search.searchWithPermission(PRIVATE,"alice",10).get(0).getScore());
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }

    @Test void disabledTracingKeepsResultsWithoutExportingInternalSteps() {
        org.springframework.test.util.ReflectionTestUtils.setField(search,"tracing",LangfuseTracing.noop());
        assertEquals(0.9,runSearch().get(0).getScore());
        assertTrue(steps().isEmpty());
        assertEquals("success",attr(tool(),"langfuse.observation.metadata.rerank_status"));
    }

    @Test void failedBm25FallbackMarksToolAndBothRecallAttempts() throws Exception {
        when(es.search(any(java.util.function.Function.class),eq(EsDocument.class))).thenThrow(new IOException(PRIVATE));
        assertTrue(runSearch().isEmpty());
        var attempts=steps().stream().filter(span -> "retrieval".equals(span.getName())).toList();
        assertEquals(2,attempts.size());
        assertTrue(attempts.stream().allMatch(span -> span.getStatus().getStatusCode()==StatusCode.ERROR));
        assertEquals(StatusCode.ERROR,tool().getStatus().getStatusCode());
        assertFalse(exporter.getFinishedSpanItems().toString().contains(PRIVATE));
    }

    @Test void unexpectedRerankErrorClosesChildBeforeExistingBm25Fallback() {
        when(reranker.rerank(anyString(),anyList(),anyInt())).thenThrow(new IllegalStateException(PRIVATE));
        assertEquals(1.0,runSearch().get(0).getScore());
        assertEquals(StatusCode.ERROR,step("rerank").getStatus().getStatusCode());
        assertEquals(2,steps().stream().filter(span -> "retrieval".equals(span.getName())).count());
        assertFalse(exporter.getFinishedSpanItems().toString().contains(PRIVATE));
    }

    @Test void emptyRecallDoesNotInventRerankTime() throws Exception {
        when(es.search(any(java.util.function.Function.class),eq(EsDocument.class))).thenReturn(response(false));
        assertTrue(runSearch().isEmpty());
        assertEquals(List.of("embedding","retrieval"),steps().stream().map(SpanData::getName).toList());
        verify(reranker,never()).rerank(anyString(),anyList(),anyInt());
    }

    @Test void unavailableRerankerEndsFailedChildAndRetainsRrfResults() {
        when(reranker.rerank(anyString(),anyList(),anyInt())).thenReturn(null);
        assertEquals(2.0/61,runSearch().get(0).getScore());
        assertEquals(StatusCode.ERROR,step("rerank").getStatus().getStatusCode());
        assertEquals("fallback",attr(tool(),"langfuse.observation.metadata.rerank_status"));
    }

    @Test void embeddingFailureRecordsBm25FallbackWithoutLeakingException() {
        when(embedding.embed(anyList())).thenThrow(new IllegalStateException(PRIVATE));
        assertEquals(1.0,runSearch().get(0).getScore());
        assertEquals(StatusCode.ERROR,step("embedding").getStatus().getStatusCode());
        assertEquals("bm25_fallback",attr(step("retrieval"),"langfuse.observation.metadata.retrieval_strategy"));
        assertFalse(exporter.getFinishedSpanItems().toString().contains(PRIVATE));
    }

    @Test void failedHybridRecallAndBm25RetryAreDistinctEndedChildren() throws Exception {
        when(es.search(any(java.util.function.Function.class),eq(EsDocument.class)))
                .thenThrow(new IOException(PRIVATE)).thenReturn(response(true));
        assertEquals(1.0,runSearch().get(0).getScore());
        var attempts=steps().stream().filter(span -> "retrieval".equals(span.getName())).toList();
        assertEquals(2,attempts.size());
        assertEquals(StatusCode.ERROR,attempts.get(0).getStatus().getStatusCode());
        assertEquals("bm25_fallback",attr(attempts.get(1),"langfuse.observation.metadata.retrieval_strategy"));
        assertEquals(attempts.get(0).getParentSpanId(),attempts.get(1).getParentSpanId());
        assertFalse(exporter.getFinishedSpanItems().toString().contains(PRIVATE));
    }

    private List<SearchResult> runSearch() {
        try(var rootScope=request.traceContext().makeCurrent()) {
            var tool=tracing.startSearch(request,"call-test");
            try(var scope=tool.makeCurrent()) {
                var parent=Span.current().getSpanContext();
                var results=search.searchWithPermission(PRIVATE,"alice",10);
                assertEquals(parent,Span.current().getSpanContext(),"Search must restore caller scope");
                return results;
            } finally { tool.end(); }
        }
    }
    private List<SpanData> steps() { return exporter.getFinishedSpanItems().stream().filter(span -> !"tool.knowledge_search".equals(span.getName())).toList(); }
    private SpanData step(String name) {
        var match=steps().stream().filter(span -> name.equals(span.getName())).findFirst().orElse(null);
        assertNotNull(match,"Missing search step: "+name); return match;
    }
    private SpanData tool() { return exporter.getFinishedSpanItems().stream().filter(span -> "tool.knowledge_search".equals(span.getName())).findFirst().orElseThrow(); }
    private String attr(SpanData span,String key) { return span.getAttributes().get(AttributeKey.stringKey(key)); }
    private SearchResponse<EsDocument> response(boolean hasHit) {
        var doc=new EsDocument("doc","file",3,PRIVATE,new float[0],"test","7","public",true);
        return SearchResponse.of(builder -> builder.took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> {
                    h.total(t -> t.value(hasHit?1:0).relation(TotalHitsRelation.Eq)).maxScore(1.0);
                    if(hasHit) h.hits(hit -> hit.index("knowledge_base").id("doc").score(1.0).source(doc));
                    else h.hits(List.of());
                    return h;
                }));
    }
}
