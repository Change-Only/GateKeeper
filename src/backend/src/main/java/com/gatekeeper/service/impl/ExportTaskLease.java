package com.gatekeeper.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 导出任务执行租约（心跳）—— 让「任务是否真的有人在跑」在集群内可判定。
 *
 * <h3>要解决的问题</h3>
 * <p>导出任务在<b>发起请求的那台节点</b>上异步执行（{@code @Async}），进程重启后
 * 卡在 {@code RUNNING} 的任务永远无人接续，会永久显示「生成中」。因此需要启动时回收孤儿任务。</p>
 *
 * <p>但改造前的回收是「启动时把所有 RUNNING 一律置 FAILED」——这在<b>单节点</b>下成立，
 * 在<b>集群</b>下是严重缺陷：节点 B 重启时会把节点 A <b>正在正常执行</b>的导出任务判死，
 * 用户看到任务失败、也下不到本已生成好的文件。</p>
 *
 * <h3>方案：Redis 租约</h3>
 * <p>执行方在开跑前写下 {@code gk:export:lease:{taskId}}（TTL {@value #LEASE_TTL_SECONDS} 秒），
 * 并在每批导出后续租，结束后删除。回收方据此判断：</p>
 * <ul>
 *   <li>租约<b>存在</b> ⇒ 有节点在跑（哪怕不是我）⇒ <b>不动它</b>；</li>
 *   <li>租约<b>不存在</b> ⇒ 执行者已死（或从未开始）⇒ 可以安全回收。</li>
 * </ul>
 *
 * <p><b>降级策略</b>：租约本身依赖 Redis，而 Redis 恰是网关的既有依赖。因此——</p>
 * <ul>
 *   <li>写租约失败（Redis 挂）：<b>照常执行导出</b>（fail-open，不能让导出功能整体不可用），
 *       只是该任务在本次执行中失去「不被误杀」的保护；</li>
 *   <li>读租约失败（回收时 Redis 挂）：<b>一台都不回收</b>（fail-safe）。
 *       宁可留几个僵尸 RUNNING 任务（用户可手动重建），也绝不误杀正在执行的任务。</li>
 * </ul>
 *
 * @author GateKeeper
 * @since 集群化改造
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportTaskLease {

    /** 租约键前缀 */
    static final String KEY_PREFIX = "gk:export:lease:";

    /** 租约存活秒数：需明显大于「单批导出耗时」，按批续租即可维持 */
    static final long LEASE_TTL_SECONDS = 90L;

    private final StringRedisTemplate redisTemplate;

    /** 本节点实例标识（写进租约值，便于排障时定位「是哪一台在执行」） */
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * 写下租约（覆盖式，用于开跑前初始化与执行中续租）。
     *
     * <p>失败只记 WARN 并返回：导出是用户可见功能，不能因为 Redis 抖动就整体失败。</p>
     *
     * @param taskId 导出任务 ID
     */
    public void hold(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + taskId, instanceId,
                    Duration.ofSeconds(LEASE_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("导出任务 {} 写租约失败（不影响本次导出，但该任务失去防误杀保护）: {}",
                    taskId, e.getMessage());
        }
    }

    /**
     * 续租（语义等同于 {@link #hold}，单独命名以体现调用意图）。
     *
     * @param taskId 导出任务 ID
     */
    public void renew(Long taskId) {
        hold(taskId);
    }

    /**
     * 释放租约（任务正常结束/失败时调用）。
     *
     * @param taskId 导出任务 ID
     */
    public void release(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + taskId);
        } catch (Exception e) {
            log.warn("导出任务 {} 释放租约失败（将由 TTL 自动过期）: {}", taskId, e.getMessage());
        }
    }

    /**
     * 查询租约是否仍被持有。
     *
     * @param taskId 导出任务 ID
     * @return {@code TRUE} 有节点在跑；{@code FALSE} 无人在跑；<b>{@code null} 表示无法判定</b>
     *         （Redis 异常）——调用方必须把 null 当作「不确定」处理，而不是当作「没人在跑」
     */
    public Boolean isHeld(Long taskId) {
        if (taskId == null) {
            return Boolean.FALSE;
        }
        try {
            return redisTemplate.hasKey(KEY_PREFIX + taskId);
        } catch (Exception e) {
            log.warn("查询导出任务 {} 租约失败（判定为「不确定」）: {}", taskId, e.getMessage());
            return null;
        }
    }

    /** 本节点实例标识 */
    public String getInstanceId() {
        return instanceId;
    }
}
