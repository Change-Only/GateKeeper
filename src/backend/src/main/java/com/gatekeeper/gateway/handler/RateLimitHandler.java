package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.AppRateLimit;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.AppRateLimitMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * Step 4: 频率限制 (令牌桶 + Redis)
 *
 * <p>责任链第 4 环：基于 Redis 实现三维限流——QPS（令牌桶）、并发数、日调用量，
 * 任一维度超限即返回 429。并发计数在请求结束后由 GatewayCore 回收。</p>
 *
 * <p>QPS 采用 Redis Lua 令牌桶算法（容量 = 速率 = qpsLimit），
 * 相比固定窗口计数器能平滑突发、消除窗口临界突刺。</p>
 */
@Slf4j
@Component
@Order(4)
@RequiredArgsConstructor
public class RateLimitHandler implements GatewayHandler {

    /** 令牌桶 Lua 脚本：原子地补充令牌并扣减，返回 1=放行 / 0=拒绝 */
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>(
            "local key = KEYS[1]\n" +
            "local capacity = tonumber(ARGV[1])\n" +
            "local rate = tonumber(ARGV[2])\n" +
            "local now = tonumber(ARGV[3])\n" +
            "local requested = tonumber(ARGV[4])\n" +
            "local data = redis.call('HMGET', key, 'tokens', 'ts')\n" +
            "local tokens = tonumber(data[1])\n" +
            "local last_ts = tonumber(data[2])\n" +
            "if tokens == nil then tokens = capacity; last_ts = now end\n" +
            "local delta = math.max(0, now - last_ts)\n" +
            "local filled = delta * rate / 1000\n" +
            "tokens = math.min(capacity, tokens + filled)\n" +
            "last_ts = now\n" +
            "if tokens >= requested then\n" +
            "  tokens = tokens - requested\n" +
            "  redis.call('HMSET', key, 'tokens', tokens, 'ts', last_ts)\n" +
            "  redis.call('PEXPIRE', key, math.ceil(capacity / rate * 1000) + 1000)\n" +
            "  return 1\n" +
            "else\n" +
            "  redis.call('HMSET', key, 'tokens', tokens, 'ts', last_ts)\n" +
            "  redis.call('PEXPIRE', key, math.ceil(capacity / rate * 1000) + 1000)\n" +
            "  return 0\n" +
            "end",
            Long.class);

    private final AppRateLimitMapper rateLimitMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 执行频率限制检查
     *
     * @param ctx 网关上下文（需已由 AppAuthHandler 写入 appId）
     */
    @Override
    public void handle(GatewayContext ctx) {
        Long appId = ctx.getAppId();
        if (appId == null) return;

        // 查询应用的限流配置（app_rate_limit 表）
        AppRateLimit config = rateLimitMapper.selectOne(
                new QueryWrapper<AppRateLimit>().eq("app_id", appId)
        );
        if (config == null) return; // 无配置，不限流

        // QPS 限制：Redis 令牌桶（容量 = 速率 = qpsLimit，允许 1 秒内突发）
        if (config.getQpsLimit() > 0) {
            if (!tryAcquireToken(appId, config.getQpsLimit())) {
                ctx.setRateLimited(true);
                throw GatewayException.tooManyRequests("请求频率超限 (QPS limit: " + config.getQpsLimit() + "/s)");
            }
        }

        // 并发限制：请求处理期间计数 +1，GatewayCore finally 中 -1
        if (config.getConcurrentLimit() > 0) {
            try {
                String concurrentKey = "rate_limit:concurrent:" + appId;
                Long current = redisTemplate.opsForValue().increment(concurrentKey);
                if (current != null && current > config.getConcurrentLimit()) {
                    // 超限则立即回滚本次 +1，避免计数泄漏
                    redisTemplate.opsForValue().decrement(concurrentKey);
                    ctx.setRateLimited(true);
                    throw GatewayException.tooManyRequests("并发数超限 (max: " + config.getConcurrentLimit() + ")");
                }
            } catch (GatewayException e) {
                throw e; // 业务限流拒绝原样抛出
            } catch (Exception e) {
                // Redis 故障降级放行：并发限制暂时失效，业务链路不中断
                log.error("Concurrent limit degraded (fail-open) for appId={}: {}", appId, e.getMessage());
            }
            // 并发数在ForwardHandler结束后-1（通过afterCompletion）
        }

        // 日调用上限：按日期维度计数，当日 23:59:59 过期
        if (config.getDailyLimit() > 0) {
            try {
                String dailyKey = "rate_limit:daily:" + appId + ":" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
                if (dailyCount == 1) {
                    // TTL到当天23:59:59
                    redisTemplate.expire(dailyKey, 1, java.util.concurrent.TimeUnit.DAYS);
                }
                if (dailyCount != null && dailyCount > config.getDailyLimit()) {
                    ctx.setRateLimited(true);
                    throw GatewayException.tooManyRequests("日调用量超限 (daily limit: " + config.getDailyLimit() + ")");
                }
            } catch (GatewayException e) {
                throw e; // 业务限流拒绝原样抛出
            } catch (Exception e) {
                // Redis 故障降级放行：日调用限制暂时失效，业务链路不中断
                log.error("Daily limit degraded (fail-open) for appId={}: {}", appId, e.getMessage());
            }
        }

        log.debug("Rate limit passed for appId={}", appId);
    }

    /**
     * 尝试从令牌桶获取一个令牌（原子操作，Redis Lua 保证并发安全）
     *
     * @param appId    应用 ID
     * @param qpsLimit QPS 上限（同时作为桶容量与每秒填充速率）
     * @return true=放行；false=令牌不足，应拒绝
     */
    private boolean tryAcquireToken(Long appId, int qpsLimit) {
        try {
            String key = "rate_limit:bucket:" + appId;
            Long result = redisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(qpsLimit),   // capacity：桶容量
                    String.valueOf(qpsLimit),   // rate：每秒填充令牌数
                    String.valueOf(System.currentTimeMillis()), // now（毫秒）
                    "1"                          // requested：本次请求 1 个令牌
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            // Redis 异常时降级放行，避免限流组件故障导致全链路不可用
            log.error("Token bucket failed for appId={}: {}", appId, e.getMessage());
            return true;
        }
    }
}
