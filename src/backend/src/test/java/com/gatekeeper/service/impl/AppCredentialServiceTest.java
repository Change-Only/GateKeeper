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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *
 * <h3>⚠️ 2026-09-14 重构说明（勿回退）</h3>
 * <p>生产代码 {@code findLivePrimary/findLiveRotating} 已从 {@code baseMapper.selectOne(rotate_flag=N)}
 * 改为 {@code selectList(rotate_flag=N AND status=1)} + 按 id 倒序取首条 —— 因为
 * {@code completeRotate} 只把旧主密钥置 {@code status=3} 而不改 {@code rotate_flag}，
 * 同一 {@code (appId, envCode)} 下会留下两条 {@code rotate_flag=0}，{@code selectOne} 必抛
 * {@code TooManyResultsException} → HTTP 500「系统繁忙，请稍后重试」。
 * 因此本测试的桩也统一由 {@link #stubRows} 按「参数里 rotate_flag 是 0 还是 1」分派，
 * <b>不再使用顺序桩</b>（两个查询的调用先后在 rotate / completeRotate / getActiveCredential
 * 三个方法里并不一致，顺序桩极易写错）。</p>
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
        stubRows(null, rotating);

        GatewayException ex = assertThrows(GatewayException.class, () -> service.rotate(rotateReq()));
        assertTrue(ex.getMessage().contains("轮换中"));
    }

    @Test
    @DisplayName("rotate 没有主密钥时拒绝")
    void rotate_noPrimaryFails() {
        stubRows(null, null);

        GatewayException ex = assertThrows(GatewayException.class, () -> service.rotate(rotateReq()));
        assertTrue(ex.getMessage().contains("主密钥"));
    }

    @Test
    @DisplayName("rotate 正常流程：原主密钥 7d 过期 + 新建轮换中凭证")
    void rotate_successFlow() {
        AppCredential primary = sample(1L, "ak_prod_1111111111111111", 1, 0);
        primary.setAlias("主密钥");
        stubRows(primary, null);

        CredentialRotateRequest req = rotateReq();
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
        stubRows(primary, null);

        // 不设 expireAfterDays，期望默认 7
        service.rotate(rotateReq());
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential c) ->
                c.getExpireTime() != null
                        && c.getExpireTime().isAfter(LocalDateTime.now().plusDays(6))
                        && c.getExpireTime().isBefore(LocalDateTime.now().plusDays(8))));
    }

    @Test
    @DisplayName("rotate 越界 expireAfterDays（>30）兜底为 7")
    void rotate_overridesInvalidDays() {
        AppCredential primary = sample(1L, "ak_prod_1111111111111111", 1, 0);
        stubRows(primary, null);

        CredentialRotateRequest req = rotateReq();
        req.setExpireAfterDays(99); // 越界

        service.rotate(req);
        // 7 天豁口：6 < d < 8
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential c) ->
                c.getExpireTime() != null
                        && c.getExpireTime().isAfter(LocalDateTime.now().plusDays(6))
                        && c.getExpireTime().isBefore(LocalDateTime.now().plusDays(8))));
    }

    /**
     * 回归测试 —— 2026-09-14 线上 500「系统繁忙，请稍后重试」的守门用例。
     *
     * <p>场景：完成过一次轮换后，同一 {@code (appId, envCode)} 下存在两条 {@code rotate_flag=0}
     * 记录（旧的 {@code status=3} 已吊销 + 新的 {@code status=1} 生效中）。</p>
     *
     * <p>修复前：{@code baseMapper.selectOne(rotate_flag=0)} 命中 2 行 ⇒ MyBatis-Plus 抛
     * {@code TooManyResultsException} ⇒ HTTP 500。修复后：{@code selectList} + 按 id 倒序取首条，
     * 取到「最新的生效主密钥」并正常完成轮换。</p>
     */
    @Test
    @DisplayName("回归：同 env 两条 rotate_flag=0 的脏数据下 rotate 不再抛异常（原本 HTTP 500）")
    void rotate_toleratesDuplicatePrimaryRows() {
        AppCredential revokedOld = sample(1L, "ak_prod_1111111111111111", 3, 0); // 已吊销的旧主密钥
        revokedOld.setAlias("主密钥-旧(已于 7 天后吊销)");
        AppCredential livePrimary = sample(5L, "ak_prod_5555555555555555", 1, 0); // 生效中的新主密钥
        // 数据库 order by id desc ⇒ 最新（id=5）在前
        stubPrimaryRows(Arrays.asList(livePrimary, revokedOld), null);

        // 修复前这里抛 TooManyResultsException（被兜底成 HTTP 500）
        AppCredentialDto result = assertDoesNotThrow(() -> service.rotate(rotateReq()));
        assertEquals(Integer.valueOf(1), result.getRotateFlag());

        // 被改写成「旧(将于 N 天后吊销)」的必须是 id 最大的生效主密钥，而不是已吊销的旧行
        verify(appCredentialMapper, times(1)).updateById(argThat((AppCredential c) ->
                c.getId().equals(5L) && c.getAlias() != null && c.getAlias().contains("旧")));
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
        stubRows(oldPrimary, rotating);

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
        stubRows(null, null);
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
        stubRows(primary, null);
        AppCredential result = service.getActiveCredential(1L, "prod");
        assertNotNull(result);
        assertEquals(0, result.getRotateFlag());
    }

    @Test
    @DisplayName("getActiveCredential 没有主密钥时返回轮换中凭证")
    void getActiveCredential_fallsBackToRotating() {
        AppCredential rotating = sample(2L, "ak_prod_2222222222222222", 1, 1);
        stubRows(null, rotating);

        AppCredential result = service.getActiveCredential(1L, "prod");
        assertNotNull(result);
        assertEquals(1, result.getRotateFlag());
    }

    @Test
    @DisplayName("getActiveCredential 都没有时返回 null")
    void getActiveCredential_noActive() {
        stubRows(null, null);
        assertNull(service.getActiveCredential(1L, "prod"));
    }

    // =================================================================
    // 工具：桩 / 构造
    // =================================================================

    /**
     * 按 {@code QueryWrapper} 里 {@code rotate_flag} 的取值，分别给「主密钥查询」与「轮换中查询」装桩。
     *
     * <p>两个查询都是 {@code selectList(QueryWrapper)}，外部看不出区别，只能靠参数内容区分：
     * {@code findLivePrimary} 带 {@code rotate_flag=0}，其 {@code paramNameValuePairs} 值集合里
     * 必然出现 {@code 0}；{@code findLiveRotating} 带 {@code rotate_flag=1}，值集合里不会出现
     * {@code 0}（测试里 appId=1、envCode="prod"、status=1，都不会是 0）。</p>
     *
     * @param primary  主密钥行；{@code null} 表示查无
     * @param rotating 轮换中行；{@code null} 表示查无
     */
    private void stubRows(AppCredential primary, AppCredential rotating) {
        stubPrimaryRows(rowsOf(primary), rotating);
    }

    /**
     * 主密钥查询返回「多行」（模拟完成轮换后遗留的重复 {@code rotate_flag=0} 脏数据）。
     *
     * <p>⚠️ 这里刻意用 {@code any() + thenAnswer} 而不是两个 {@code argThat(...)} 桩：
     * Mockito 在注册第 N 个桩时会拿「参数表达式本身」（{@code argThat} 返回 {@code null}）
     * 去跑已存在桩的匹配器，{@code argThat} 里的 lambda 于是被传入 {@code null} 而 NPE。
     * 放到 {@code thenAnswer} 里判断，拿到的一定是调用时的真实 {@code QueryWrapper}。</p>
     *
     * @param primaryRows 主密钥查询返回的行列表（调用方自行按 id desc 排序）
     * @param rotating    轮换中行；{@code null} 表示查无
     */
    private void stubPrimaryRows(List<AppCredential> primaryRows, AppCredential rotating) {
        List<AppCredential> primaryHits = primaryRows == null ? Collections.emptyList() : primaryRows;
        when(appCredentialMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> {
            QueryWrapper<AppCredential> q = inv.getArgument(0);
            return isPrimaryQuery(q) ? primaryHits : rowsOf(rotating);
        });
    }

    /** 匹配 {@code rotate_flag = #{ew.paramNameValuePairs.MPGENVAL3}} 中的参数键名。 */
    private static final Pattern ROTATE_FLAG_PARAM =
            Pattern.compile("rotate_flag\\s*=\\s*#\\{[^}]*paramNameValuePairs\\.(\\w+)\\}");

    /**
     * 主密钥查询特征：{@code rotate_flag=0}。
     *
     * <p>🔴 <b>必须先调 {@code getSqlSegment()}，这不是可省略的「顺手一句」：</b>
     * MyBatis-Plus 的 {@code paramNameValuePairs} 是<b>惰性</b>的 —— 只在生成 SQL 片段时才被填入。
     * 实测（MP 3.5.x）：建好 wrapper 后直接读得到 {@code {}}（空 map），调过 {@code getSqlSegment()}
     * 才变成 {@code {MPGENVAL3=0, MPGENVAL2=prod, MPGENVAL1=1, MPGENVAL4=1}}。
     * 依赖 {@code containsValue(0)} 直接判断会让<b>每一条</b>断言都落空。
     * 这里从 SQL 片段里反解出 {@code rotate_flag} 对应的参数键，再按键取值，
     * 因此不依赖 MPGENVAL 序号，生产代码调整条件顺序也不会失效。</p>
     */
    private static boolean isPrimaryQuery(QueryWrapper<AppCredential> q) {
        if (q == null) {
            return false;
        }
        String segment = q.getSqlSegment();
        Matcher m = ROTATE_FLAG_PARAM.matcher(segment == null ? "" : segment);
        if (!m.find()) {
            return false;
        }
        return Integer.valueOf(0).equals(q.getParamNameValuePairs().get(m.group(1)));
    }

    private static List<AppCredential> rowsOf(AppCredential c) {
        return c == null ? Collections.emptyList() : Collections.singletonList(c);
    }

    private static CredentialRotateRequest rotateReq() {
        CredentialRotateRequest req = new CredentialRotateRequest();
        req.setAppId(1L);
        req.setEnvCode("prod");
        return req;
    }

    /** 构造一个样例 AppCredential。 */
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
