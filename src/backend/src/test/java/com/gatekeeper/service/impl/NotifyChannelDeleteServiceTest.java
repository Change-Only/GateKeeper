package com.gatekeeper.service.impl;

import com.gatekeeper.alarm.NotifySender;
import com.gatekeeper.alarm.impl.NotifyChannelServiceImpl;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.NotifyChannelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotifyChannelServiceImpl.delete 行为单测 — T07-A 补课（新增的 DELETE 端点背后的服务逻辑）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>id 为空 → badRequest</li>
 *   <li>渠道不存在 → notFound，且**不**触发删除</li>
 *   <li>正常删除 → 先 selectById 校验存在，再 deleteById</li>
 * </ul></p>
 *
 * <p>写法参照 {@code AppCredentialServiceTest}：MyBatis-Plus ServiceImpl 的 {@code baseMapper}
 * 为 protected 字段，需反射注入 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotifyChannelService.delete T07-A")
class NotifyChannelDeleteServiceTest {

    @Mock
    private NotifyChannelMapper notifyChannelMapper;

    @Mock
    private NotifySender notifySender;

    @Mock
    private com.gatekeeper.crypto.CryptoService cryptoService;

    private NotifyChannelServiceImpl service;

    @BeforeEach
    void setUp() {
        // T09-N1 起构造函数增加 CryptoService（配置加密链路）
        service = new NotifyChannelServiceImpl(notifySender, cryptoService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "aesDbKey", "TEST_32_BYTES_LONG_KEY_FOR_AES_256");
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(service, notifyChannelMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("delete(null) → 400 badRequest，不触碰 mapper")
    void delete_nullId_badRequest() {
        assertThrows(GatewayException.class, () -> service.delete(null));
        verify(notifyChannelMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("delete(不存在id) → 404 notFound，且不执行删除")
    void delete_notFound_throwsNotFound() {
        when(notifyChannelMapper.selectById(99L)).thenReturn(null);
        assertThrows(GatewayException.class, () -> service.delete(99L));
        verify(notifyChannelMapper, times(1)).selectById(99L);
        verify(notifyChannelMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("delete(存在id) → 先查后删，deleteById 恰调用一次")
    void delete_existing_deletes() {
        NotifyChannel ch = new NotifyChannel();
        ch.setId(5L);
        ch.setChannelName("企业微信");
        when(notifyChannelMapper.selectById(5L)).thenReturn(ch);

        service.delete(5L);

        verify(notifyChannelMapper, times(1)).selectById(5L);
        verify(notifyChannelMapper, times(1)).deleteById(5L);
    }

    @Test
    @DisplayName("delete 返回值语义：ServiceImpl.deleteById 采用 Long 入参，避免误删")
    void delete_usesPrimaryKeyOnly() {
        NotifyChannel ch = new NotifyChannel();
        ch.setId(12L);
        when(notifyChannelMapper.selectById(12L)).thenReturn(ch);

        service.delete(12L);

        verify(notifyChannelMapper).deleteById(12L);
        assertNotNull(ch.getId());
    }
}
