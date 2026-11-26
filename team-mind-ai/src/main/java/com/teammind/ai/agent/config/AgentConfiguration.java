package com.teammind.ai.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/** Agent 专用线程池，避免慢工具占用检索线程池并拖垮普通 RAG 请求。 */
@Configuration
public class AgentConfiguration {

    @Bean("agentToolExecutor")
    public ThreadPoolExecutorFactoryBean agentToolExecutor() {
        ThreadPoolExecutorFactoryBean executor = new ThreadPoolExecutorFactoryBean();
        executor.setThreadNamePrefix("agent-tool-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        // 队列满时由调用线程执行，保证工具调用不会因为线程池拒绝而静默丢失。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /** SSE 请求使用虚拟线程，等待模型输出期间不占用平台线程。 */
    @Bean(name = "agentStreamExecutor", destroyMethod = "close")
    public ExecutorService agentStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
