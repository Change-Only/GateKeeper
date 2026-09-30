package com.gatekeeper.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

/**
 * 集群定时任务互斥锁 —— 保证同一任务在<b>同一时刻只有一个节点执行</b>。
 *
 * <h3>为什么需要它</h3>
 * <p>{@code @Scheduled} 是<b>进程内</b>调度：集群里 N 个副本各自监听同一个 cron，
 * 每个 tick 都会被执行 N 次。对有副作用的任务（评估告警、发送通知）会造成
 * <b>重复告警 / 重复通知</b>，对维护类任务会造成 N 倍无效负载。</p>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * if (!jobLock.tryLock("alarm-realtime", Duration.ofSeconds(60), false)) {
 *     return;   // 别的节点在跑，本次跳过
 * }
 * try {
 *     doWork();
 * } finally {
 *     jobLock.unlock("alarm-realtime");
 * }
 * }</pre>
 *
 * <p><b>每日恰好一次</b>：用 {@link #daily(String)} 拼日期后缀作为任务名，
 * TTL 设 25 小时，则当天第一个抢到的节点执行后<b>不释放</b>，其余节点当天全部跳过：</p>
 * <pre>{@code
 * String name = DistributedJobLock.daily("grant-expire");   // grant-expire:2026-09-30
 * if (!jobLock.tryLock(name, Duration.ofHours(25), true)) {
 *     return;
 * }
 * doDailyWork();   // 不调 unlock：让键自然过期，实现当日恰好一次
 * }</pre>
 *
 * <h3>Redis 不可用时的策略（{@code failOpenOnError}）</h3>
 * <p>锁本身依赖 Redis，而 Redis 故障时「该不该照常执行」取决于任务语义，故由调用方决定：</p>
 * <ul>
 *   <li><b>fail-open（true）</b>——适合<b>幂等</b>任务（批量 UPDATE / DELETE）：
 *       拿不到锁就照常执行，最坏是 N 个节点重复做同一件幂等的事，结果不变；
 *       若改成跳过，Redis 长期故障会导致日志永不清理这类运维事故。</li>
 *   <li><b>fail-safe（false）</b>——适合<b>有外部副作用</b>的任务（发告警、发通知）：
 *       拿不到锁就跳过本次。宁可少算一轮（下一轮 10 秒后照常），
 *       也不要给运维连发 N 条重复告警。</li>
 * </ul>
 *
 * <p>所有方法<b>绝不向外抛异常</b>：调度线程一旦被异常打断，后续 tick 会静默失效。</p>
 *
 * @author GateKeeper
 * @since 集群化改造
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedJobLock {

    /** Redis key 前缀 */
    static final String KEY_PREFIX = "gk:job:lock:";

    /**
     * 释放锁的 Lua 脚本：仅当值等于自己的 {@link #instanceId} 时才 DEL。
     *
     * <p>「先 GET 比对再 DEL」可以防止这类事故：本节点执行超时导致锁 TTL 已过期、
     * 另一节点已抢到锁，此时本节点才走到 finally —— 若无条件 DEL，就会把
     * <b>别人的锁删掉</b>，造成两个节点同时执行。Lua 保证比对与删除原子。</p>
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then\n" +
            "  return redis.call('DEL', KEYS[1])\n" +
            "else\n" +
            "  return 0\n" +
            "end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 本节点实例标识（进程启动时生成）。
     *
     * <p>带初始值的 final 字段不参与 Lombok 的构造注入，因此无需外部传入。</p>
     */
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * 尝试获取任务锁（非阻塞）。
     *
     * @param jobName       任务名（同时作为锁键的一部分，须全局唯一且稳定）
     * @param ttl           锁的存活时长；应明显大于任务最长执行时间，作为「持锁节点宕机」的兜底回收
     * @param failOpenOnError Redis 异常时是否放行执行（true=放行，见类注释的选型说明）
     * @return true=抢到锁，可以执行；false=已有其他节点在执行，或按 fail-safe 策略跳过
     */
    public boolean tryLock(String jobName, Duration ttl, boolean failOpenOnError) {
        if (jobName == null || jobName.isEmpty()) {
            // 配置错误：宁可放行也不要让任务永久不执行，但要留痕
            log.warn("tryLock 收到空任务名，按 fail-open 放行");
            return true;
        }
        String key = KEY_PREFIX + jobName;
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, instanceId, ttl);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            if (acquired == null) {
                // 管道/事务场景下可能返回 null，按调用方策略处理
                return failOpenOnError;
            }
            log.debug("任务锁被其他节点持有，跳过本次: {}", jobName);
            return false;
        } catch (Exception e) {
            if (failOpenOnError) {
                log.warn("任务锁不可用（Redis 异常），任务 [{}] 按 fail-open 继续执行: {}", jobName, e.getMessage());
                return true;
            }
            log.error("任务锁不可用（Redis 异常），任务 [{}] 按 fail-safe 跳过本次: {}", jobName, e.getMessage());
            return false;
        }
    }

    /**
     * 释放任务锁（仅当锁仍属于本节点时才会真正删除）。
     *
     * <p>失败不抛异常：锁键设有 TTL，即使释放失败也会自动过期，不会造成永久死锁。</p>
     *
     * @param jobName 任务名（与 {@link #tryLock} 传入的必须一致）
     */
    public void unlock(String jobName) {
        if (jobName == null || jobName.isEmpty()) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE_SCRIPT,
                    Collections.singletonList(KEY_PREFIX + jobName), instanceId);
        } catch (Exception e) {
            log.warn("释放任务锁失败（将由 TTL 自动过期兜底）: {} cause={}", jobName, e.getMessage());
        }
    }

    /**
     * 生成「按天唯一」的任务名，配合 {@link #tryLock} 使用可实现<b>当日恰好执行一次</b>。
     *
     * <p>用日期而非时间做键，天然免疫各节点时钟微差导致的重复执行：
     * 即使节点 A 在 02:00:00 执行、节点 B 在 02:00:07 才触发，B 也会因为
     * 同名键已被占用而跳过。</p>
     *
     * @param jobName 任务基础名
     * @return 形如 {@code grant-expire:2026-09-30} 的任务名
     */
    public static String daily(String jobName) {
        return jobName + ":" + LocalDate.now();
    }

    /** 本节点实例标识（供日志/排障定位「是哪一台在执行」） */
    public String getInstanceId() {
        return instanceId;
    }
}
