package com.gatekeeper.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常时段判定单元测试 — OffHoursDetector.isOffHours 纯函数
 *
 * <p>覆盖：跨天时段（23:00-06:00）与同日时段（08:00-18:00）的边界值。</p>
 */
class OffHoursDetectorTest {

    @Test
    void isOffHours_crossDay_shouldMarkNightAsOffHours() {
        // 跨天 23:00-06:00：23、0、1、5 属于异常时段
        assertTrue(OffHoursDetector.isOffHours(23, 23, 6));
        assertTrue(OffHoursDetector.isOffHours(0, 23, 6));
        assertTrue(OffHoursDetector.isOffHours(5, 23, 6));
        // 6（区间结束，不含）与 12 属于正常时段
        assertFalse(OffHoursDetector.isOffHours(6, 23, 6));
        assertFalse(OffHoursDetector.isOffHours(12, 23, 6));
    }

    @Test
    void isOffHours_sameDay_shouldMarkOutsideWorkHours() {
        // 同日 08:00-18:00：7、18 属于异常时段，8、17 属于正常时段
        assertTrue(OffHoursDetector.isOffHours(7, 8, 18));
        assertTrue(OffHoursDetector.isOffHours(18, 8, 18));
        assertFalse(OffHoursDetector.isOffHours(8, 8, 18));
        assertFalse(OffHoursDetector.isOffHours(17, 8, 18));
    }
}
