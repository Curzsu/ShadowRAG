package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.controller.ChatController;
import com.yizhaoqi.smartpai.service.chat.ChatRequestRegistry;
import com.yizhaoqi.smartpai.service.chat.ChatStreamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

@Configuration
public class ChatStreamingConfig {
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor chatStreamingExecutor(ChatStreamingProperties properties) {
        properties.validate();
        return new ThreadPoolExecutor(properties.getWorkerThreads(), properties.getWorkerThreads(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.getWorkerQueueCapacity()),
                namedThreads("chat-stream-worker-"), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "dispose")
    public Scheduler chatStreamingScheduler(ThreadPoolExecutor chatStreamingExecutor) {
        return Schedulers.fromExecutorService(chatStreamingExecutor);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatStreamingTimers(ChatRequestRegistry registry,
                                                       ChatStreamingProperties properties) {
        ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(2, namedThreads("chat-stream-timer-"));
        timers.setRemoveOnCancelPolicy(true);
        timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        timers.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        timers.scheduleWithFixedDelay(registry::purgeExpired, properties.getTerminalRetentionMs(),
                properties.getTerminalRetentionMs(), TimeUnit.MILLISECONDS);
        return timers;
    }

    @Bean
    public LongFunction<SseEmitter> chatSseEmitterFactory() {
        return TransportSseEmitter::new;
    }

    @Bean
    public WebMvcConfigurer chatStreamingWebMvc(ChatStreamService service) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new AsyncHandlerInterceptor() {
                    @Override
                    public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                                               HttpServletResponse response, Object handler) {
                        Object emitter = request.getAttribute(ChatController.EMITTER_REQUEST_ATTRIBUTE);
                        if (emitter instanceof SseEmitter stream) service.transportReady(stream);
                    }
                }).addPathPatterns("/api/v1/chat/stream");
            }
        };
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Production emitters wait for MVC readiness, avoiding its unbounded early-send buffer. */
    public static class TransportSseEmitter extends SseEmitter {
        public TransportSseEmitter(long timeout) { super(timeout); }
    }
}
