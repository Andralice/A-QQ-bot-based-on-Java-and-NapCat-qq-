package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.RecurringTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RecurringTask 租约 E2E 测试（默认跳过，需真实 MySQL）。
 *
 * 跑法：$env:RUN_DB_TESTS="true"; mvn test -Dtest=RecurringTaskClaimIntegrationTest
 *
 * 验证开发计划第一阶段 1.2 的核心诉求（周期任务版）：
 * 1. 两个调度线程同时执行时只能成功 claim 一次
 * 2. claim 后 release 能再 claim（发送失败重试场景）
 * 3. claim 后 markFired 不能 claim
 * 4. claim 后 30 分钟内不能重 claim
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class RecurringTaskClaimIntegrationTest {

    private static RecurringTaskRepository repo;
    private static final String TEST_USER = "test-claim-user";
    private static final String TEST_GROUP = "test-claim-group";

    @BeforeAll
    static void setUpAll() {
        repo = new RecurringTaskRepository(DatabaseConfig.getDataSource());
    }

    @AfterEach
    void tearDown() {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM recurring_tasks WHERE user_id = ?")) {
            ps.setString(1, TEST_USER);
            ps.executeUpdate();
        } catch (Exception e) {
            // 清理失败不致命
        }
    }

    private RecurringTask makeDueTask() throws Exception {
        RecurringTask t = new RecurringTask();
        t.setUserId(TEST_USER);
        t.setGroupId(TEST_GROUP);
        t.setTaskName("test-claim-task-" + UUID.randomUUID());
        t.setCronExpr("0 9 * * *");
        t.setTriggerPrompt("test prompt");
        t.setExpireDays(7);
        t.setEnabled(true);
        // 触发时间设为 1 分钟前（已到期）
        t.setNextFireAt(LocalDateTime.now().minusMinutes(1));
        repo.insert(t);
        return t;
    }

    @Test
    void twoThreadsConcurrentlyClaimOnlyOneSucceeds() throws Exception {
        RecurringTask task = makeDueTask();
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger();

            for (int i = 0; i < 2; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        if (repo.claimDueTask(task.getId())) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(3, TimeUnit.SECONDS), "两个线程都应完成");
            assertEquals(1, successCount.get(), "并发 claim 只能 1 个成功");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void reClaimWithin30MinutesFails() throws Exception {
        RecurringTask task = makeDueTask();
        assertTrue(repo.claimDueTask(task.getId()), "首次 claim 应成功");
        assertFalse(repo.claimDueTask(task.getId()), "30 分钟内不能重 claim");
    }

    @Test
    void releaseAllowsReclaim() throws Exception {
        RecurringTask task = makeDueTask();
        assertTrue(repo.claimDueTask(task.getId()), "首次 claim 应成功");
        repo.releaseTaskClaim(task.getId());
        assertTrue(repo.claimDueTask(task.getId()), "release 后能再 claim");
    }

    @Test
    void markFiredPreventsReclaim() throws Exception {
        RecurringTask task = makeDueTask();
        assertTrue(repo.claimDueTask(task.getId()));
        repo.markFired(task.getId(), LocalDateTime.now().plusDays(1));
        assertFalse(repo.claimDueTask(task.getId()), "markFired 后不能再 claim");
    }
}
