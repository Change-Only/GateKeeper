package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.dto.AppCredentialDto;
import com.gatekeeper.dto.CredentialRotateRequest;
import com.gatekeeper.entity.AppCredential;
import com.gatekeeper.entity.Env;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.AppCredentialMapper;
import com.gatekeeper.service.EnvService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AppCredentialServiceImpl 单元测试 — T03a 凭证服务（最复杂）
 *
 * <p>覆盖 8 个接口 + 灰度轮换完整流程：
 * <ul>
 *   <li>list / get / create</li>
 *   <li>rotate：已有轮换中 → 拒绝；正常轮换 → 主密钥改 7d 过期 + 新建轮换中</li>
 *   <li>completeRotate：轮换中→主 + 旧→已吊销</li>
 *   <li>revoke：status → 3（已吊销）</li>
 *   <li>update：仅 alias / expireTime</li>
 * </ul></p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AppCredentialService 8 接口 + 灰度轮换")
class AppCredentialServiceTest {

    @Mock
    private AppCredentialMapper appCredentialMapper;

    @Mock
    private EnvService envService;

    @Mock
    private CryptoService cryptoService;

    private AppCredentialServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AppCredentialServiceImpl(envService, cryptoService);
        // 注入 baseMapper（CryptoService 与 aesDbKey）
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(service, appCredentialMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ReflectionTestUtils.setField(service, "aesDbKey", "TEST_32_BYTES_LONG_KEY_FOR_AES_256");
        when(cryptoService.encrypt(any(), any(), any(), any(), any(), any()))
                .thenReturn("AES_CIPHERTEXT_BASE64");
    }

    // =================================================================
    // list / get
    // =================================================================

    @Test
    @DisplayName("list 不返回明文 secret")
    void list_masksSecret() {
        AppCredential c = sample(1L, "ak_prod_aaaabbbbcccc1111", 1, 0);
        when(appCredentialMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(c));

        List<AppCredentialDto> result = service.list(1L, "prod", null);
        assertEquals(1, result.size());
        AppCredentialDto dto = result.get(0);
        assertNotNull(dto.getSecretMask());
        // 列表绝不允许返回明文
        assertNull(dto.getAppSecret());
    }

    @Test
    @DisplayName("get 不返回明文 secret")
    void get_masksSecret() {
        AppCredential c = sample(2L, "ak_prod_dddddddddddddddd", 1, 0);
        when(appCredentialMapper.selectById(2L)).thenReturn(c);
        AppCredentialDto dto = service.get(2L);
        assertNull(dto.getAppSecret());
        assertEquals("ak_prod_dddddddddddddddd", dto.getAppKey());
    }

    @Test
    @DisplayName("get 不存在时 404")
    void get_notFound() {
        when(appCredentialMapper.selectById(99L)).thenReturn(null);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.get(99L));
        assertEquals(404, ex.getCode());
    }

    // =================================================================
    // create
    // =================================================================

    @Test
    @DisplayName("create 成功：返回明文 secret（仅一次）")
    void create_returnsPlaintextOnce() {
        Env env = new Env();
        env.setId(4L);
        env.setEnvCode("prod");
        when(envService.getByEnvCode("prod")).thenReturn(env);

        AppCredentialDto dto = new AppCredentialDto();
        dto.setAppId(1L);
        dto.setEnvCode("prod");
        dto.setAlias("主密钥");

        AppCredentialDto result = service.create(dto);
        assertNotNull(result.getAppSecret(), "create 响应应含明文 secret（仅此一次）");
        assertNotNull(result.getSecretMask());
        assertTrue(result.getAppKey().startsWith("ak_prod_"));
        verify(appCredentialMapper, times(1)).insert(any(AppCredential.class));
    }

    @Test
    @DisplayName("create 环境不存在抛 400")
    void create_envNotFound() {
        when(envService.getByEnvCode("uat")).thenReturn(null);

        AppCredentialDto dto = new AppCredentialDto();
        dto.setAppId(1L);
        dto.setEnvCode("uat");
        dto.setAlias("X");

        assertThrows(GatewayException.class, () -> service.create(dto));
    }

    // =================================================================
    // rotate
    // =================================================================

    @Test
    @DisplayName("rotate 已有 rotateFlag=1 时拒绝")
    void rotate_rejectsWhenAlreadyRotating() {
        AppCredential rotating = sample(99L, "ak_prod_9999999999999999", 1, 1);
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(rotating);

        CredentialRotateRequest req = new CredentialRotateRequest();
        req.setAppId(1L);
        req.setEnvCode("prod");

        GatewayException ex = assertThrows(GatewayException.class, () -> service.rotate(req));
        assertTrue(ex.getMessage().contains("轮换中"));
    }

    @Test
    @DisplayName("rotate 没有主密钥时拒绝")
    void rotate_noPrimaryFails() {
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(null);

        CredentialRotateRequest req = new CredentialRotateRequest();
        req.setAppId(1L);
        req.setEnvCode("prod");

        GatewayException ex = assertThrows(GatewayException.class, () -> service.rotate(req));
        assertTrue(ex.getMessage().contains("主密钥"));
    }

    @Test
    @DisplayName("rotate 正常流程：原主密钥 7d 过期 + 新建轮换中凭证")
    void rotate_successFlow() {
        AppCredential primary = sample(1L, "ak_prod_1111111111111111", 1, 0);
        primary.setAlias("主密钥");
        // 第一次 selectOne 查询"是否有轮换中"返回 null（无）
        // 第二次 selectOne 查询主密钥
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(null)
                .thenReturn(primary);

        CredentialRotateRequest req = new CredentialRotateRequest();
        req.setAppId(1L);
        req.setEnvCode("prod");
        req.setExpireAfterDays(7);

        AppCredentialDto result = service.rotate(req);
        assertNotNull(result.getAppSecret(), "rotate 响应应含新凭证明文 secret");
        assertEquals(Integer.valueOf(1), result.getRotateFlag(), "新凭证 rotateFlag=1（轮换中）");
        assertEquals(Integer.valueOf(1), result.getStatus(), "新凭证 status=1（启用中）");

        // 验证原主密钥：updateById + alias 改了 + expireTime 设置（约 7 天后）
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential c) -> {
            return c.getId().equals(1L)
                    && c.getAlias() != null
                    && c.getAlias().contains("旧")
                    && c.getExpireTime() != null
                    && c.getExpireTime().isAfter(LocalDateTime.now().plusDays(6));
        }));
        // 验证插入新凭证
        verify(appCredentialMapper, times(1)).insert(argThat((AppCredential c) ->
                c.getRotateFlag() != null && c.getRotateFlag() == 1));
    }

    @Test
    @DisplayName("rotate 默认 expireAfterDays=7")
    void rotate_defaultSevenDays() {
        AppCredential primary = sample(1L, "ak_prod_1111111111111111", 1, 0);
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(null).thenReturn(primary);

        CredentialRotateRequest req = new CredentialRotateRequest();
        req.setAppId(1L);
        req.setEnvCode("prod");
        // 不设 expireAfterDays，期望默认 7

        service.rotate(req);
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential c) ->
                c.getExpireTime() != null
                        && c.getExpireTime().isAfter(LocalDateTime.now().plusDays(6))
                        && c.getExpireTime().isBefore(LocalDateTime.now().plusDays(8))));
    }

    @Test
    @DisplayName("rotate 越界 expireAfterDays（>30）兜底为 7")
    void rotate_overridesInvalidDays() {
        AppCredential primary = sample(1L, "ak_prod_1111111111111111", 1, 0);
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(null).thenReturn(primary);

        CredentialRotateRequest req = new CredentialRotateRequest();
        req.setAppId(1L);
        req.setEnvCode("prod");
        req.setExpireAfterDays(99); // 越界

        service.rotate(req);
        // 7 天豁口：6 < d < 8
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential c) ->
                c.getExpireTime() != null
                        && c.getExpireTime().isAfter(LocalDateTime.now().plusDays(6))
                        && c.getExpireTime().isBefore(LocalDateTime.now().plusDays(8))));
    }

    // =================================================================
    // completeRotate
    // =================================================================

    @Test
    @DisplayName("completeRotate 成功：轮换中→主 + 旧主→已吊销 status=3")
    void completeRotate_success() {
        AppCredential oldPrimary = sample(1L, "ak_prod_1111111111111111", 1, 0);
        AppCredential rotating = sample(2L, "ak_prod_2222222222222222", 1, 1);
        rotating.setAlias("主密钥-轮换中(新)");
        // 第一次查询"轮换中" → rotating
        // 第二次查询"主密钥" → oldPrimary
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(rotating)
                .thenReturn(oldPrimary);

        int n = service.completeRotate(1L, "prod");
        assertEquals(2, n);

        // 旧主密钥：updateById status=3
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential c) ->
                c.getId().equals(1L) && c.getStatus() != null && c.getStatus() == 3));
        // 轮换中凭证：updateById rotateFlag=0
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential c) ->
                c.getId().equals(2L) && c.getRotateFlag() != null && c.getRotateFlag() == 0));
    }

    @Test
    @DisplayName("completeRotate 不存在轮换中凭证抛 400")
    void completeRotate_noRotating() {
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(null);
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.completeRotate(1L, "prod"));
        assertTrue(ex.getMessage().contains("轮换中"));
    }

    // =================================================================
    // revoke
    // =================================================================

    @Test
    @DisplayName("revoke 立即吊销：status=3")
    void revoke_setsStatus3() {
        AppCredential c = sample(7L, "ak_prod_7777777777777777", 1, 0);
        when(appCredentialMapper.selectById(7L)).thenReturn(c);

        service.revoke(7L);
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential updated) ->
                updated.getStatus() != null && updated.getStatus() == 3));
    }

    @Test
    @DisplayName("revoke 已吊销凭证幂等（不报错）")
    void revoke_idempotentWhenAlreadyRevoked() {
        AppCredential c = sample(7L, "ak_prod_7777777777777777", 3, 0);
        c.setStatus(3); // 已吊销
        when(appCredentialMapper.selectById(7L)).thenReturn(c);

        service.revoke(7L);
        // 不应再次 updateById
        verify(appCredentialMapper, times(0)).updateById(any(AppCredential.class));
    }

    // =================================================================
    // update
    // =================================================================

    @Test
    @DisplayName("update 仅修改 alias / expireTime，密钥不动")
    void update_modifiesOnlyEditableFields() {
        AppCredential c = sample(5L, "ak_prod_5555555555555555", 1, 0);
        c.setAlias("旧别名");
        c.setAppSecret("ORIGINAL_ENCRYPTED_SECRET");
        when(appCredentialMapper.selectById(5L)).thenReturn(c);

        AppCredentialDto dto = new AppCredentialDto();
        dto.setAlias("新别名");
        dto.setExpireTime(LocalDateTime.now().plusDays(30));

        service.update(5L, dto);
        // 关键：appKey 与 appSecret 都不能被 DTO 改写
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential updated) ->
                updated.getAlias().equals("新别名")
                        && updated.getExpireTime() != null
                        && updated.getAppKey().equals("ak_prod_5555555555555555") // appKey 不变
                        && "ORIGINAL_ENCRYPTED_SECRET".equals(updated.getAppSecret()))); // 加密的 secret 不变
    }

    // =================================================================
    // CredentialFacadeService
    // =================================================================

    @Test
    @DisplayName("getActiveCredential 优先返回主密钥")
    void getActiveCredential_preferPrimary() {
        AppCredential primary = sample(1L, "ak_prod_1111111111111111", 1, 0);
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(primary);
        AppCredential result = service.getActiveCredential(1L, "prod");
        assertNotNull(result);
        assertEquals(0, result.getRotateFlag());
    }

    @Test
    @DisplayName("getActiveCredential 没有主密钥时返回轮换中凭证")
    void getActiveCredential_fallsBackToRotating() {
        AppCredential rotating = sample(2L, "ak_prod_2222222222222222", 1, 1);
        // 第一次查主密钥 → null；第二次查轮换中 → rotating
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(null).thenReturn(rotating);

        AppCredential result = service.getActiveCredential(1L, "prod");
        assertNotNull(result);
        assertEquals(1, result.getRotateFlag());
    }

    @Test
    @DisplayName("getActiveCredential 都没有时返回 null")
    void getActiveCredential_noActive() {
        when(appCredentialMapper.selectOne(argThat((QueryWrapper<AppCredential> q) -> true)))
                .thenReturn(null).thenReturn(null);
        assertNull(service.getActiveCredential(1L, "prod"));
    }

    // =================================================================
    // 工具：构造一个样例 AppCredential
    // =================================================================
    private AppCredential sample(Long id, String appKey, int status, int rotateFlag) {
        AppCredential c = new AppCredential();
        c.setId(id);
        c.setAppId(1L);
        c.setEnvCode("prod");
        c.setAppKey(appKey);
        c.setAppSecret("AES_CIPHERTEXT_BASE64");
        c.setSecretMask("Yk3m****J5sU");
        c.setAlias("主密钥");
        c.setStatus(status);
        c.setRotateFlag(rotateFlag);
        c.setCreateTime(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }
}
