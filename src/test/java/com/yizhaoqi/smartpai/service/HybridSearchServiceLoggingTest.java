package com.yizhaoqi.smartpai.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankerClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridSearchServiceLoggingTest {
    @Test void rerankObservationMatchesSuccessDisabledAndSupplierFallback() {
        var exporter=io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
        try(var provider=io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()) {
            var search=new HybridSearchService();
            var reranker=mock(RerankerClient.class);
            ReflectionTestUtils.setField(search,"rerankerClient",reranker);
            var original=List.of(new com.yizhaoqi.smartpai.entity.SearchResult("file",1,SNIPPET,0.5));
            when(reranker.isEnabled()).thenReturn(true);
            when(reranker.rerank(QUERY,List.of(SNIPPET),10)).thenReturn(List.of(new RerankerClient.RerankResult(0,0.9)));
            for(String expected:List.of("success","skipped","fallback")) {
                if("skipped".equals(expected)) when(reranker.isEnabled()).thenReturn(false);
                if("fallback".equals(expected)) {
                    when(reranker.isEnabled()).thenReturn(true);
                    when(reranker.rerank(QUERY,List.of(SNIPPET),10)).thenReturn(null);
                }
                var span=provider.get("test").spanBuilder("tool.knowledge_search").startSpan();
                try(var ignored=span.makeCurrent()) {
                    List<com.yizhaoqi.smartpai.entity.SearchResult> results=ReflectionTestUtils.invokeMethod(search,"applyRerank",QUERY,original,10);
                    assertNotNull(results);
                    assertEquals("success".equals(expected) ? 0.9 : 0.5,results.get(0).getScore());
                } finally { span.end(); }
                var data=exporter.getFinishedSpanItems().get(exporter.getFinishedSpanItems().size()-1);
                assertEquals(expected,data.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.metadata.rerank_status")));
                assertFalse(data.getAttributes().toString().contains(SNIPPET));
            }
        }
    }
    private static final String QUERY = "PRIVATEQUERYFORLOGTEST";
    private static final String SNIPPET = "PRIVATEHITFORLOGTEST";
    private static final String TOKEN = "DEDICATEDTESTSUPPLIERTOKEN";

    @Test void permissionSearchDoesNotLogQueryOrRawHitAtDebugLevel() throws Exception {
        SearchFixture fixture = new SearchFixture();
        EsDocument document = new EsDocument("document-id", "file-id", 3, SNIPPET, new float[0], "test", "7", "public", true);
        SearchResponse<EsDocument> response = SearchResponse.of(builder -> builder.took(1).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.total(total -> total.value(1).relation(TotalHitsRelation.Eq)).maxScore(1.0)
                        .hits(hit -> hit.index("knowledge_base").id("document-id").score(1.0).source(document))));
        when(fixture.embedding.embed(anyList())).thenReturn(List.of());
        when(fixture.elasticsearch.search(any(java.util.function.Function.class), eq(EsDocument.class))).thenReturn(response);
        try (Capture capture = new Capture(HybridSearchService.class)) {
            var result = fixture.search.searchWithPermission(QUERY, "alice", 10);
            assertEquals(SNIPPET, result.get(0).getTextContent());
            assertSafe(capture.text());
            assertTrue(capture.text().contains("file-id"));
        }
    }

    @Test void permissionSearchFailureLogsExceptionTypeWithoutEchoedContents() throws Exception {
        SearchFixture fixture = new SearchFixture();
        when(fixture.embedding.embed(anyList())).thenThrow(new IllegalStateException(QUERY + SNIPPET + TOKEN));
        when(fixture.elasticsearch.search(any(java.util.function.Function.class), eq(EsDocument.class)))
                .thenThrow(new IOException(QUERY + SNIPPET + TOKEN));
        try (Capture capture = new Capture(HybridSearchService.class)) {
            assertTrue(fixture.search.searchWithPermission(QUERY, "alice", 10).isEmpty());
            assertSafe(capture.text());
            assertTrue(capture.text().contains("IllegalStateException"));
            assertTrue(capture.text().contains("IOException"));
        }
    }

    @Test void embeddingParseFailureDoesNotEchoInputInLogs() {
        EmbeddingClient embedding = new EmbeddingClient(malformedSupplier(), new ObjectMapper());
        ReflectionTestUtils.setField(embedding, "modelId", "test-model");
        ReflectionTestUtils.setField(embedding, "batchSize", 1);
        ReflectionTestUtils.setField(embedding, "dimension", 2);
        try (Capture capture = new Capture(EmbeddingClient.class)) {
            assertThrows(RuntimeException.class, () -> embedding.embed(List.of(QUERY)));
            assertSafe(capture.text());
            assertTrue(capture.text().contains("JsonParseException"));
        }
    }

    @Test void rerankParseFailureDoesNotEchoQueryOrCandidateInLogs() {
        RerankerClient reranker = new RerankerClient(malformedSupplier(), new ObjectMapper(), true);
        try (Capture capture = new Capture(RerankerClient.class)) {
            assertNull(reranker.rerank(QUERY, List.of(SNIPPET), 1));
            assertSafe(capture.text());
            assertTrue(capture.text().contains("JsonParseException"));
        }
    }

    private WebClient malformedSupplier() {
        return WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .body(QUERY + " " + SNIPPET + " " + TOKEN).build())).build();
    }
    private void assertSafe(String logs) {
        assertFalse(logs.contains(QUERY), "query must not appear in rendered logs");
        assertFalse(logs.contains(SNIPPET), "raw hit must not appear in rendered logs");
        assertFalse(logs.contains(TOKEN), "supplier test token must not appear in rendered logs");
    }

    private static final class SearchFixture {
        final HybridSearchService search = new HybridSearchService();
        final ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        final EmbeddingClient embedding = mock(EmbeddingClient.class);
        SearchFixture() {
            UserRepository users = mock(UserRepository.class);
            User user = new User(); user.setId(7L); user.setUsername("alice");
            when(users.findByUsername("alice")).thenReturn(Optional.of(user));
            OrgTagCacheService tags = mock(OrgTagCacheService.class);
            when(tags.getUserEffectiveOrgTags("alice")).thenReturn(List.of());
            ReflectionTestUtils.setField(search, "userRepository", users);
            ReflectionTestUtils.setField(search, "orgTagCacheService", tags);
            ReflectionTestUtils.setField(search, "embeddingClient", embedding);
            ReflectionTestUtils.setField(search, "esClient", elasticsearch);
            ReflectionTestUtils.setField(search, "fileUploadRepository", mock(FileUploadRepository.class));
            ReflectionTestUtils.setField(search, "rerankerClient", mock(RerankerClient.class));
        }
    }
    private static final class Capture implements AutoCloseable {
        final Logger logger;
        final Level previous;
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Capture(Class<?> target) {
            logger = (Logger) LoggerFactory.getLogger(target); previous = logger.getLevel();
            appender.start(); logger.addAppender(appender); logger.setLevel(Level.DEBUG);
        }
        String text() {
            return appender.list.stream().map(event -> event.getFormattedMessage() +
                    (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        @Override public void close() { logger.detachAppender(appender); appender.stop(); logger.setLevel(previous); }
    }
}
