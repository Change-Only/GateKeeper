package com.gatekeeper.service;

import com.gatekeeper.dto.InterfaceVisibilityVo;
import com.gatekeeper.entity.SysInterfaceVisibility;
import com.gatekeeper.security.InterfaceViewer;

import java.util.List;
import java.util.Map;

/**
 * 接口信息可见性服务 — T17
 *
 * <p>两个关注点：
 * <ul>
 *   <li>{@link #resolveViewer()} —— 供列表/详情/参数树等「热读路径」调用，
 *       每次请求解析一次当前用户的可见性上下文（开关 + 白名单命中 + 是否超管）；</li>
 *   <li>{@link #list()} / {@link #add} / {@link #update} / {@link #delete} —— 供管理页维护白名单。</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
public interface InterfaceVisibilityService {

    /**
     * 解析当前 HTTP 请求的可见性上下文。
     *
     * <p>非 HTTP 上下文（定时任务、网关线程）取不到 uid，会得到「保护启用 + 未命中白名单」的
     * 结果；网关链路本就<b>不调用</b>本方法（它只需要解密，不需要掩码）。</p>
     *
     * @return 可见性上下文，非 null
     */
    InterfaceViewer resolveViewer();

    /**
     * 按指定 uid 解析可见性上下文（便于单测与显式指定场景）。
     *
     * @param uid 用户 id，可为 null
     * @return 可见性上下文，非 null
     */
    InterfaceViewer resolveViewer(Long uid);

    /** 从当前请求解析登录用户 id；非 HTTP 上下文返回 null */
    Long currentUid();

    /** 查询全部白名单条目（启用在前，并补齐主体名称） */
    List<InterfaceVisibilityVo> list();

    /** 新增一条白名单 */
    void add(SysInterfaceVisibility entry);

    /** 更新（含启用/停用） */
    void update(Long id, SysInterfaceVisibility entry);

    /** 删除 */
    void delete(Long id);

    /**
     * 白名单配置页的候选下拉数据。
     *
     * @return {@code {"users":[{id,label,code}], "roles":[{id,label,code}]}}
     */
    Map<String, Object> subjectOptions();
}
