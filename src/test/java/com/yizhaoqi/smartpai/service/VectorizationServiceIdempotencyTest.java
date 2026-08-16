package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorizationServiceIdempotencyTest {

    @Test
    void vectorizeUsesFileMd5AndChunkIdAsElasticsearchDocumentId() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ElasticsearchService elasticsearchService = mock(ElasticsearchService.class);
        DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
        VectorizationService service = new VectorizationService();

        DocumentVector chunk = new DocumentVector();
        chunk.setChunkId(7);
        chunk.setTextContent("chunk text");
        when(documentVectorRepository.findByFileMd5("abc123")).thenReturn(List.of(chunk));
        when(embeddingClient.embed(List.of("chunk text"))).thenReturn(List.of(new float[]{0.1f}));

        ReflectionTestUtils.setField(service, "embeddingClient", embeddingClient);
        ReflectionTestUtils.setField(service, "elasticsearchService", elasticsearchService);
        ReflectionTestUtils.setField(service, "documentVectorRepository", documentVectorRepository);
        ReflectionTestUtils.setField(service, "embeddingModelName", "bge-m3");

        service.vectorize("abc123", "user", "org", false);

        @SuppressWarnings("unchecked")
        var documentsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(elasticsearchService).bulkIndex(documentsCaptor.capture());
        EsDocument document = (EsDocument) documentsCaptor.getValue().get(0);
        assertEquals("abc123_7", document.getId());
    }
}
