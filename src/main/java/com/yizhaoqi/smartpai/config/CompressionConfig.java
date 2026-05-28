package com.yizhaoqi.smartpai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class CompressionConfig {

    private static final Logger logger = LoggerFactory.getLogger(CompressionConfig.class);

    @Bean
    public ThreadPoolTaskExecutor compressionExecutor(CompressionProperties props) {
        CompressionProperties.ThreadPoolConfig tp = props.getThreadPool();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(tp.getCoreSize());
        executor.setMaxPoolSize(tp.getMaxSize());
        executor.setQueueCapacity(tp.getQueueCapacity());
        executor.setThreadNamePrefix(tp.getThreadNamePrefix());
        executor.setRejectedExecutionHandler((r, exec) -> {
            throw new java.util.concurrent.RejectedExecutionException("Compression pool full, task rejected");
        });
        executor.initialize();
        return executor;
    }

    @Bean
    public RedisScript<Long> compressScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/compress_and_replace.lua"), Long.class);
    }

    @Bean
    public RedisScript<Long> truncateScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/sync_truncate.lua"), Long.class);
    }
}
