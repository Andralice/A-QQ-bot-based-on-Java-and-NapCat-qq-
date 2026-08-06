package com.start.service;

import com.start.model.LongTermMemory;
import com.start.model.RecurringTask;
import com.start.repository.LongTermMemoryRepository;
import com.start.repository.RecurringTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScheduleExecutor 单元测试：用 Spy repo + 函数式 send 模拟，验证发送失败时释放租约、
 * 成功时标记触发，不依赖真实 DB 或 WebSocket。
 *
 * 覆盖开发计划第四阶段「调度消息发送失败后不标记完成」。
 */
class ScheduleExecutorTest {

    /** Spy：截留 claim/release/mark 调用计数 + 模拟失败注入。 */
    static class SpyEventRepo extends LongTermMemoryRepository {
        final AtomicInteger claimCount = new AtomicInteger();
        final AtomicInteger releaseCount = new AtomicInteger();
        final AtomicInteger markCount = new AtomicInteger();
        boolean nextClaimResult = true;

        SpyEventRepo() { super(null); }

        @Override
        public boolean claimDueEvent(long id) {
            claimCount.incrementAndGet();
            return nextClaimResult;
        }
        @Override
        public void releaseEventClaim(long id) {
            releaseCount.incrementAndGet();
        }
        @Override
        public void markTriggered(long id) {
            markCount.incrementAndGet();
        }
    }

    static class SpyTaskRepo extends RecurringTaskRepository {
        final AtomicInteger claimCount = new AtomicInteger();
        final AtomicInteger releaseCount = new AtomicInteger();
        final AtomicInteger markCount = new AtomicInteger();
        boolean nextClaimResult = true;
        final AtomicReference<LocalDateTime> lastMarkedNextFire = new AtomicReference<>();

        SpyTaskRepo() { super(null); }

        @Override
        public boolean claimDueTask(long id) {
            claimCount.incrementAndGet();
            return nextClaimResult;
        }
        @Override
        public void releaseTaskClaim(long id) {
            releaseCount.incrementAndGet();
        }
        @Override
        public void markFired(long id, LocalDateTime nextFireAt) {
            markCount.incrementAndGet();
            lastMarkedNextFire.set(nextFireAt);
        }
    }

    private LongTermMemory makeEvent(long id, String groupId) {
        LongTermMemory m = new LongTermMemory();
        m.setId(id);
        m.setContent("test event");
        m.setUserId("12345");
        m.setGroupId(groupId);
        return m;
    }

    private RecurringTask makeTask(long id, String groupId) {
        RecurringTask t = new RecurringTask();
        t.setId(id);
        t.setTaskName("test task");
        t.setUserId("12345");
        t.setGroupId(groupId);
        t.setTriggerPrompt("do it");
        t.setCronExpr("0 9 * * *");
        return t;
    }

    // ========== 定时事件测试 ==========

    @Test
    void eventSendFailureReleasesClaim() {
        SpyEventRepo spy = new SpyEventRepo();
        LongTermMemory event = makeEvent(100L, "200");
        Supplier<String> generator = () -> "hello world";
        // 模拟 sendGroupReply 返回 false（发送失败）
        BiFunction<Long, String, Boolean> failingSend = (id, msg) -> false;

        boolean result = ScheduleExecutor.executeDueEvent(
                spy, event, generator, failingSend, failingSend);

        assertFalse(result, "发送失败应返回 false");
        assertEquals(1, spy.claimCount.get(), "应 claim 一次");
        assertEquals(1, spy.releaseCount.get(), "发送失败应 release 一次");
        assertEquals(0, spy.markCount.get(), "发送失败不应 markTriggered");
    }

    @Test
    void eventSendSuccessMarksTriggered() {
        SpyEventRepo spy = new SpyEventRepo();
        LongTermMemory event = makeEvent(101L, "200");
        Supplier<String> generator = () -> "hello";
        BiFunction<Long, String, Boolean> okSend = (id, msg) -> true;

        boolean result = ScheduleExecutor.executeDueEvent(
                spy, event, generator, okSend, okSend);

        assertTrue(result, "发送成功应返回 true");
        assertEquals(1, spy.claimCount.get());
        assertEquals(0, spy.releaseCount.get(), "成功不应 release");
        assertEquals(1, spy.markCount.get(), "成功应 markTriggered");
    }

    @Test
    void eventClaimFailureSkipsExecution() {
        SpyEventRepo spy = new SpyEventRepo();
        spy.nextClaimResult = false;  // 模拟被别的实例抢了
        LongTermMemory event = makeEvent(102L, "200");
        AtomicInteger sendCalled = new AtomicInteger();
        BiFunction<Long, String, Boolean> trackingSend = (id, msg) -> {
            sendCalled.incrementAndGet();
            return true;
        };

        boolean result = ScheduleExecutor.executeDueEvent(
                spy, event, () -> "x", trackingSend, trackingSend);

        assertFalse(result);
        assertEquals(1, spy.claimCount.get());
        assertEquals(0, spy.releaseCount.get(), "claim 失败不应 release");
        assertEquals(0, spy.markCount.get());
        assertEquals(0, sendCalled.get(), "claim 失败不应调 send");
    }

    @Test
    void eventPrivateSendIsUsedWhenGroupIdIsNull() {
        SpyEventRepo spy = new SpyEventRepo();
        LongTermMemory event = makeEvent(103L, null);  // 无 group_id → 走私聊
        AtomicInteger groupSendCalls = new AtomicInteger();
        AtomicInteger privateSendCalls = new AtomicInteger();
        BiFunction<Long, String, Boolean> groupSend = (id, msg) -> {
            groupSendCalls.incrementAndGet();
            return true;
        };
        BiFunction<Long, String, Boolean> privateSend = (id, msg) -> {
            privateSendCalls.incrementAndGet();
            return true;
        };

        boolean result = ScheduleExecutor.executeDueEvent(
                spy, event, () -> "hi", groupSend, privateSend);

        assertTrue(result);
        assertEquals(0, groupSendCalls.get(), "无 group_id 不应调群发");
        assertEquals(1, privateSendCalls.get(), "应调私聊一次");
    }

    // ========== 周期任务测试 ==========

    @Test
    void taskSendFailureReleasesClaim() {
        SpyTaskRepo spy = new SpyTaskRepo();
        RecurringTask task = makeTask(200L, "300");
        BiFunction<Long, String, Boolean> failingSend = (id, msg) -> false;

        boolean result = ScheduleExecutor.executeDueTask(
                spy, task, () -> "hello", failingSend, failingSend,
                () -> LocalDateTime.now().plusDays(1));

        assertFalse(result);
        assertEquals(1, spy.claimCount.get());
        assertEquals(1, spy.releaseCount.get());
        assertEquals(0, spy.markCount.get(), "发送失败不应 markFired");
    }

    @Test
    void taskSendSuccessMarksFiredWithNextFireTime() {
        SpyTaskRepo spy = new SpyTaskRepo();
        RecurringTask task = makeTask(201L, "300");
        LocalDateTime nextFire = LocalDateTime.of(2027, 1, 1, 9, 0);
        BiFunction<Long, String, Boolean> okSend = (id, msg) -> true;

        boolean result = ScheduleExecutor.executeDueTask(
                spy, task, () -> "hi", okSend, okSend, () -> nextFire);

        assertTrue(result);
        assertEquals(1, spy.markCount.get());
        assertEquals(nextFire, spy.lastMarkedNextFire.get(),
                "markFired 应传入 nextFireSupplier 返回的时间");
    }

    @Test
    void taskClaimFailureSkipsExecution() {
        SpyTaskRepo spy = new SpyTaskRepo();
        spy.nextClaimResult = false;
        RecurringTask task = makeTask(202L, "300");

        boolean result = ScheduleExecutor.executeDueTask(
                spy, task, () -> "x", (id, msg) -> true, (id, msg) -> true,
                () -> LocalDateTime.now());

        assertFalse(result);
        assertEquals(0, spy.markCount.get());
    }
}
