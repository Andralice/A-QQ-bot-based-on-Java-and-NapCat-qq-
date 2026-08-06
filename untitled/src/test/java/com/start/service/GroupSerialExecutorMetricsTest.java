package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GroupSerialExecutor 监控指标测试：覆盖 submitted / completed / expired / 耗时记录。
 */
class GroupSerialExecutorMetricsTest {

    @Test
    void submittedAndCompletedCountersTrackExecution() throws Exception {
        GroupSerialExecutor executor = new GroupSerialExecutor(2, 5_000);
        try {
            CountDownLatch done = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                executor.execute("group-A", () -> {
                    try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    done.countDown();
                });
            }
            assertTrue(done.await(2, TimeUnit.SECONDS));
            // 业务跑完 → done.countDown() → wrapper finally 跑 tasksCompleted++
            // done.await() 看到 done=0 立即返回，但 finally 还在跑，等一下确保全跑完
            Thread.sleep(200);

            GroupSerialExecutor.ExecutorMetrics m = executor.getMetrics();
            assertEquals(3, m.tasksSubmitted, "submitted 应该 +3");
            assertEquals(3, m.tasksCompleted, "completed 应该 +3");
            assertEquals(0, m.tasksRejected, "正常提交不应被拒");
            assertEquals(0, m.tasksExpired, "5s 超时窗口内不应过期");
            assertEquals(0, m.tasksActive, "执行完毕应回到 0");
            assertTrue(m.totalExecutionTimeMs >= 60, "3 个 20ms 任务，total >= 60ms");
            assertTrue(m.maxExecutionTimeMs >= 20, "max >= 单个任务耗时");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void expiredTasksAreCounted() throws Exception {
        GroupSerialExecutor executor = new GroupSerialExecutor(1, 50);
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            // 第一个任务占用 worker
            executor.execute("group-X", () -> {
                firstStarted.countDown();
                try { releaseFirst.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            // 第二个任务排进队列
            executor.execute("group-X", () -> {});
            // 等过期（50ms < 释放时间）
            Thread.sleep(150);
            releaseFirst.countDown();
            // 等待 worker 回到空闲
            Thread.sleep(200);

            GroupSerialExecutor.ExecutorMetrics m = executor.getMetrics();
            assertTrue(m.tasksExpired >= 1, "至少 1 个任务过期，实际=" + m.tasksExpired);
            assertEquals(2, m.tasksSubmitted, "submitted 应该 +2（第一个+第二个）");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void differentGroupsHaveIsolatedQueues() throws Exception {
        GroupSerialExecutor executor = new GroupSerialExecutor(2, 5_000);
        try {
            AtomicInteger maxActive = new AtomicInteger();
            AtomicInteger active = new AtomicInteger();
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);

            for (String g : new String[]{"g1", "g2"}) {
                executor.execute(g, () -> {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    started.countDown();
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { active.decrementAndGet(); }
                });
            }
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(2, maxActive.get(), "两个不同群应能并行");

            // release 前检查：两个群都在 worker 池里运行中
            GroupSerialExecutor.ExecutorMetrics mDuring = executor.getMetrics();
            assertEquals(2, mDuring.groupQueuesCount, "运行中应记录 2 个活跃群队列");
            assertEquals(0, mDuring.groupQueuesTotalSize, "运行中无积压");
            assertEquals(2, mDuring.tasksActive, "应有 2 个活跃任务");

            release.countDown();
            // release 后业务跑完 → finally 跑 tasksCompleted++
            Thread.sleep(300);

            // release 后：群队列被清空
            GroupSerialExecutor.ExecutorMetrics mAfter = executor.getMetrics();
            assertEquals(0, mAfter.groupQueuesCount, "执行完毕后群队列应清空");
            assertEquals(2, mAfter.tasksCompleted, "completed 应累计 2");
        } finally {
            executor.shutdown();
        }
    }
}
