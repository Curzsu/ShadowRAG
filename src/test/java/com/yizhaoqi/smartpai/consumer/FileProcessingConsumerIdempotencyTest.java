package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileProcessingConsumerIdempotencyTest {

    @Test
    void completedTaskIsAcknowledgedWithoutParsingOrVectorizingAgain() {
        ParseService parseService = mock(ParseService.class);
        VectorizationService vectorizationService = mock(VectorizationService.class);
        FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
        FileUpload upload = new FileUpload();
        upload.setParseStatus(2);
        when(fileUploadRepository.findByFileMd5("abc123")).thenReturn(Optional.of(upload));
        FileProcessingConsumer consumer = new FileProcessingConsumer(parseService, vectorizationService, fileUploadRepository);

        assertDoesNotThrow(() -> consumer.processTask(
                new FileProcessingTask("abc123", "not-a-real-file", "file.txt", "user", "org", false)));

        verifyNoInteractions(parseService, vectorizationService);
    }
}
