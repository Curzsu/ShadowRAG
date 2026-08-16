package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Consumes permanently failed file-processing tasks and records their terminal state.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private static final int PARSE_STATUS_DEAD_LETTER = 4;

    private final FileUploadRepository fileUploadRepository;

    @KafkaListener(topics = "${spring.kafka.topic.dlt}")
    public void processDeadLetterTask(FileProcessingTask task) {
        try {
            log.error("收到文件处理死信消息: {}", task);
            fileUploadRepository.updateParseStatusByFileMd5(task.getFileMd5(), PARSE_STATUS_DEAD_LETTER);
            log.info("文件已标记为死信: fileMd5={}, parseStatus={}",
                    task.getFileMd5(), PARSE_STATUS_DEAD_LETTER);
        } catch (Exception e) {
            // DLT listener must never throw: the common recoverer otherwise republishes to this topic forever.
            log.error("处理文件死信消息时发生异常，已吞掉以避免循环: task={}", task, e);
        }
    }
}
