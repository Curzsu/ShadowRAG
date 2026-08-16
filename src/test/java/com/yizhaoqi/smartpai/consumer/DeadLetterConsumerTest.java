package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeadLetterConsumerTest {

    @Test
    void marksDeadLetterTaskWithStatusFour() {
        FileUploadRepository repository = mock(FileUploadRepository.class);
        DeadLetterConsumer consumer = new DeadLetterConsumer(repository);

        consumer.processDeadLetterTask(new FileProcessingTask("abc123", "path", "file.txt", "user", "org", false));

        verify(repository).updateParseStatusByFileMd5("abc123", 4);
    }

    @Test
    void swallowsRepositoryFailureToPreventDeadLetterLoop() {
        FileUploadRepository repository = mock(FileUploadRepository.class);
        doThrow(new RuntimeException("database unavailable"))
                .when(repository).updateParseStatusByFileMd5("abc123", 4);
        DeadLetterConsumer consumer = new DeadLetterConsumer(repository);

        assertDoesNotThrow(() -> consumer.processDeadLetterTask(
                new FileProcessingTask("abc123", "path", "file.txt", "user", "org", false)));
    }
}
