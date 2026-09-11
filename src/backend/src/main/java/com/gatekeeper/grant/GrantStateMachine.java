package com.gatekeeper.grant;

import com.gatekeeper.exception.GatewayException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 授权状态机 — 编码 app_api_grant 的状态流转规则（架构 D3）
 *
 * <p>状态字典：
 * <ul>
 *   <li>0=待审批(PENDING)</li>
 *   <li>1=已生效(ACTIVE)</li>
 *   <li>2=已过期(EXPIRED)</li>
 *   <li>3=已撤销(REVOKED)</li>
 *   <li>4=已驳回(REJECTED)</li>
 * </ul></p>
 *
 * <p>合法流转：
 * <ul>
 *   <li>0 → 1(approve) / 4(reject)</li>
 *   <li>1 → 1(renew 延期) / 2(自动过期) / 3(revoke)</li>
 *   <li>2 → 1(renew 复活)</li>
 *   <li>3 → (终态，无出边)</li>
 *   <li>4 → (终态，无出边)</li>
 * </ul></p>
 */
public final class GrantStateMachine {

    public static final int PENDING = 0;
    public static final int ACTIVE = 1;
    public static final int EXPIRED = 2;
    public static final int REVOKED = 3;
    public static final int REJECTED = 4;

    /** 状态 -> 允许到达的目标状态集合 */
    private static final Map<Integer, int[]> ALLOWED = new HashMap<>();

    static {
        ALLOWED.put(PENDING, new int[]{ACTIVE, REJECTED});
        ALLOWED.put(ACTIVE, new int[]{ACTIVE, EXPIRED, REVOKED});
        ALLOWED.put(EXPIRED, new int[]{ACTIVE});
        ALLOWED.put(REVOKED, new int[]{});
        ALLOWED.put(REJECTED, new int[]{});
    }

    private GrantStateMachine() {
    }

    /**
     * 校验状态流转是否合法，非法时抛出 {@link GatewayException#badRequest}。
     *
     * @param from 当前状态
     * @param to   目标状态
     */
    public static void validate(int from, int to) {
        int[] allowed = ALLOWED.get(from);
        if (allowed == null) {
            throw GatewayException.badRequest("未知的授权状态: " + from);
        }
        for (int a : allowed) {
            if (a == to) {
                return;
            }
        }
        throw GatewayException.badRequest(
                String.format("非法的授权状态流转: %d -> %d", from, to));
    }

    /**
     * 纯函数：判断 from→to 是否合法（不抛异常）。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true=合法
     */
    public static boolean isAllowed(int from, int to) {
        int[] allowed = ALLOWED.get(from);
        if (allowed == null) {
            return false;
        }
        return Arrays.stream(allowed).anyMatch(a -> a == to);
    }
}
