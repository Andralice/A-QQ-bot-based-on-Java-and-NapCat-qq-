package com.start.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实日志文件的 smoke test —— 写入真实文本、模拟增量扫描，
 * 验证修复后的 readNewErrors + parseErrorBlocks 链路在文件场景下也工作正确。
 *
 * 这里走反射调 private readNewErrors(Path, long)，因为它依赖实例状态
 * (lastFilePos) 不适合直接暴露。核心解析逻辑已由 ErrorMonitorServiceTest
 * 覆盖，本测试专注于 lastFilePos 推进和真实文件 I/O。
 */
class ErrorMonitorSmokeTest {

    @Test
    void readsErrorsFromRealFileAndAdvancesCursor(@TempDir Path tmp) throws Exception {
        Path logFile = tmp.resolve("qq-bot.log");
        String content = String.join("\n", Arrays.asList(
            "2026-07-16 10:00:00.000 [main] INFO  com.start.Main - 启动",
            "2026-07-16 10:00:01.123 [pool-1] DEBUG com.start.service.BaiLianService - 请求 Gemini API (Model: qwen-vl-max): {}",
            "2026-07-16 10:00:02.000 [ErrorMonitor-Thread] INFO com.start.service.ErrorMonitorService - 扫描中",
            "2026-07-16 10:00:03.000 [main] INFO  com.start.service.Y - 处理 NoSuchElementException 成功",
            "2026-07-16 10:00:04.000 [main] ERROR com.start.service.A - AI 调用失败",
            "java.io.IOException: connection reset",
            "    at com.start.service.A.method(A.java:123)",
            "    at com.start.service.B.method(B.java:456)",
            "2026-07-16 10:00:05.000 [main] WARN  com.start.service.Z - FATAL_RECOVERY 状态",
            "2026-07-16 10:00:06.000 [main] INFO  com.start.service.W - ERROR_CODE=42",
            "2026-07-16 10:00:07.000 [ErrorMonitor-Thread] ERROR com.start.service.ErrorMonitorService - 自激测试",
            "2026-07-16 10:00:08.000 [main] ERROR com.start.service.C - 第二次错误",
            "    at com.start.service.C.method(C.java:789)"
        ));
        Files.write(logFile, content.getBytes(StandardCharsets.UTF_8));

        // 反射构造一个最小可用的实例，绕过 BaiLianService 依赖
        ErrorMonitorService svc = new ErrorMonitorServiceForTest();
        Method m = ErrorMonitorService.class.getDeclaredMethod("readNewErrors", Path.class, long.class);
        m.setAccessible(true);

        // 第一次扫描：从头开始
        List<String> errors1 = (List<String>) m.invoke(svc, logFile, Files.size(logFile));
        // 应只识别 2 个真错误块：A 的"AI 调用失败"+ 堆栈，C 的"第二次错误"+ 堆栈
        assertEquals(2, errors1.size(), "应只识别 2 个真错误，实际：" + errors1);

        String blockA = errors1.get(0);
        assertTrue(blockA.contains("AI 调用失败"));
        assertTrue(blockA.contains("connection reset"));
        assertTrue(blockA.contains("A.java:123"));
        assertTrue(blockA.contains("B.java:456"));
        // 关键：以下四个**修复前会被误判**的样本都不应出现在结果里
        assertFalse(blockA.contains("Gemini"));
        assertFalse(blockA.contains("NoSuchElementException 成功"));
        assertFalse(blockA.contains("FATAL_RECOVERY"));
        assertFalse(blockA.contains("ERROR_CODE"));
        assertFalse(blockA.contains("ErrorMonitor-Thread"));
        assertFalse(blockA.contains("扫描中"));

        String blockC = errors1.get(1);
        assertTrue(blockC.contains("第二次错误"));
        assertTrue(blockC.contains("C.java:789"));

        // 第二次扫描：lastFilePos 已推进到文件末尾，应无新错误
        List<String> errors2 = (List<String>) m.invoke(svc, logFile, Files.size(logFile));
        assertTrue(errors2.isEmpty(), "第二次扫描应为空，实际：" + errors2);

        // 模拟追加新日志，再扫一次
        String appended = "\n2026-07-16 10:00:09.000 [main] ERROR com.start.service.D - 新错误";
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "rw")) {
            raf.seek(raf.length());
            raf.write(appended.getBytes(StandardCharsets.UTF_8));
        }
        List<String> errors3 = (List<String>) m.invoke(svc, logFile, Files.size(logFile));
        assertEquals(1, errors3.size());
        assertTrue(errors3.get(0).contains("新错误"));
    }

    @Test
    void oldLogFileWithoutDatePrefixIsSilentlyIgnored(@TempDir Path tmp) throws Exception {
        // 模拟"老日志格式"——只有时间没有日期——正则不匹配，应全部忽略
        Path logFile = tmp.resolve("old.log");
        String content = String.join("\n", Arrays.asList(
            "10:00:00.000 [main] ERROR com.start.A - 老格式错误",
            "10:00:01.000 [main] INFO  com.start.B - 业务消息含 Exception"
        ));
        Files.write(logFile, content.getBytes(StandardCharsets.UTF_8));

        ErrorMonitorService svc = new ErrorMonitorServiceForTest();
        Method m = ErrorMonitorService.class.getDeclaredMethod("readNewErrors", Path.class, long.class);
        m.setAccessible(true);

        List<String> errors = (List<String>) m.invoke(svc, logFile, Files.size(logFile));
        assertTrue(errors.isEmpty(),
            "旧格式日志（无日期前缀）应被安全忽略，不应误判为错误。实际：" + errors);
    }

    /** 绕过 BaiLianService 依赖的测试桩。 */
    private static class ErrorMonitorServiceForTest extends ErrorMonitorService {
        ErrorMonitorServiceForTest() {
            super(null);
        }
    }
}
