package com.gatekeeper.security;

import cn.hutool.crypto.digest.BCrypt;
import com.gatekeeper.entity.SysUser;
import com.gatekeeper.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 「当前登录用户密码」二次确认校验器（T14）
 *
 * <h3>为什么需要它</h3>
 * <p>凭证的 AppSecret 明文按设计只在 create / rotate 时返回一次。但运维场景里「换个电脑要重新
 * 拿一次密钥」是真实需求，于是 T14 开放了「二次查看密钥」：<b>可以再看，但必须先输入当前登录
 * 账号的密码</b>。本类就是那道闸门。</p>
 *
 * <h3>为什么放在 security 包而不是某个业务 Service</h3>
 * <p>这是<b>认证</b>关注点（「你是不是本账号本人」），与凭证域的 CRUD 无关。放进凭证服务会让
 * 业务服务反向依赖 {@code SysUserMapper}，也顺带把它已有的 2 参构造改成 3 参（会波及既有单测）。
 * 单独一个组件则两边都干净。</p>
 *
 * <h3>🔴 错误码口径（勿改）</h3>
 * <p>本类只返回「错误文案或 null」，<b>不抛异常</b>，由调用方决定 HTTP 码。原因是
 * {@code GlobalExceptionHandler} 会把 {@code GatewayException} 的 code 直接映射成 HTTP 状态码，
 * 而前端 {@code api/index.js} 对 <b>HTTP 401 的响应是「清除本地凭证 + 跳登录页」</b>。
 * 因此「密码输错了」绝不能走 401 —— 否则用户只是打错一次密码就会被踢出登录态。
 * 调用方约定：{@link #ERR_NOT_LOGIN} → 401，其余 → 400。</p>
 *
 * @author GateKeeper
 * @since T14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountPasswordVerifier {

    /** 当前请求没有可用的登录用户（理论上被 JwtAuthInterceptor 拦在前面） */
    public static final String ERR_NOT_LOGIN = "未登录或登录已过期";

    /** 前端没传密码 */
    public static final String ERR_EMPTY_PASSWORD = "请输入当前账号密码";

    /** 账号不存在 / 已停用 / 无口令散列 */
    public static final String ERR_USER_UNAVAILABLE = "账号不存在或已停用";

    /** 密码不匹配 */
    public static final String ERR_WRONG_PASSWORD = "当前账号密码不正确";

    private final SysUserMapper sysUserMapper;

    /**
     * 校验「uid 这个用户」的登录密码。
     *
     * @param uid         当前登录用户 ID（取自请求属性 {@code X-USER-ID}）
     * @param rawPassword 用户本次输入的明文密码
     * @return {@code null} 表示校验通过；否则是应当回显给前端的错误文案
     */
    public String check(Long uid, String rawPassword) {
        if (uid == null) {
            return ERR_NOT_LOGIN;
        }
        if (!StringUtils.hasText(rawPassword)) {
            return ERR_EMPTY_PASSWORD;
        }
        SysUser user = sysUserMapper.selectById(uid);
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !StringUtils.hasText(user.getPassword())) {
            log.warn("二次确认失败：账号不可用 uid={}", uid);
            return ERR_USER_UNAVAILABLE;
        }
        boolean ok;
        try {
            ok = BCrypt.checkpw(rawPassword, user.getPassword());
        } catch (Exception e) {
            // BCrypt 对非法散列会抛异常，按「不通过」处理（FAIL-CLOSED）
            log.warn("BCrypt verify error for uid={}: {}", uid, e.getMessage());
            ok = false;
        }
        if (!ok) {
            log.warn("二次确认失败：密码不正确 uid={}", uid);
            return ERR_WRONG_PASSWORD;
        }
        return null;
    }

    /**
     * 从请求属性取当前登录用户 ID。
     *
     * <p>写入方是 {@code config/JwtAuthInterceptor}（{@code request.setAttribute("X-USER-ID",
     * claims.get("uid"))}）。JWT 数字 claim 由 jjwt 反序列化，可能是 {@code Integer} 也可能是
     * {@code Long}（取决于数值大小），故此处做兼容读取。</p>
     *
     * @return 用户 ID；缺省返回 {@code null}
     */
    public Long currentUid(javax.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object v = request.getAttribute("X-USER-ID");
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("X-USER-ID 无法解析为 Long: {}", v);
            return null;
        }
    }
}
