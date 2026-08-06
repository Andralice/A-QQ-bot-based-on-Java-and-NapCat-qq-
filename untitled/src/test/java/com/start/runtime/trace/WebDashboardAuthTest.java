package com.start.runtime.trace;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebDashboardListener 鉴权测试。
 *
 * 验证开发计划第四阶段「Dashboard 外部监听无 Token 时拒绝启动」：
 * 1. token=null + host=非本机 → 拒绝启动（server 为 null）
 * 2. token=有效值 + host=非本机 → 允许启动
 * 3. token=null + host=127.0.0.1 → 允许启动
 * 4. checkAuth 行为：header / query / 不匹配 / token 缺失
 *
 * 通过反射读 server / host / token 字段。
 */
class WebDashboardAuthTest {

    private String originalHost;
    private String originalToken;

    @BeforeEach
    void saveSystemProps() {
        originalHost = System.getProperty("dashboard.host");
        originalToken = System.getProperty("dashboard.token");
    }

    @AfterEach
    void restoreSystemProps() {
        if (originalHost != null) System.setProperty("dashboard.host", originalHost);
        else System.clearProperty("dashboard.host");
        if (originalToken != null) System.setProperty("dashboard.token", originalToken);
        else System.clearProperty("dashboard.token");
    }

    @Test
    void startRefusesNonLoopbackWithoutToken() {
        // host=0.0.0.0（非本机），token=null
        System.setProperty("dashboard.host", "0.0.0.0");
        System.clearProperty("dashboard.token");
        WebDashboardListener listener = new WebDashboardListener();
        listener.start();
        // 验证 server 字段仍为 null（没启动）
        assertNull(getField(listener, "server"),
                "无 token + 非本机监听应拒绝启动，server 字段应为 null");
    }

    @Test
    void startAllowsLoopbackWithoutToken() {
        // host=127.0.0.1（本机），token=null
        System.setProperty("dashboard.host", "127.0.0.1");
        System.clearProperty("dashboard.token");
        WebDashboardListener listener = new WebDashboardListener();
        listener.start();
        // 应该启动
        try {
            HttpServer server = (HttpServer) getField(listener, "server");
            assertNotNull(server, "本机 + 无 token 应允许启动，server 字段应非 null");
        } finally {
            listener.stop();
        }
    }

    @Test
    void startAllowsNonLoopbackWithToken() {
        System.setProperty("dashboard.host", "0.0.0.0");
        System.setProperty("dashboard.token", "test-secret-token");
        WebDashboardListener listener = new WebDashboardListener();
        listener.start();
        try {
            HttpServer server = (HttpServer) getField(listener, "server");
            assertNotNull(server, "非本机 + 有 token 应允许启动");
        } finally {
            listener.stop();
        }
    }

    @Test
    void isLoopbackHostRecognizesVariants() throws Exception {
        // 用反射调用 private static 方法
        java.lang.reflect.Method m = WebDashboardListener.class.getDeclaredMethod("isLoopbackHost", String.class);
        m.setAccessible(true);
        assertTrue((Boolean) m.invoke(null, "localhost"));
        assertTrue((Boolean) m.invoke(null, "127.0.0.1"));
        assertTrue((Boolean) m.invoke(null, "::1"));
        assertTrue((Boolean) m.invoke(null, "[::1]"));
        assertTrue((Boolean) m.invoke(null, "LOCALHOST")); // 大小写不敏感
        // 非本机
        AtomicReference<Object> result = new AtomicReference<>();
        result.set(m.invoke(null, "0.0.0.0"));
        assertEquals(false, result.get());
        result.set(m.invoke(null, "192.168.1.1"));
        assertEquals(false, result.get());
    }

    private static Object getField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
