package com.start;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.BotConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Main pendingRequest 行为集成测试（默认跳过，需真实 MySQL）。
 *
 * 跑法：$env:RUN_DB_TESTS="true"; mvn test -Dtest=MainPendingFutureIntegrationTest
 *
 * 验证开发计划第四阶段：
 * 1. OneBot 断线时 pending Future 完成异常（failPendingRequests）
 * 2. OneBot API 超时后 pending Map 清理（whenComplete remove）
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class MainPendingFutureIntegrationTest {

    @BeforeAll
    static void setup() {
        // 确保 BotConfig 至少读取默认值
        BotConfig.getBaiLianApiKey();
    }

    @Test
    void onDisconnectCompletesPendingFuturesExceptionally() throws Exception {
        // ws://localhost:0 不真正连，只是构造
        Main bot = new Main(new URI("ws://localhost:0"));

        @SuppressWarnings("unchecked")
        Map<String, CompletableFuture<com.fasterxml.jackson.databind.JsonNode>> pending =
                (Map<String, CompletableFuture<com.fasterxml.jackson.databind.JsonNode>>)
                        getField(bot, "pendingRequests");
        assertNotNull(pending);

        CompletableFuture<com.fasterxml.jackson.databind.JsonNode> fakeFuture = new CompletableFuture<>();
        pending.put("fake-echo-1", fakeFuture);

        // 模拟断线
        bot.onClose(1006, "test close", true);

        // 验证 future 被 failPendingRequests 完成异常
        assertTrue(fakeFuture.isCompletedExceptionally(),
                "断线时 pending future 应被 completeExceptionally");
        try {
            fakeFuture.get(1, TimeUnit.SECONDS);
            fail("应抛异常");
        } catch (ExecutionException ee) {
            assertTrue(ee.getCause() instanceof IllegalStateException,
                    "cause 应为 IllegalStateException，实际=" + ee.getCause());
            assertTrue(ee.getCause().getMessage().contains("断开"));
        }
    }

    @Test
    void whenCompleteRemovesFromPendingMap() throws Exception {
        Main bot = new Main(new URI("ws://localhost:0"));

        @SuppressWarnings("unchecked")
        Map<String, CompletableFuture<com.fasterxml.jackson.databind.JsonNode>> pending =
                (Map<String, CompletableFuture<com.fasterxml.jackson.databind.JsonNode>>)
                        getField(bot, "pendingRequests");

        // 模拟一个 future 走完整 whenComplete 链
        CompletableFuture<com.fasterxml.jackson.databind.JsonNode> original = new CompletableFuture<>();
        pending.put("test-echo-cleanup", original);

        // 模拟 OneBot 返回响应：complete the original
        ObjectNode resp = (ObjectNode) com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        resp.put("status", "ok");
        resp.put("retcode", 0);
        original.complete(resp);

        // 等异步 whenComplete 链跑完
        Thread.sleep(200);
        assertEquals(null, pending.get("test-echo-cleanup"),
                "future 完成时 whenComplete 应从 pendingRequests 移除");
    }

    @Test
    void apiTimeoutAlsoRemovesFromPendingMap() throws Exception {
        Main bot = new Main(new URI("ws://localhost:0"));

        @SuppressWarnings("unchecked")
        Map<String, CompletableFuture<com.fasterxml.jackson.databind.JsonNode>> pending =
                (Map<String, CompletableFuture<com.fasterxml.jackson.databind.JsonNode>>)
                        getField(bot, "pendingRequests");

        // 直接模拟一个 future 走 whenComplete 链但完成异常（模拟 timeout）
        CompletableFuture<com.fasterxml.jackson.databind.JsonNode> original = new CompletableFuture<>();
        pending.put("test-echo-timeout", original);

        original.completeExceptionally(new java.util.concurrent.TimeoutException("test timeout"));

        Thread.sleep(200);
        assertEquals(null, pending.get("test-echo-timeout"),
                "future 异常完成时 whenComplete 也应从 pendingRequests 移除");
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getSuperclass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
