package com.start.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.Callable;

/**
 * 统一的百炼 API 重试执行器
 * <p>
 * 替代 BaiLianService 中散落的 3 处局部重试逻辑（HTTP 主请求 / Tool follow-up / Long JSON 重试），
 * 统一覆盖可重试异常：
 * <ul>
 *   <li>网络层：HttpTimeoutException、ConnectException、IOException</li>
 *   <li>HTTP 状态：5xx、429（自动转为 HttpRetryableException）</li>
 * </ul>
 * 不可重试的异常（4xx 非 429、业务逻辑错误、JSON 解析错误）原样向上抛出，由调用方决定。
 * </p>
 *
 * 退避策略：线性 1s/2s/3s（与现有行为一致，未来可加指数退避 + jitter）
 */
public final class BailianRetryExecutor {
    private static final Logger logger = LoggerFactory.getLogger(BailianRetryExecutor.class);

    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVER_ERROR_MIN = 500;

    private BailianRetryExecutor() {}

    /**
     * 执行可重试操作。遇到不可重试异常立即抛出；可重试异常按线性退避重试。
     *
     * @param operation 操作名称（用于日志）
     * @param callable  实际调用
     * @param maxRetries 最大重试次数（不含首次）
     * @return 调用结果
     * @throws Exception 最终失败时抛出最后一次的异常
     */
    public static <T> T execute(String operation, Callable<T> callable, int maxRetries) throws Exception {
        Exception lastEx = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return callable.call();
            } catch (Exception e) {
                lastEx = e;
                if (!isRetryable(e)) {
                    throw e;
                }
                if (attempt >= maxRetries) {
                    logger.warn("❌ {} 已重试 {} 次仍失败: {}", operation, maxRetries, e.getMessage());
                    throw e;
                }
                long sleepMs = 1000L * (attempt + 1);
                logger.warn("⚠️ {} 第 {} 次失败，{}ms 后重试: {}", operation, attempt + 1, sleepMs, e.getMessage());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(operation + " 重试被中断", ie);
                }
            }
        }
        // 理论上不会到这里（最后一次 attempt 失败时已 throw）
        throw lastEx;
    }

    /**
     * 判定异常是否可重试
     */
    public static boolean isRetryable(Throwable e) {
        if (e instanceof HttpTimeoutException) return true;
        if (e instanceof ConnectException) return true;
        if (e instanceof IOException) return true;
        if (e instanceof HttpRetryableException) return true;
        return false;
    }

    /**
     * HTTP 请求专用：自动把 5xx/429 响应转为可重试异常
     *
     * @param operation 操作名称
     * @param request   实际 HTTP 请求
     * @param maxRetries 最大重试次数
     * @return 成功响应（statusCode < 500 且 != 429）
     */
    public static HttpResponse<String> executeHttp(String operation,
                                                   Callable<HttpResponse<String>> request,
                                                   int maxRetries) throws Exception {
        return execute(operation, () -> {
            HttpResponse<String> resp = request.call();
            int code = resp.statusCode();
            if (code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR_MIN) {
                String body = resp.body() != null
                        ? resp.body().substring(0, Math.min(200, resp.body().length()))
                        : "";
                throw new HttpRetryableException("HTTP " + code + ": " + body, code);
            }
            return resp;
        }, maxRetries);
    }

    /**
     * 标记 5xx/429 响应为可重试异常
     */
    public static class HttpRetryableException extends RuntimeException {
        private final int statusCode;

        public HttpRetryableException(String msg, int statusCode) {
            super(msg);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
