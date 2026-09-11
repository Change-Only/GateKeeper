package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.EnvDto;
import com.gatekeeper.entity.Env;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.EnvMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EnvServiceImpl 单元测试 — T03a 环境服务
 *
 * <p>覆盖：CRUD + envCode 唯一性 + envCode 不可修改校验（架构 D1）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EnvService CRUD + 业务规则")
class EnvServiceTest {

    @Mock
    private EnvMapper envMapper;

    private EnvServiceImpl envService;

    @BeforeEach
    void setUp() {
        envService = new EnvServiceImpl();
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(envService, envMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("listEnabled 返回 status=1 环境并按 sort_order 排序")
    void listEnabled_returnsActive() {
        Env e = new Env();
        e.setId(1L);
        e.setEnvCode("dev");
        e.setStatus(1);
        e.setGatewayUrl("https://api-dev.example.com");
        when(envMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(e));

        List<EnvDto> result = envService.listEnabled();
        assertEquals(1, result.size());
        assertEquals("dev", result.get(0).getEnvCode());
        // https 字段从 gatewayUrl 推导
        assertEquals(Integer.valueOf(1), result.get(0).getHttps());
    }

    @Test
    @DisplayName("listEnabled https 从 gatewayUrl 推导（http:// → 0）")
    void listEnabled_httpsDerivedFromUrl() {
        Env e = new Env();
        e.setId(1L);
        e.setEnvCode("dev");
        e.setStatus(1);
        e.setGatewayUrl("http://api-dev.example.com");
        when(envMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(e));

        List<EnvDto> result = envService.listEnabled();
        assertEquals(Integer.valueOf(0), result.get(0).getHttps());
    }

    @Test
    @DisplayName("getByEnvCode 按 envCode 查询")
    void getByEnvCode_returnsEntity() {
        Env e = new Env();
        e.setId(1L);
        e.setEnvCode("prod");
        when(envMapper.selectOne(any(QueryWrapper.class))).thenReturn(e);

        Env result = envService.getByEnvCode("prod");
        assertNotNull(result);
        assertEquals("prod", result.getEnvCode());
    }

    @Test
    @DisplayName("getByEnvCode 入参为 null 返回 null（不抛异常）")
    void getByEnvCode_handlesNull() {
        assertEquals(null, envService.getByEnvCode(null));
        assertEquals(null, envService.getByEnvCode(""));
    }

    @Test
    @DisplayName("createEnv envCode 不在白名单（uat）抛 400")
    void createEnv_rejectsUnsupportedEnvCode() {
        EnvDto dto = new EnvDto();
        dto.setEnvCode("uat"); // 不在 dev/test/pre/prod 之列
        dto.setEnvName("UAT");
        dto.setGatewayUrl("https://api-uat.example.com");

        GatewayException ex = assertThrows(GatewayException.class,
                () -> envService.createEnv(dto));
        assertTrue(ex.getMessage().contains("dev/test/pre/prod"));
    }

    @Test
    @DisplayName("createEnv 成功")
    void createEnv_success() {
        EnvDto dto = new EnvDto();
        dto.setEnvCode("pre");
        dto.setEnvName("预发环境");
        dto.setGatewayUrl("https://api-pre.example.com");
        dto.setSortOrder(3);
        when(envMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        Env result = envService.createEnv(dto);
        assertEquals("pre", result.getEnvCode());
        assertEquals(Integer.valueOf(1), result.getStatus()); // 默认启用
        verify(envMapper, times(1)).insert(any(Env.class));
    }

    @Test
    @DisplayName("createEnv 重复 envCode 抛业务异常")
    void createEnv_duplicateFails() {
        Env existing = new Env();
        existing.setId(99L);
        existing.setEnvCode("pre");
        when(envMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        EnvDto dto = new EnvDto();
        dto.setEnvCode("pre");
        dto.setEnvName("预发");
        dto.setGatewayUrl("https://x.com");

        GatewayException ex = assertThrows(GatewayException.class,
                () -> envService.createEnv(dto));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("updateEnv 试图修改 envCode 抛 400（架构 D1）")
    void updateEnv_rejectsEnvCodeChange() {
        Env existing = new Env();
        existing.setId(1L);
        existing.setEnvCode("pre");
        when(envMapper.selectById(1L)).thenReturn(existing);

        EnvDto dto = new EnvDto();
        dto.setId(1L);
        dto.setEnvCode("prod"); // 试图改
        dto.setEnvName("改");
        dto.setGatewayUrl("https://api.example.com");

        GatewayException ex = assertThrows(GatewayException.class,
                () -> envService.updateEnv(1L, dto));
        assertTrue(ex.getMessage().contains("不可修改"));
    }

    @Test
    @DisplayName("updateEnv 保持 envCode 不变则允许")
    void updateEnv_allowsSameEnvCode() {
        Env existing = new Env();
        existing.setId(1L);
        existing.setEnvCode("pre");
        when(envMapper.selectById(1L)).thenReturn(existing);

        EnvDto dto = new EnvDto();
        dto.setId(1L);
        dto.setEnvCode("pre"); // 没变
        dto.setEnvName("改名了");
        dto.setGatewayUrl("https://api-pre.example.com");

        envService.updateEnv(1L, dto);
        verify(envMapper, times(1)).updateById(any(Env.class));
    }

    @Test
    @DisplayName("deleteEnv 成功（架构 D1：删/停用不影响存量凭证/授权）")
    void deleteEnv_succeeds() {
        Env existing = new Env();
        existing.setId(4L);
        existing.setEnvCode("prod");
        when(envMapper.selectById(4L)).thenReturn(existing);

        envService.deleteEnv(4L);
        verify(envMapper, times(1)).deleteById(4L);
    }
}
