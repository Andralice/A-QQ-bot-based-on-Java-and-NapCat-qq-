package com.start.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotConfig 静态初始化阶段的占位符处理测试（5.1 启动链路）。
 *
 * <p>这些 helper 方法必须包内可见，否则 BotConfig.<clinit> 会在环境变量缺失时
 * 抛 NumberFormatException，把后续 preflightCheck 跳过 —— 这正是 5.1 要修的缺陷。
 */
class BotConfigPlaceholderTest {

    // ── isUnresolvedPlaceholder ──

    @Test
    void detectsPurePlaceholder() {
        assertTrue(BotConfig.isUnresolvedPlaceholder("${BOT_QQ}"));
        assertTrue(BotConfig.isUnresolvedPlaceholder("${ALLOWED_GROUPS}"));
    }

    @Test
    void detectsPlaceholderWithWhitespace() {
        assertTrue(BotConfig.isUnresolvedPlaceholder("  ${VAR}  "));
        assertTrue(BotConfig.isUnresolvedPlaceholder("\t${X}\n"));
    }

    @Test
    void rejectsNullOrEmpty() {
        assertFalse(BotConfig.isUnresolvedPlaceholder(null));
        assertFalse(BotConfig.isUnresolvedPlaceholder(""));
        assertFalse(BotConfig.isUnresolvedPlaceholder("   "));
    }

    @Test
    void rejectsResolvedValue() {
        assertFalse(BotConfig.isUnresolvedPlaceholder("12345"));
        assertFalse(BotConfig.isUnresolvedPlaceholder("123,456"));
        assertFalse(BotConfig.isUnresolvedPlaceholder("abc"));
    }

    @Test
    void rejectsPlaceholderWithTextAround() {
        // 包含 ${ 但不是单个占位符的，保留原值（外部有处理逻辑）
        assertFalse(BotConfig.isUnresolvedPlaceholder("123,${PLACEHOLDER}"));
        assertFalse(BotConfig.isUnresolvedPlaceholder("prefix-${X}"));
    }

    // ── parseLongSet 占位符过滤 ──

    @Test
    void parseLongSetEmptyOnPurePlaceholder() {
        // 整个 value 是 ${...} → 当作未配置，返回空集
        assertTrue(BotConfig.parseLongSet("${ALLOWED_GROUPS}").isEmpty());
        assertTrue(BotConfig.parseLongSet("${ALLOWED_PRIVATE_USERS}").isEmpty());
    }

    @Test
    void parseLongSetEmptyOnNullOrEmpty() {
        assertTrue(BotConfig.parseLongSet(null).isEmpty());
        assertTrue(BotConfig.parseLongSet("").isEmpty());
        assertTrue(BotConfig.parseLongSet("   ").isEmpty());
    }

    @Test
    void parseLongSetFiltersPlaceholderElement() {
        // 集合里夹杂 ${...} 元素时，filter 掉占位符，保留正常元素
        Set<Long> result = BotConfig.parseLongSet("123,${PLACEHOLDER},456");
        assertEquals(Set.of(123L, 456L), result);
    }

    @Test
    void parseLongSetParsesNormalValues() {
        assertEquals(Set.of(111L, 222L), BotConfig.parseLongSet("111,222"));
        assertEquals(Set.of(42L), BotConfig.parseLongSet("42"));
        assertEquals(Set.of(1L, 2L, 3L), BotConfig.parseLongSet(" 1 , 2 , 3 "));
    }

    // ── parseLongSafe 占位符 fallback ──

    @Test
    void parseLongSafeReturnsDefaultForPlaceholder() {
        assertEquals(0L, BotConfig.parseLongSafe("${BOT_QQ}", 0L));
        assertEquals(999L, BotConfig.parseLongSafe("${X}", 999L));
    }

    @Test
    void parseLongSafeReturnsDefaultForNullOrEmpty() {
        assertEquals(0L, BotConfig.parseLongSafe(null, 0L));
        assertEquals(0L, BotConfig.parseLongSafe("", 0L));
        assertEquals(0L, BotConfig.parseLongSafe("   ", 0L));
    }

    @Test
    void parseLongSafeReturnsDefaultForGarbage() {
        // 非占位符也非数字 → 用 default 而不是抛异常
        assertEquals(42L, BotConfig.parseLongSafe("not-a-number", 42L));
    }

    @Test
    void parseLongSafeParsesValidLong() {
        assertEquals(12345L, BotConfig.parseLongSafe("12345", 0L));
        assertEquals(0L, BotConfig.parseLongSafe("0", 99L));
    }
}
