package com.start.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkingMemory 字段测试。
 *
 * 验证：白板模型的两个关键字段（status, expiresAt）能正常 get/set。
 * Repository 和 SetWorkingMemoryTool 依赖这两个字段，不应该有 setter 缺失。
 */
class WorkingMemoryTest {

    @Test
    void newInstance_defaultFieldsAreSensible() {
        WorkingMemory wm = new WorkingMemory();
        assertNull(wm.getId());
        assertNull(wm.getThreadId());
        assertNull(wm.getStatus(), "默认 status 应为 null（由 save 时设 ACTIVE）");
        assertNull(wm.getExpiresAt(), "默认 expiresAt 应为 null（由 save 时算）");
        assertFalse(wm.isActive(), "默认 active=false");
    }

    @Test
    void status_setterAndGetter_work() {
        WorkingMemory wm = new WorkingMemory();
        wm.setStatus(WorkingMemoryStatus.ACTIVE);
        assertEquals(WorkingMemoryStatus.ACTIVE, wm.getStatus());

        wm.setStatus(WorkingMemoryStatus.COMPLETED);
        assertEquals(WorkingMemoryStatus.COMPLETED, wm.getStatus());

        wm.setStatus(WorkingMemoryStatus.EXPIRED);
        assertEquals(WorkingMemoryStatus.EXPIRED, wm.getStatus());
    }

    @Test
    void expiresAt_setterAndGetter_work() {
        WorkingMemory wm = new WorkingMemory();
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        wm.setExpiresAt(tomorrow);
        assertEquals(tomorrow, wm.getExpiresAt());
    }

    @Test
    void goalAndContext_settersWork() {
        WorkingMemory wm = new WorkingMemory();
        wm.setGoal("帮用户查东京机票");
        wm.setContextSummary("已查到 7 月航班，等待用户确认");

        assertEquals("帮用户查东京机票", wm.getGoal());
        assertEquals("已查到 7 月航班，等待用户确认", wm.getContextSummary());
    }

    @Test
    void activeField_stillSupported_legacyCompat() {
        // 保留 active 字段以兼容老代码读取路径
        WorkingMemory wm = new WorkingMemory();
        wm.setActive(true);
        assertTrue(wm.isActive());

        wm.setActive(false);
        assertFalse(wm.isActive());
    }
}
