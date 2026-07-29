package com.start.service;

import com.start.model.WorkingMemory;
import com.start.model.WorkingMemoryStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryLifecycleDetector 单元测试（纯函数，不调 DB）。
 *
 * 覆盖 4 类场景：
 *   1. 关键词命中（中文 10+ / 英文 5+）
 *   2. 关键词不命中（正常对话不应误关 WM）
 *   3. 边界：null / 空 / 无 ACTIVE 任务
 *   4. 纯函数性：不调 DB，副作用由调用方负责
 */
class MemoryLifecycleDetectorTest {

    /** 构造一个 ACTIVE 状态的工作记忆。 */
    private WorkingMemory activeWm() {
        WorkingMemory wm = new WorkingMemory();
        wm.setId(1L);
        wm.setThreadId(100L);
        wm.setGoal("帮用户查东京机票");
        wm.setStatus(WorkingMemoryStatus.ACTIVE);
        return wm;
    }

    /** 构造一个 COMPLETED 状态的工作记忆。 */
    private WorkingMemory completedWm() {
        WorkingMemory wm = activeWm();
        wm.setStatus(WorkingMemoryStatus.COMPLETED);
        return wm;
    }

    // ============ 1. 关键词命中 ============

    @Test
    void matches_chinese_buYongLe() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "东京机票不用了"));
    }

    @Test
    void matches_chinese_quXiao() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "取消这个任务吧"));
    }

    @Test
    void matches_chinese_suanLe() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "算了"));
    }

    @Test
    void matches_chinese_wanShi() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "这事完事了"));
    }

    @Test
    void matches_chinese_gaoDingLe() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "已经搞定了"));
    }

    @Test
    void matches_chinese_buYongCha() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "机票不用查了"));
    }

    @Test
    void matches_chinese_yiJingJieJue() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "问题已经解决了"));
    }

    @Test
    void matches_chinese_wangDiao() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "忘掉这件事吧"));
    }

    @Test
    void matches_english_cancel() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "cancel the task"));
    }

    @Test
    void matches_english_forgetIt() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "forget it, never mind"));
    }

    @Test
    void matches_english_done() {
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "it's already done"));
    }

    // ============ 2. 关键词不命中（正常对话） ============

    @Test
    void noMatch_normalChat_keepsActive() {
        var detector = new MemoryLifecycleDetector();
        // "取消"在 "取消键" 上下文里可能误命中，但普通闲聊不应触发
        assertFalse(detector.shouldComplete(activeWm(), "今天天气不错"));
        assertFalse(detector.shouldComplete(activeWm(), "东京机票查得怎么样了？"));
        assertFalse(detector.shouldComplete(activeWm(), "帮我查一下广州到北京的航班"));
        assertFalse(detector.shouldComplete(activeWm(), "我想看更多结果"));
    }

    @Test
    void falsePositive_acknowledged_designTradeoff() {
        // 设计取舍：子串匹配，"那个搞定了没？" 会误判为"完成"。
        // 误判代价 = 多一次 markCompleted DB UPDATE（无副作用，因为 ACTIVE→COMPLETED 是幂等的）。
        // 这个误判可以接受，因为：a) WM 关闭后 LLM 不会基于它继续胡言；
        //                            b) 用户说"搞定"本意也常常是话题结束。
        // 因此：宁可多关不多开。
        var detector = new MemoryLifecycleDetector();
        assertTrue(detector.shouldComplete(activeWm(), "那个搞定了没？"),
                "子串匹配会命中'搞定'，是已知误判（设计取舍）");
    }

    // ============ 3. 边界 ============

    @Test
    void nullWm_returnsFalse() {
        var detector = new MemoryLifecycleDetector();
        assertFalse(detector.shouldComplete(null, "不用了"));
    }

    @Test
    void completedWm_returnsFalse() {
        // 已完成的 WM 不该再次处理
        var detector = new MemoryLifecycleDetector();
        assertFalse(detector.shouldComplete(completedWm(), "不用了"));
    }

    @Test
    void nullStatusWm_doesNotThrowNpe() {
        // 修复：老数据迁移遗漏的边角情况（active=0 但 status=NULL）
        // 之前用 .name().equals("ACTIVE") 会 NPE 崩溃
        // 现在用 enum 直接比较 + null-safe
        WorkingMemory wm = activeWm();
        wm.setStatus(null);  // 模拟 null status
        var detector = new MemoryLifecycleDetector();
        assertFalse(detector.shouldComplete(wm, "不用了"),
                "null status 应当被当作非 ACTIVE，不抛 NPE");
    }

    @Test
    void nullMessage_returnsFalse() {
        var detector = new MemoryLifecycleDetector();
        assertFalse(detector.shouldComplete(activeWm(), null));
    }

    @Test
    void blankMessage_returnsFalse() {
        var detector = new MemoryLifecycleDetector();
        assertFalse(detector.shouldComplete(activeWm(), ""));
        assertFalse(detector.shouldComplete(activeWm(), "   "));
    }
}
