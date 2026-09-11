package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.Alert;

/**
 * 告警服务接口 — 负责运营告警的发布、查询、未读统计与处置
 */
public interface AlertService extends IService<Alert> {

    /** 未读状态值 */
    int STATUS_UNREAD = 0;
    /** 已读状态值 */
    int STATUS_READ = 1;
    /** 已处理状态值 */
    int STATUS_HANDLED = 2;
    /** 已忽略状态值 */
    int STATUS_IGNORED = 3;

    /**
     * 发布一条告警（落库失败不影响主流程）
     *
     * @param level          告警等级 INFO / WARNING / CRITICAL
     * @param source         告警来源 GATEWAY / SECURITY / RATE_LIMIT / SYSTEM
     * @param title          告警标题
     * @param content        告警内容
     * @param relatedAppId   关联应用 ID（可为 null）
     * @param relatedAppName 关联应用名（可为 null）
     * @param relatedIp      关联客户端 IP（可为 null）
     */
    void publish(String level, String source, String title, String content,
                 Long relatedAppId, String relatedAppName, String relatedIp);

    /**
     * 分页查询告警列表
     *
     * @param current 当前页码
     * @param size    每页条数
     * @param level   等级筛选（可为空）
     * @param source  来源筛选（可为空）
     * @param status  状态筛选（可为空，0=未读）
     * @return 告警分页结果
     */
    PageResult<Alert> pageQuery(int current, int size, String level, String source, Integer status);

    /**
     * 统计未读告警数量（status = 0）
     *
     * @return 未读告警数
     */
    long unreadCount();

    /**
     * 标记单条告警为已读
     *
     * @param id 告警 ID
     */
    void markRead(Long id);

    /**
     * 一键全部标记为已读（仅影响未读记录）
     */
    void markAllRead();

    /**
     * 处置告警：更新为已处理/已忽略并写入备注与处理时间
     *
     * @param id     告警 ID
     * @param status 目标状态（2=已处理, 3=已忽略）
     * @param remark 处理备注（可为空）
     */
    void handleAlert(Long id, Integer status, String remark);
}
