package com.gatekeeper.config;

import com.gatekeeper.entity.SysConfig;
import com.gatekeeper.mapper.SysConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统参数运行时读取器测试 —— T19「参数配置页真正生效」的地基。
 *
 * <p>要钉死的四件事：</p>
 * <ol>
 *   <li>有值就用库里的值（否则接线无意义）；</li>
 *   <li>缺键 / 空值 / 非法值一律回退调用方默认值（不能让一张配置表把网关搞挂）；</li>
 *   <li>查询异常 fail-open 回退默认值（DB 故障不阻断链路）；</li>
 *   <li>有缓存且 evict 后能重读（保证「改完立刻生效」）。</li>
 * </ol>
 */
@DisplayName("SysConfigAccessor：sys_config 运行时读取（缓存 / 默认值 / fail-open）")
class SysConfigAccessorTest {

    private SysConfigMapper mapper;
    private SysConfigAccessor accessor;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(SysConfigMapper.class);
        accessor = new SysConfigAccessor(mapper);
    }

    /** 构造一行配置 */
    private SysConfig row(String key, String value) {
        SysConfig c = new SysConfig();
        c.setConfigKey(key);
        c.setConfigValue(value);
        return c;
    }

    /** 让 mapper 对任意键返回给定行 */
    private void stubRows(SysConfig... rows) {
        Mockito.when(mapper.selectList(Mockito.any()))
                .thenReturn(rows == null ? Collections.emptyList() : Arrays.asList(rows));
    }

    @Test
    @DisplayName("库里有值 → 用库里的值（这是接线的意义所在）")
    void getInt_readsValueFromDb() {
        stubRows(row("sign.nonce.ttl", "600"));

        assertEquals(600, accessor.getInt("sign.nonce.ttl", 300));
    }

    @Test
    @DisplayName("键不存在 → 回退调用方默认值")
    void getInt_fallsBackWhenKeyAbsent() {
        stubRows();

        assertEquals(300, accessor.getInt("sign.nonce.ttl", 300));
    }

    @Test
    @DisplayName("值为空串 → 回退默认值（不把空串当 0）")
    void getInt_fallsBackWhenValueBlank() {
        stubRows(row("sign.nonce.ttl", "   "));

        assertEquals(300, accessor.getInt("sign.nonce.ttl", 300));
    }

    @Test
    @DisplayName("值不是数字 → 回退默认值（不当成 0，否则会把窗口收敛成 0 秒全拒）")
    void getInt_fallsBackWhenValueNotNumeric() {
        stubRows(row("sign.timestamp.tolerance", "五万毫秒"));

        assertEquals(300000, accessor.getInt("sign.timestamp.tolerance", 300000));
    }

    @Test
    @DisplayName("查询抛异常 → fail-open 回退默认值（DB 故障不阻断网关）")
    void getInt_failsOpenOnException() {
        Mockito.when(mapper.selectList(Mockito.any())).thenThrow(new RuntimeException("db down"));

        assertEquals(300000, accessor.getInt("sign.timestamp.tolerance", 300000));
        assertTrue(accessor.getBoolean("gateway.auth.enabled", true), "异常时应回退「开启」这一安全默认值");
    }

    @Test
    @DisplayName("布尔值宽松解析：true/1/yes/on 与 false/0/no/off")
    void getBoolean_parsesLoosely() {
        stubRows(row("gateway.auth.enabled", "TRUE"));
        assertTrue(accessor.getBoolean("gateway.auth.enabled", false));

        accessor.evictAll();
        stubRows(row("gateway.auth.enabled", "1"));
        assertTrue(accessor.getBoolean("gateway.auth.enabled", false));

        accessor.evictAll();
        stubRows(row("gateway.auth.enabled", "yes"));
        assertTrue(accessor.getBoolean("gateway.auth.enabled", false));

        accessor.evictAll();
        stubRows(row("gateway.auth.enabled", "on"));
        assertTrue(accessor.getBoolean("gateway.auth.enabled", false));

        accessor.evictAll();
        stubRows(row("gateway.auth.enabled", "false"));
        assertFalse(accessor.getBoolean("gateway.auth.enabled", true));

        accessor.evictAll();
        stubRows(row("gateway.auth.enabled", "0"));
        assertFalse(accessor.getBoolean("gateway.auth.enabled", true));

        accessor.evictAll();
        stubRows(row("gateway.auth.enabled", "no"));
        assertFalse(accessor.getBoolean("gateway.auth.enabled", true));

        accessor.evictAll();
        stubRows(row("gateway.auth.enabled", "off"));
        assertFalse(accessor.getBoolean("gateway.auth.enabled", true));
    }

    @Test
    @DisplayName("布尔值非法（阴阳值）→ 回退默认值，不静默当 false")
    void getBoolean_fallsBackOnInvalidValue() {
        stubRows(row("gateway.auth.enabled", "maybe"));

        assertTrue(accessor.getBoolean("gateway.auth.enabled", true),
                "非法值必须回退默认值：静默当 false 会让验签开关被意外关掉");
    }

    @Test
    @DisplayName("命中缓存 → 不重复查库（网关热路径不得每请求一次 DB）")
    void cachedValue_doesNotHitDbTwice() {
        stubRows(row("sign.nonce.ttl", "600"));

        accessor.getInt("sign.nonce.ttl", 300);
        accessor.getInt("sign.nonce.ttl", 300);

        Mockito.verify(mapper, Mockito.times(1)).selectList(Mockito.any());
    }

    @Test
    @DisplayName("evictAll 之后能读到新值（写后失效 ⇒ 改完立刻生效）")
    void evictAll_forcesReload() {
        stubRows(row("sign.nonce.ttl", "600"));
        assertEquals(600, accessor.getInt("sign.nonce.ttl", 300));

        stubRows(row("sign.nonce.ttl", "120"));
        // 未失效时仍是旧值（缓存生效）
        assertEquals(600, accessor.getInt("sign.nonce.ttl", 300));

        accessor.evictAll();
        assertEquals(120, accessor.getInt("sign.nonce.ttl", 300), "写后失效应立即可见新值");
    }

    @Test
    @DisplayName("多行同键 → 取 id 升序首个非空值（刻意不用 selectOne，避免 TooManyResultsException）")
    void multipleRows_takesFirstNonBlank() {
        List<SysConfig> rows = new ArrayList<>();
        SysConfig blank = row("sign.nonce.ttl", null);
        blank.setId(1L);
        SysConfig first = row("sign.nonce.ttl", "600");
        first.setId(2L);
        rows.add(blank);
        rows.add(first);
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(rows);

        assertEquals(600, accessor.getInt("sign.nonce.ttl", 300));
    }

    @Test
    @DisplayName("getString / getLong 的正常与回退路径")
    void stringAndLongAccessors() {
        stubRows(row("sign.algorithm", " SM3 "));
        assertEquals("SM3", accessor.getString("sign.algorithm", "SM3"));

        accessor.evictAll();
        stubRows();
        assertEquals("SM3", accessor.getString("sign.algorithm", "SM3"), "缺键回退默认");

        accessor.evictAll();
        stubRows(row("call.log.hot.days", "90"));
        assertEquals(90L, accessor.getLong("call.log.hot.days", 30L));
    }
}
