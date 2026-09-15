package com.gatekeeper.security;

import cn.hutool.crypto.digest.BCrypt;
import com.gatekeeper.entity.SysUser;
import com.gatekeeper.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * AccountPasswordVerifier 单测 — T14「二次查看密钥」的密码闸门
 *
 * <p>用<b>真 BCrypt</b>（不是打桩）覆盖比对逻辑：这是整个 T14 唯一的身份校验点，
 * 打桩会把「比对逻辑本身写错」这类问题全部掩盖掉。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccountPasswordVerifier 当前账号密码二次确认")
class AccountPasswordVerifierTest {

    private static final String RAW_PASSWORD = "admin123";
    private static final String PASSWORD_HASH = BCrypt.hashpw(RAW_PASSWORD, BCrypt.gensalt());

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private HttpServletRequest request;

    private AccountPasswordVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new AccountPasswordVerifier(sysUserMapper);
    }

    private SysUser enabledUser() {
        SysUser u = new SysUser();
        u.setId(1L);
        u.setUsername("admin");
        u.setPassword(PASSWORD_HASH);
        u.setStatus(1);
        return u;
    }

    // =================================================================
    // check()
    // =================================================================

    @Test
    @DisplayName("密码正确 → 返回 null（校验通过）")
    void check_ok() {
        when(sysUserMapper.selectById(1L)).thenReturn(enabledUser());
        assertNull(verifier.check(1L, RAW_PASSWORD));
    }

    @Test
    @DisplayName("密码错误 → ERR_WRONG_PASSWORD")
    void check_wrongPassword() {
        when(sysUserMapper.selectById(1L)).thenReturn(enabledUser());
        assertEquals(AccountPasswordVerifier.ERR_WRONG_PASSWORD, verifier.check(1L, "not-my-password"));
    }

    @Test
    @DisplayName("未传密码 / 空白密码 → ERR_EMPTY_PASSWORD（且不查库）")
    void check_emptyPassword() {
        assertEquals(AccountPasswordVerifier.ERR_EMPTY_PASSWORD, verifier.check(1L, null));
        assertEquals(AccountPasswordVerifier.ERR_EMPTY_PASSWORD, verifier.check(1L, ""));
        assertEquals(AccountPasswordVerifier.ERR_EMPTY_PASSWORD, verifier.check(1L, "   "));
    }

    @Test
    @DisplayName("uid 为空 → ERR_NOT_LOGIN（调用方映射成 401）")
    void check_noUid() {
        assertEquals(AccountPasswordVerifier.ERR_NOT_LOGIN, verifier.check(null, RAW_PASSWORD));
    }

    @Test
    @DisplayName("账号不存在 / 已停用 → ERR_USER_UNAVAILABLE，且不受密码对错影响")
    void check_userUnavailable() {
        when(sysUserMapper.selectById(1L)).thenReturn(null);
        assertEquals(AccountPasswordVerifier.ERR_USER_UNAVAILABLE, verifier.check(1L, RAW_PASSWORD));

        SysUser disabled = enabledUser();
        disabled.setStatus(0);
        when(sysUserMapper.selectById(1L)).thenReturn(disabled);
        assertEquals(AccountPasswordVerifier.ERR_USER_UNAVAILABLE, verifier.check(1L, RAW_PASSWORD));
    }

    @Test
    @DisplayName("🔴 库中散列非法（历史脏数据）→ 按不通过处理，绝不抛异常（FAIL-CLOSED）")
    void check_brokenHashFailsClosed() {
        SysUser broken = enabledUser();
        broken.setPassword("NOT_A_BCRYPT_HASH");
        when(sysUserMapper.selectById(1L)).thenReturn(broken);
        // 若这里抛异常，会被 GlobalExceptionHandler 兜成 500「系统繁忙」，用户无从判断
        assertEquals(AccountPasswordVerifier.ERR_WRONG_PASSWORD, verifier.check(1L, RAW_PASSWORD));
    }

    @Test
    @DisplayName("库中密码为空 → ERR_USER_UNAVAILABLE")
    void check_nullHash() {
        SysUser noPwd = enabledUser();
        noPwd.setPassword(null);
        when(sysUserMapper.selectById(1L)).thenReturn(noPwd);
        assertEquals(AccountPasswordVerifier.ERR_USER_UNAVAILABLE, verifier.check(1L, RAW_PASSWORD));
    }

    // =================================================================
    // currentUid()：请求属性 X-USER-ID 的类型兼容
    // =================================================================

    @Test
    @DisplayName("currentUid 兼容 Long / Integer / String 三种写入形态")
    void currentUid_typeCompatible() {
        when(request.getAttribute("X-USER-ID")).thenReturn(7L);
        assertEquals(Long.valueOf(7L), verifier.currentUid(request));

        // jjwt 对小整数常反序列化成 Integer
        when(request.getAttribute("X-USER-ID")).thenReturn(7);
        assertEquals(Long.valueOf(7L), verifier.currentUid(request));

        when(request.getAttribute("X-USER-ID")).thenReturn(" 9 ");
        assertEquals(Long.valueOf(9L), verifier.currentUid(request));
    }

    @Test
    @DisplayName("currentUid：缺属性 / 非法字符串 / request 为 null → 一律 null")
    void currentUid_nullSafe() {
        when(request.getAttribute("X-USER-ID")).thenReturn(null);
        assertNull(verifier.currentUid(request));

        when(request.getAttribute("X-USER-ID")).thenReturn("abc");
        assertNull(verifier.currentUid(request));

        assertNull(verifier.currentUid(null));
    }
}
