package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ErrorMonitorService 日志解析单测 —— 重点验证修复后不再误判。
 *
 * 修复前：line.contains("ERROR") / "Exception" / "FATAL" 三个 substring 匹配，
 * 会被线程名（ErrorMonitor-Thread 含 Error）、业务消息里的 Exception 类名、
 * 任何含 ERROR/Exception 字样的非错误日志误判。
 *
 * 修复后：必须严格匹配 logback 格式 "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level ..."
 * 才算日志行，且只取 ERROR/FATAL 级别，自身线程永远忽略。
 */
class ErrorMonitorServiceTest {

    private static final String HDR = "2026-07-16 10:30:00.123 ";

    @Test
    void capturesRealErrorLevelLine() {
        List<String> lines = Arrays.asList(
            HDR + "[main] ERROR com.start.service.X - AI 调用失败"
        );
        List<String> errors = ErrorMonitorService.parseErrorBlocks(lines);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("ERROR"));
        assertTrue(errors.get(0).contains("AI 调用失败"));
    }

    @Test
    void capturesFatalLevelLine() {
        List<String> lines = Arrays.asList(
            HDR + "[main] FATAL com.start.service.X - 致命错误"
        );
        List<String> errors = ErrorMonitorService.parseErrorBlocks(lines);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("FATAL"));
    }

    @Test
    void ignoresInfoWarnDebugTrace() {
        List<String> lines = Arrays.asList(
            HDR + "[main] INFO  com.start.service.X - normal info",
            HDR + "[main] WARN  com.start.service.X - a warning here",
            HDR + "[main] DEBUG com.start.service.X - debug detail",
            HDR + "[main] TRACE com.start.service.X - trace noise"
        );
        List<String> errors = ErrorMonitorService.parseErrorBlocks(lines);
        assertTrue(errors.isEmpty(), "非 ERROR/FATAL 不应被捕获，实际抓到：" + errors);
    }

    /**
     * 报告里提到的"线程名 ErrorMonitor-Thread 包含 Error 被误判"——修复后必须不再触发。
     * 这里测试三个角度：
     *   1) 自身线程 ERROR 日志：忽略
     *   2) 业务消息文本里出现 "Exception" 类名（如 NoSuchElementException）但级别是 INFO：忽略
     *   3) 业务消息文本里出现 "FATAL" 字样但级别是 INFO：忽略
     */
    @Test
    void noLongerFalsePositiveOnThreadName() {
        // 场景 1：自身线程的 ERROR 日志（不应被自己的扫描器捕获）
        String selfThreadError = HDR + "[ErrorMonitor-Thread] ERROR com.start.service.ErrorMonitorService - 审计 API 失败";
        // 场景 2：业务 INFO 日志里出现 Exception 类名
        String infoWithException = HDR + "[pool-1] INFO  com.start.service.Y - 处理 NoSuchElementException 成功";
        // 场景 3：业务 INFO 日志里出现 FATAL 关键字
        String infoWithFatal = HDR + "[pool-2] INFO  com.start.service.Z - 当前模式: FATAL_RECOVERY";
        // 场景 4：业务 INFO 日志里出现 ERROR 关键字
        String infoWithError = HDR + "[pool-3] INFO  com.start.service.W - 上次请求 ERROR_CODE=42";
        // 场景 5：自身线程 INFO 日志
        String selfThreadInfo = HDR + "[ErrorMonitor-Thread] INFO  com.start.service.ErrorMonitorService - 扫描中";

        List<String> errors = ErrorMonitorService.parseErrorBlocks(Arrays.asList(
            selfThreadError, infoWithException, infoWithFatal, infoWithError, selfThreadInfo
        ));
        assertTrue(errors.isEmpty(),
            "以上五种场景都不应被识别为错误，实际抓到：" + errors);
    }

    /**
     * 修复前：异常堆栈的每一行（"at com.xxx.Y.method(Y.java:123)"）单独被
     * line.contains("Exception") 触发，被当成 N 条独立错误。
     * 修复后：堆栈行必须跟在一条 ERROR 日志首行之后才被归入同一块。
     */
    @Test
    void multiLineStackTraceCollapsedIntoOneBlock() {
        List<String> lines = Arrays.asList(
            HDR + "[main] ERROR com.start.service.A - 调用失败",
            "java.io.IOException: connection reset",
            "    at com.start.service.A.method(A.java:123)",
            "    at com.start.service.B.method(B.java:456)",
            "    at java.base.Thread.run(Thread.java:1583)"
        );
        List<String> errors = ErrorMonitorService.parseErrorBlocks(lines);
        assertEquals(1, errors.size(), "整个堆栈应被合并为 1 个错误块");
        String block = errors.get(0);
        assertTrue(block.contains("调用失败"));
        assertTrue(block.contains("connection reset"));
        assertTrue(block.contains("A.java:123"));
        assertTrue(block.contains("B.java:456"));
    }

    /**
     * 异常堆栈后接另一条 INFO 日志时，堆栈归上一条，下一条 INFO 不应被吞掉也不应被误判。
     */
    @Test
    void stackTraceTerminatesAtNextLogLine() {
        List<String> lines = Arrays.asList(
            HDR + "[main] ERROR com.start.service.A - 调用失败",
            "    at com.start.service.A.method(A.java:1)",
            HDR + "[main] INFO  com.start.service.B - 恢复完成",
            HDR + "[main] ERROR com.start.service.C - 第二次错误"
        );
        List<String> errors = ErrorMonitorService.parseErrorBlocks(lines);
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).contains("调用失败"));
        assertTrue(errors.get(0).contains("A.java:1"));
        assertFalse(errors.get(0).contains("恢复完成"));
        assertTrue(errors.get(1).contains("第二次错误"));
    }

    /**
     * 多个错误块连续出现时按顺序全部捕获。
     */
    @Test
    void multipleErrorsCapturedInOrder() {
        List<String> lines = Arrays.asList(
            HDR + "[main] ERROR com.start.service.A - 错误 1",
            HDR + "[main] ERROR com.start.service.B - 错误 2",
            HDR + "[main] ERROR com.start.service.C - 错误 3"
        );
        List<String> errors = ErrorMonitorService.parseErrorBlocks(lines);
        assertEquals(3, errors.size());
        assertTrue(errors.get(0).contains("错误 1"));
        assertTrue(errors.get(1).contains("错误 2"));
        assertTrue(errors.get(2).contains("错误 3"));
    }

    /**
     * 完全没有时间戳前缀的杂项行（如裸 Exception 类名、堆栈、空行）应被忽略。
     * 修复前这些会被 contains("Exception") 误抓。
     */
    @Test
    void rawExceptionClassNameAloneIsNotAnError() {
        List<String> lines = Arrays.asList(
            "",
            "java.lang.NullPointerException",
            "    at com.start.X.method(X.java:1)",
            HDR + "[main] INFO  com.start.X - 这条是真业务消息",
            "throw new IllegalStateException(\"test\");"
        );
        List<String> errors = ErrorMonitorService.parseErrorBlocks(lines);
        assertTrue(errors.isEmpty(),
            "无时间戳前缀的 Exception 行不应被误判，实际抓到：" + errors);
    }

    /**
     * 边界：空输入。
     */
    @Test
    void emptyInputReturnsEmpty() {
        assertTrue(ErrorMonitorService.parseErrorBlocks(Collections.emptyList()).isEmpty());
    }

    /**
     * 边界：文件末尾有一个未闭合的错误块（lastFilePos 切到中间）—— 仍应 flush。
     */
    @Test
    void unclosedErrorBlockAtEndIsFlushed() {
        List<String> lines = Arrays.asList(
            HDR + "[main] ERROR com.start.service.A - 调用失败",
            "    at com.start.service.A.method(A.java:1)"
            // 没有下一条时间戳行
        );
        List<String> errors = ErrorMonitorService.parseErrorBlocks(lines);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("A.java:1"));
    }

    /**
     * 修复前场景复现：报告里说"那条 DEBUG 日志是 Gemini API 请求"——验证它不会被误抓。
     * 关键点：DEBUG 级别 + 内容里出现 "Gemini"/"Exception" 等都不应触发。
     */
    @Test
    void debugLevelWithExceptionKeywordNotCaptured() {
        // 模拟 BaiLianService.java:864 那种 DEBUG 日志
        String debugLine = HDR + "[main] DEBUG com.start.service.BaiLianService - 请求 Gemini API (Model: qwen-vl-max): {messages=[...]}";
        // 加一条 INFO 级别但内容里有 Exception
        String infoLine = HDR + "[main] INFO  com.start.service.Y - 完成 IllegalArgumentException 重试";
        // 加一条 WARN 级别但内容里有 FATAL
        String warnLine = HDR + "[main] WARN  com.start.service.Z - 检查 FATAL_RECOVERY 状态";
        // 唯一真错误的 ERROR 行
        String realError = HDR + "[main] ERROR com.start.service.A - 真错误发生了";

        List<String> errors = ErrorMonitorService.parseErrorBlocks(Arrays.asList(
            debugLine, infoLine, warnLine, realError
        ));
        assertEquals(1, errors.size(), "只有真 ERROR 应被抓到");
        assertTrue(errors.get(0).contains("真错误发生了"));
    }

    /**
     * 错误块首行可能跨平台换行符不同，验证 CRLF 也工作正常。
     */
    @Test
    void handlesCrlfLineEndings() {
        // RandomAccessFile.readLine() 已经把 \r\n 剥掉了，但提供带 \r 的也算边界
        List<String> lines = Arrays.asList(
            HDR + "[main] ERROR com.start.service.A - 调用失败\r",
            "    at com.start.service.A.method(A.java:1)\r"
        );
        List<String> errors = ErrorMonitorService.parseErrorBlocks(lines);
        // \r 在行尾不影响判定，应该能识别
        assertEquals(1, errors.size());
        assertNotNull(errors.get(0));
    }

    // ==================== extractAuditConclusion ====================

    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();

    @Test
    void extractConclusionPrefersContent() throws Exception {
        // 标准 OpenAI 响应：content 优先
        String body = "{\"choices\":[{\"message\":{\"content\":\"[一般] 需要修复：是。\",\"reasoning_content\":\"thinking...\"}}]}";
        JsonNode json = TEST_MAPPER.readTree(body);
        String result = ErrorMonitorService.extractAuditConclusion(json);
        assertEquals("[一般] 需要修复：是。", result);
    }

    @Test
    void extractConclusionFallsBackToReasoningWhenContentEmpty() throws Exception {
        // mytokenland + MiniMax-M2.7 实际响应：content 空，reasoning_content 有结论
        String body = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{"
            + "\"content\":\"\","
            + "\"reasoning_content\":\"[一般] 两条错误均为唯一键冲突导致插入失败。需要修复：是。\","
            + "\"role\":\"assistant\"}}]}";
        JsonNode json = TEST_MAPPER.readTree(body);
        String result = ErrorMonitorService.extractAuditConclusion(json);
        assertTrue(result.contains("需要修复：是"), "应 fallback 到 reasoning_content，实际: " + result);
    }

    @Test
    void extractConclusionHandlesMissingChoices() throws Exception {
        String body = "{\"error\": \"something went wrong\"}";
        JsonNode json = TEST_MAPPER.readTree(body);
        String result = ErrorMonitorService.extractAuditConclusion(json);
        assertEquals("", result);
    }

    @Test
    void extractConclusionHandlesBothEmpty() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"\"}}]}";
        JsonNode json = TEST_MAPPER.readTree(body);
        String result = ErrorMonitorService.extractAuditConclusion(json);
        assertEquals("", result);
    }

    // ==================== needsRepair ====================

    @Test
    void needsRepair_generalWithYesIsTrue() {
        // 修复前 bug: [一般] 一票否决，导致审计说"需要修复：是"也被忽略
        String conclusion = "[一般] 两条错误均为唯一键冲突导致数据缺失。需要修复：是。原因：必须修正插入逻辑。";
        assertTrue(ErrorMonitorService.needsRepair(conclusion),
            "[一般] + 需要修复：是 → true（修复前会判 false）");
    }

    @Test
    void needsRepair_generalWithNoIsFalse() {
        String conclusion = "[一般] 业务告警但可监控。需要修复：否。原因：临时网络抖动，重试可恢复。";
        assertFalse(ErrorMonitorService.needsRepair(conclusion));
    }

    @Test
    void needsRepair_severeWithYesIsTrue() {
        String conclusion = "[严重] 系统级崩溃。需要修复：是。原因：核心服务挂了。";
        assertTrue(ErrorMonitorService.needsRepair(conclusion));
    }

    @Test
    void needsRepair_severeFallbackIsTrue() {
        // [严重] 但没明确说"是/否"——按兜底规则算 true
        String conclusion = "[严重] 核心服务挂了";
        assertTrue(ErrorMonitorService.needsRepair(conclusion),
            "[严重] 无明确指令时按兜底规则 → true");
    }

    @Test
    void needsRepair_severeWithExplicitNoRespectsNo() {
        // 审计明确说"不要修"——即使严重也不修
        String conclusion = "[严重] 看起来吓人但只是已知测试副作用。需要修复：否。";
        assertFalse(ErrorMonitorService.needsRepair(conclusion),
            "审计明确说否就不修（保守原则）");
    }

    @Test
    void needsRepair_ignorableIsFalse() {
        String conclusion = "[可忽略] 一些无关紧要的 INFO 级日志。";
        assertFalse(ErrorMonitorService.needsRepair(conclusion));
    }

    @Test
    void needsRepair_emptyIsFalse() {
        assertFalse(ErrorMonitorService.needsRepair(null));
        assertFalse(ErrorMonitorService.needsRepair(""));
    }

    @Test
    void needsRepair_unparseableIsFalse() {
        // 审计 API 返回乱码或没按格式回答 → 保守不修
        assertFalse(ErrorMonitorService.needsRepair("模型返回了一段无关的话"));
        assertFalse(ErrorMonitorService.needsRepair("我不太确定，让我再看看"));
    }

    @Test
    void needsRepair_supportsBothColonStyles() {
        // 中文全角冒号 + ASCII 半角冒号都要支持
        assertTrue(ErrorMonitorService.needsRepair("需要修复：是"));
        assertTrue(ErrorMonitorService.needsRepair("需要修复:是"));
        assertFalse(ErrorMonitorService.needsRepair("需要修复：否"));
        assertFalse(ErrorMonitorService.needsRepair("需要修复:否"));
    }

    @Test
    void needsRepair_phrasesSynonyms() {
        // 同意短语
        assertFalse(ErrorMonitorService.needsRepair("无需修复，纯属样式问题"));
        assertFalse(ErrorMonitorService.needsRepair("不需要修复"));
        assertTrue(ErrorMonitorService.needsRepair("需要立即修复"));
    }
}
