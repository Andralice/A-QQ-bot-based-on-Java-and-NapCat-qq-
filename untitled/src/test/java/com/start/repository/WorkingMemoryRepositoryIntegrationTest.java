package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.WorkingMemory;
import com.start.model.WorkingMemoryStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkingMemoryRepository 集成测试（默认跳过，需要真实 MySQL）。
 *
 * 跑法：$env:RUN_DB_TESTS="true"; mvn test -Dtest=WorkingMemoryRepositoryIntegrationTest
 *
 * 验证：白板模型的"白板"语义 — 只有 ACTIVE + 未过期才被 findActiveByThread 查到；
 * markCompleted 立刻让它从 findActiveByThread 消失（DB 行保留）；
 * markExpired 把所有过期 ACTIVE 批量标 EXPIRED；
 * 同 Thread 再次 save() 自动 deactivate 旧 ACTIVE。
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class WorkingMemoryRepositoryIntegrationTest {

    private static WorkingMemoryRepository repo;
    private long testThreadId;

    @BeforeAll
    static void setUpAll() {
        repo = new WorkingMemoryRepository(DatabaseConfig.getDataSource());
    }

    @BeforeEach
    void setUp() {
        // 用 nanoTime 作 threadId 避免冲突
        testThreadId = System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        // 清理：把所有该 threadId 的记录都标 COMPLETED
        repo.deactivateByThread(testThreadId);
    }

    private WorkingMemory makeWm(String goal, Instant expiresAt) {
        WorkingMemory wm = new WorkingMemory();
        wm.setThreadId(testThreadId);
        wm.setGoal(goal);
        wm.setContextSummary("test context");
        wm.setStatus(WorkingMemoryStatus.ACTIVE);
        wm.setActive(true);
        wm.setExpiresAt(expiresAt);
        return wm;
    }

    @Test
    void save_thenFindActive_returnsIt() {
        WorkingMemory wm = makeWm("task-1", Instant.now().plus(24, ChronoUnit.HOURS));
        repo.save(wm);

        WorkingMemory found = repo.findActiveByThread(testThreadId);
        assertNotNull(found, "刚 save 的 ACTIVE WM 应该被查到");
        assertEquals("task-1", found.getGoal());
        assertEquals(WorkingMemoryStatus.ACTIVE, found.getStatus());
    }

    @Test
    void expiredWm_notFoundByFindActive() {
        // expiresAt = 1 小时前 → 已过期
        WorkingMemory wm = makeWm("expired-task", Instant.now().minus(1, ChronoUnit.HOURS));
        repo.save(wm);

        WorkingMemory found = repo.findActiveByThread(testThreadId);
        assertNull(found, "过期的 WM 不该被 findActiveByThread 查到");
    }

    @Test
    void markCompleted_removedFromFindActive() {
        WorkingMemory wm = makeWm("to-complete", Instant.now().plus(24, ChronoUnit.HOURS));
        repo.save(wm);

        boolean ok = repo.markCompleted(wm.getId());
        assertTrue(ok, "markCompleted 应该返回 true");

        WorkingMemory found = repo.findActiveByThread(testThreadId);
        assertNull(found, "COMPLETED 的 WM 不该被 findActiveByThread 查到");
    }

    @Test
    void markCompleted_twice_returnsFalseSecondTime() {
        WorkingMemory wm = makeWm("twice-complete", Instant.now().plus(24, ChronoUnit.HOURS));
        repo.save(wm);

        assertTrue(repo.markCompleted(wm.getId()));
        assertFalse(repo.markCompleted(wm.getId()), "第二次 markCompleted 应该返回 false（已不是 ACTIVE）");
    }

    @Test
    void secondSave_autoDeactivatesFirst() {
        WorkingMemory wm1 = makeWm("first", Instant.now().plus(24, ChronoUnit.HOURS));
        repo.save(wm1);

        WorkingMemory wm2 = makeWm("second", Instant.now().plus(24, ChronoUnit.HOURS));
        repo.save(wm2);

        // 旧 WM 应该被 deactivate
        WorkingMemory found = repo.findActiveByThread(testThreadId);
        assertNotNull(found);
        assertEquals("second", found.getGoal(), "第二次 save 后只有 second 是 ACTIVE");
        assertNotEquals(wm1.getId(), found.getId());
    }

    @Test
    void markExpired_sweepsExpiredRows() {
        // 存 2 个：一个过期一个未过期
        WorkingMemory expired = makeWm("expired-1", Instant.now().minus(2, ChronoUnit.HOURS));
        repo.save(expired);

        WorkingMemory fresh = makeWm("fresh", Instant.now().plus(24, ChronoUnit.HOURS));
        repo.save(fresh);
        // fresh 触发 deactivateByThread → expired 也被标 COMPLETED（不是 EXPIRED）
        // 所以 markExpired 应该返回 0
        // 换个 threadId 测 markExpired：
        long isolatedThread = System.nanoTime();
        WorkingMemory iso = new WorkingMemory();
        iso.setThreadId(isolatedThread);
        iso.setGoal("iso-expired");
        iso.setStatus(WorkingMemoryStatus.ACTIVE);
        iso.setActive(true);
        iso.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        repo.save(iso);

        int swept = repo.markExpired();
        assertTrue(swept >= 1, "至少 sweep 了 1 条过期记录");
    }

    @Test
    void deactivateByThread_closesAllActive() {
        WorkingMemory wm1 = makeWm("a", Instant.now().plus(24, ChronoUnit.HOURS));
        repo.save(wm1);

        // 模拟 Thread 关闭
        repo.deactivateByThread(testThreadId);

        assertNull(repo.findActiveByThread(testThreadId),
                "deactivateByThread 后该 Thread 不再有 ACTIVE WM");
    }
}
