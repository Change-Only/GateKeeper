package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.Alert;
import com.gatekeeper.mapper.AlertMapper;
import com.gatekeeper.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警服务单元测试（AlertServiceImpl）
 *
 * <p>覆盖：告警发布落库、分页查询筛选、未读统计、标记已读 / 全部已读、告警处置。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertServiceImplTest {

    @Mock
    private AlertMapper alertMapper;

    private AlertServiceImpl alertService;

    @BeforeEach
    void setUp() {
        alertService = new AlertServiceImpl();
        // 通过反射注入 Mock Mapper（ServiceImpl 的 baseMapper 为 protected 字段）
        try {
            java.lang.reflect.Field field = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            field.setAccessible(true);
            field.set(alertService, alertMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void publish_shouldInsertAlertWithUnreadStatus() {
        alertService.publish("CRITICAL", "GATEWAY", "网关内部错误", "测试内容", 1L, "测试应用", "127.0.0.1");

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper, times(1)).insert(captor.capture());

        Alert saved = captor.getValue();
        assertEquals("CRITICAL", saved.getLevel());
        assertEquals("GATEWAY", saved.getSource());
        assertEquals("网关内部错误", saved.getTitle());
        assertEquals(AlertService.STATUS_UNREAD, saved.getStatus());
        assertEquals(1L, saved.getRelatedAppId());
        assertNotNull(saved.getOccurredAt());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void unreadCount_shouldCountUnreadAlerts() {
        when(alertMapper.selectCount(any(QueryWrapper.class))).thenReturn(3L);
        assertEquals(3L, alertService.unreadCount());
    }

    @Test
    void pageQuery_shouldReturnPageResultOrderedByTimeDesc() {
        Alert alert = new Alert();
        alert.setId(1L);
        alert.setLevel("WARNING");
        when(alertMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class), any(QueryWrapper.class)))
                .thenAnswer(invocation -> {
                    // MyBatis-Plus 会就地修改传入的 Page 对象，Mock 需模拟该行为
                    com.baomidou.mybatisplus.extension.plugins.pagination.Page<Alert> p = invocation.getArgument(0);
                    p.setRecords(Collections.singletonList(alert));
                    p.setTotal(1);
                    return p;
                });

        PageResult<Alert> result = alertService.pageQuery(1, 10, "WARNING", null, null);
        assertEquals(1, result.getTotal());
        assertEquals("WARNING", result.getRecords().get(0).getLevel());
        verify(alertMapper).selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class), any(QueryWrapper.class));
    }

    @Test
    void markRead_shouldUpdateStatusToRead() {
        Alert existing = new Alert();
        existing.setId(10L);
        existing.setStatus(AlertService.STATUS_UNREAD);
        when(alertMapper.selectById(10L)).thenReturn(existing);

        alertService.markRead(10L);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).updateById(captor.capture());
        assertEquals(AlertService.STATUS_READ, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getReadAt());
    }

    @Test
    void markAllRead_shouldUpdateAllUnread() {
        alertService.markAllRead();
        verify(alertMapper).update(any(Alert.class), any(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class));
    }

    @Test
    void handleAlert_shouldSetStatusAndHandledAt() {
        alertService.handleAlert(5L, AlertService.STATUS_HANDLED, "已封禁来源IP");

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).updateById(captor.capture());
        assertEquals(AlertService.STATUS_HANDLED, captor.getValue().getStatus());
        assertEquals("已封禁来源IP", captor.getValue().getHandleRemark());
        assertNotNull(captor.getValue().getHandledAt());
    }
}
