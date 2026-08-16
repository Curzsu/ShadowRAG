package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

class ParseServiceIdempotencyTest {

    @Test
    void firstChunkBatchDeletesExistingChunksBeforeSavingReplacementChunks() {
        DocumentVectorRepository repository = mock(DocumentVectorRepository.class);
        ParseService service = new ParseService();
        ReflectionTestUtils.setField(service, "documentVectorRepository", repository);

        ReflectionTestUtils.invokeMethod(service, "saveChildChunks", "abc123", List.of("first", "second"),
                "user", "org", false, 0);

        var order = inOrder(repository);
        order.verify(repository).deleteByFileMd5("abc123");
        order.verify(repository, times(2)).save(any());
    }
}
