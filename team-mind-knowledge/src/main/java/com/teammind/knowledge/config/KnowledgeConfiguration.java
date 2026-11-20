package com.teammind.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class KnowledgeConfiguration {
    @Bean("retrievalTaskExecutor")
    public ThreadPoolExecutorFactoryBean retrievalTaskExecutor() {
        ThreadPoolExecutorFactoryBean executor = new ThreadPoolExecutorFactoryBean();
        executor.setThreadNamePrefix("retrieval-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean("documentTaskExecutor")
    public Executor documentTaskExecutor() {
        // 文档处理包含网络调用，使用有界队列避免上传高峰无限堆积任务。
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("document-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        // 队列已满时由提交线程执行，避免事务提交后的异步任务被静默丢弃并永久停留在 PENDING。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
