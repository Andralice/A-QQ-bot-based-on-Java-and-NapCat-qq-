package com.start.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkingMemoryStatus 枚举测试。
 *
 * 验证：枚举值定义正确，名称语义稳定（DB 字符串映射依赖 name()）。
 * 若以后改枚举名会破坏 DB 兼容性，故此测试保证"name() 字符串"不变。
 */
class WorkingMemoryStatusTest {

    @Test
    void enumValues_exist() {
        WorkingMemoryStatus[] values = WorkingMemoryStatus.values();
        assertEquals(3, values.length, "应该有 3 个状态：ACTIVE/COMPLETED/EXPIRED");

        // 确认每个值存在
        assertNotNull(WorkingMemoryStatus.ACTIVE);
        assertNotNull(WorkingMemoryStatus.COMPLETED);
        assertNotNull(WorkingMemoryStatus.EXPIRED);
    }

    @Test
    void enumNames_stableForDbMapping() {
        // Repository 直接用 .name() 存 DB，valueOf() 读 DB
        // 改枚举名 = DB 列值不再解析 → 静默失败
        assertEquals("ACTIVE", WorkingMemoryStatus.ACTIVE.name());
        assertEquals("COMPLETED", WorkingMemoryStatus.COMPLETED.name());
        assertEquals("EXPIRED", WorkingMemoryStatus.EXPIRED.name());
    }

    @Test
    void valueOf_roundtrip() {
        // DB 存的是字符串，反序列化用 valueOf
        for (WorkingMemoryStatus s : WorkingMemoryStatus.values()) {
            assertEquals(s, WorkingMemoryStatus.valueOf(s.name()));
        }
    }
}
