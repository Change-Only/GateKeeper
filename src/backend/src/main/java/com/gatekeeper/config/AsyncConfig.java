package com.gatekeeper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置
 *
 * <p>此前 @Async 使用 Spring 默认执行器，其队列为无界（Integer.MAX_VALUE）。
 * 网关高峰期日志落库任务（每条携带完整请求/响应报文，单条可达 8KB+）会无限堆积，
 * 最终 OOM。本配置改为有界线程池 + {@link ThreadPoolExecutor.CallerRunsPolicy}
 * 背压策略：队列满时由调用线程（网关工作线程）亲自执行任务，
 * 短暂拖慢网关吞吐，换取进程稳定——宁可限流降速，不可 OOM 雪崩。</p>
 *
 * <p>同时开启优雅停机：应用下线时等待存量日志任务落库（最多 30 秒），
 * 避免滚动发布时丢失调用日志。</p>
 *
 * <p>参数均支持环境变量覆盖，无需改代码即可按机器规格调优。</p>
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /** 核心线程数：常驻线程，默认 CPU 核数较充裕的 4 */
    @Value("${gatekeeper.async.core-pool-size:4}")
    private int corePoolSize;

    /** 最大线程数：队列满后扩容上限 */
    @Value("${gatekeeper.async.max-pool-size:8}")
    private int maxPoolSize;

    /** 有界队列容量：日志任务的缓冲上限，超出即触发背压 */
    @Value("${gatekeeper.async.queue-capacity:2000}")
    private int queueCapacity;

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("gatekeeper-async-");
        // 队列满+线程满：调用者线程自己执行，形成天然背压，防止无界堆积 OOM
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等待存量任务完成（最多30秒），滚动发布不丢日志
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
