package com.gatekeeper.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 配置缓存跨节点失效 —— Redis Pub/Sub 订阅端。
 *
 * <h3>要解决的问题</h3>
 * <p>{@link SysConfigAccessor} 的缓存是<b>进程内</b>的（网关热路径不能每请求一次 DB）。
 * 但集群里每个副本各持一份：节点 A 上改了配置，A 通过 {@code evictAll()} 清掉自己的缓存，
 * B/C/D 仍在用旧值，最长 {@code 60s}（TTL）后才自愈。</p>
 *
 * <p>对本项目来说这不是「差一点点」的问题：{@code sys_config} 里装着
 * {@code gateway.auth.enabled}（验签总开关）、{@code gateway.ratelimit.enabled}（限流总开关）
 * 这类<b>应急止损</b>用的配置。运维在管理后台关掉开关后，期望的是「立刻全站生效」，
 * 而不是「碰运气看这次请求落到哪台机器」。</p>
 *
 * <h3>方案选择</h3>
 * <p>用 Pub/Sub 而不是「让每个请求都去 Redis 比对版本号」：后者会给网关热路径
 * 凭空增加一次网络往返，而配置变更本身是低频事件。Pub/Sub 的代价只有一条常驻订阅连接。</p>
 *
 * <p><b>降级</b>：Redis 不可用时，发布端 {@code broadcastEvict()} 记 WARN 后返回，
 * 本地失效照常生效；其他节点由 60s TTL 兜底。即「Redis 挂了 → 退化成单节点语义」，
 * 不会因为广播通道故障导致配置完全无法保存。</p>
 *
 * @author GateKeeper
 * @since 集群化改造
 */
@Slf4j
@Configuration
public class ConfigCacheInvalidationConfig {

    /**
     * 注册配置失效广播的订阅容器。
     *
     * <p>容器是 {@code SmartLifecycle}，随应用上下文启动；Redis 暂时不可用时它只在后台重连，
     * <b>不会阻断应用启动</b>（与网关各防护组件「新增依赖不得把既有链路打死」的口径一致）。</p>
     *
     * @param connectionFactory Redis 连接工厂（Spring Boot 自动配置）
     * @param accessor          系统参数读取器（收到广播后清空其本地缓存）
     * @return 监听容器
     */
    @Bean
    public RedisMessageListenerContainer configEvictListenerContainer(
            RedisConnectionFactory connectionFactory, SysConfigAccessor accessor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 消息内容不参与判断：任何一条失效广播都意味着「本地全清」（表小、重建成本可忽略）
        container.addMessageListener(
                (message, pattern) -> accessor.clearLocalCache(),
                new ChannelTopic(SysConfigAccessor.EVICT_CHANNEL));
        log.info("配置缓存跨节点失效广播已启用：channel={}", SysConfigAccessor.EVICT_CHANNEL);
        return container;
    }
}
