package com.gatekeeper.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 概览页「风险提示」分级 VO —— 对应 PRD 概览页风险三档（T06-A）
 *
 * <p>由 {@code GET /api/dashboard/risk} 返回 {@code {high, mid, low}}，按
 * {@code alert.level} 分级，仅统计「未处理告警」（{@code alert.status IN (0,1)}）：
 * <ul>
 *   <li>{@code high} — {@code level = 'CRITICAL'}</li>
 *   <li>{@code mid}  — {@code level = 'WARNING'}</li>
 *   <li>{@code low}  — {@code level = 'INFO'}</li>
 * </ul>
 *
 * <p><strong>注意</strong>：{@code alert.level} 是字符串枚举 INFO/WARNING/CRITICAL，
 * 与实际数据分布一致（前端 {@code utils/enum.js:ALERT_LEVEL} 同口径）；
 * <strong>切勿</strong>使用与真实数据不一致的 {@code alarm_level} 字典的 1/2/3。</p>
 *
 * @author GateKeeper
 * @since T06 (APIM V2)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskVo {

    /** 高风险数量（level=CRITICAL 且未处理） */
    private long high;

    /** 中风险数量（level=WARNING 且未处理） */
    private long mid;

    /** 低风险数量（level=INFO 且未处理） */
    private long low;
}
