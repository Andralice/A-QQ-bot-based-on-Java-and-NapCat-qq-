package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.LongTermMemory;
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
 * LongTermMemory 租约 E2E 测试（默认跳过，需真实 MySQL）。
 *
 * 跑法：$env:RUN_DB_TESTS="true"; mvn test -Dtest=LongTermMemoryClaimIntegrationTest
 *
 * 验证开发计划第一阶段 1.2 的核心诉求：
 * 1. 两个调度线程同时执行时只能成功 claim 一次
 * 2. claim 后 release 能再 claim（发送失败重试场景）
 * 3. claim 后 markTriggered 不能 claim
 * 4. claim 后 30 分钟内不能重 claim
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class LongTermMemoryClaimIntegrationTest {

    private static LongTermMemoryRepository repo;
    private static final String TEST_USER = "test-claim-user";
    private static final String TEST_GROUP = "test-claim-group";

    @BeforeAll
    static void setUpAll() {
        repo = new LongTermMemoryRepository(DatabaseConfig.getDataSource());
    }

    @AfterEach
    void tearDown() {
        // 清理：用 unique content 前缀删测试数据
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM long_term_memories WHERE user_id = ?")) {
            ps.setString(1, TEST_USER);
            ps.executeUpdate();
        } catch (Exception e) {
            // 清理失败不致命
        }
    }

    private LongTermMemory makeDueEvent() throws Exception {
        LongTermMemory m = new LongTermMemory();
        m.setUserId(TEST_USER);
        m.setGroupId(TEST_GROUP);
        m.setContent("test-claim-" + UUID.randomUUID());
        m.setMemoryType("event");
        m.setImportance(1);
        // 触发时间设为 1 分钟前（已到期）
        m.setTriggerAt(LocalDateTime.now().minusMinutes(1));
        m.setTriggered(false);
        repo.insert(m);
        return m;
    }

    @Test
    void twoThreadsConcurrentlyClaimOnlyOneSucceeds() throws Exception {
        LongTermMemory event = makeDueEvent();
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger();

            for (int i = 0; i < 2; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        if (repo.claimDueEvent(event.getId())) {
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
        LongTermMemory event = makeDueEvent();
        assertTrue(repo.claimDueEvent(event.getId()), "首次 claim 应成功");
        assertFalse(repo.claimDueEvent(event.getId()), "30 分钟内不能重 claim");
    }

    @Test
    void releaseAllowsReclaim() throws Exception {
        LongTermMemory event = makeDueEvent();
        assertTrue(repo.claimDueEvent(event.getId()), "首次 claim 应成功");
        repo.releaseEventClaim(event.getId());
        assertTrue(repo.claimDueEvent(event.getId()), "release 后能再 claim");
    }

    @Test
    void markTriggeredPreventsReclaim() throws Exception {
        LongTermMemory event = makeDueEvent();
        assertTrue(repo.claimDueEvent(event.getId()));
        repo.markTriggered(event.getId());
        assertFalse(repo.claimDueEvent(event.getId()), "markTriggered 后不能再 claim");
    }
}
