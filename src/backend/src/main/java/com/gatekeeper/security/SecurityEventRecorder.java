package com.gatekeeper.security;

import com.gatekeeper.entity.SecurityEvent;
import com.gatekeeper.mapper.SecurityEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 安全事件记录器 — 各检测器的共享落库组件
 *
 * <p>从 SecurityDetectionService 抽取（原 recordEvent 私有方法），供所有检测器复用。
 * 落库失败仅记录错误日志，绝不影响网关主流程（fail-safe）。</p>
 *
 * <p>检测器产出安全事件后交给本记录器持久化到 security_event 表，实现「检测逻辑」与
 * 「事件持久化」解耦；未来若需切换存储（如消息队列异步投递），只需改本类。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityEventRecorder {

    private final SecurityEventMapper eventMapper;

    /**
     * 写入安全事件（持久化到 security_event 表）
     *
     * @param type        事件类型（如 AUTH_FAIL / PERMISSION_BREACH / ABNORMAL_PARAM）
     * @param desc        事件描述
     * @param appId       关联应用 ID（可为 null）
     * @param appName     关联应用名称（可为 null）
     * @param ip          客户端 IP
     * @param triggerRule 触发的规则描述
     */
    public void record(String type, String desc, Long appId, String appName, String ip, String triggerRule) {
        try {
            SecurityEvent event = new SecurityEvent();
            event.setEventType(type);
            event.setEventDesc(desc);
            event.setAppId(appId);
            event.setAppName(appName);
            event.setClientIp(ip);
            event.setTriggerRule(triggerRule);
            event.setHandleStatus(0); // 0 = 未处理
            event.setOccurredAt(LocalDateTime.now());
            event.setCreatedAt(LocalDateTime.now());
            eventMapper.insert(event);
        } catch (Exception e) {
            // 事件落库失败不影响业务主流程，仅记录错误日志
            log.error("Failed to record security event", e);
        }
    }
}
