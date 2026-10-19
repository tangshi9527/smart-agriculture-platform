package com.smartagri.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 专用线程池（bulkhead / 舱壁隔离）。
 *
 * <p>为什么不用全局 {@code agri-async-} 池：大模型单次调用要几百毫秒到数秒，而全局池
 * 同时承担告警判定、InfluxDB 落库、WebSocket 推送。一旦 AI 慢，会把这三条主链路一起拖住。
 * 因此单独开一个小池，并且被拒绝时<strong>不</strong>走 CallerRunsPolicy——调用方可能是
 * Netty 的 IO 线程，绝不能让它去跑几秒的 LLM 请求。队列满就直接拒绝，由上层推一条"繁忙"提示。</p>
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    public static final String LLM_EXECUTOR = "llmExecutor";

    @Bean(LLM_EXECUTOR)
    public ThreadPoolTaskExecutor llmExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(64);
        ex.setKeepAliveSeconds(60);
        ex.setThreadNamePrefix("ai-llm-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        log.info("AI 线程池已就绪: core=2 max=4 queue=64 拒绝策略=Abort（保护接入层）");
        return ex;
    }
}
