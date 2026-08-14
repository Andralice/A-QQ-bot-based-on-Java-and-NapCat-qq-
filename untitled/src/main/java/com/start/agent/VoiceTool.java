package com.start.agent;

import com.start.Main;
import com.start.service.TtsService;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 语音工具。糖果熊在群里"说话"，萌感拉满。
 * 通过 TtsService 调用火山引擎豆包语音合成 2.0 生成语音 MP3 文件，
 * 再通过 [CQ:record,file=base64://...] 发送到群聊。
 *
 * <p>支持 LLM 根据上下文自动传 emotion 参数（撒娇/吐槽/安慰等），
 * 引擎会通过 context_texts 注入语音指令，让克隆音色讲出符合人设的语气。
 *
 * <p>限频：全局每小时 60 条（所有群共享），简单滑窗计数。
 */
public class VoiceTool implements Tool {
    private final Main bot;
    private final TtsService ttsService;

    /** 全局限频：每小时 60 条（避免火山引擎 TTS 配额/账单失控） */
    private static final int HOURLY_QUOTA = 60;
    private static final long HOURLY_WINDOW_MS = 3_600_000L; // 1 小时
    private final AtomicInteger hourlyUsed = new AtomicInteger(0);
    private final AtomicLong windowStartMs = new AtomicLong(System.currentTimeMillis());

    /**
     * instruct 由 LLM 自由生成（不再用代码映射）。
     * LLM 需要按 RuleSetDefaults 里的规则写"你应该以 XX 的..."句式。
     */
    public VoiceTool(Main bot, TtsService ttsService) {
        this.bot = bot;
        this.ttsService = ttsService;
    }

    @Override public String getName() { return "send_voice"; }

    @Override
    public String getDescription() {
        return "在群里发送AI语音消息（用克隆音色讲出指定语气）。"
                + " 必须传 emotion 参数：内容是直接给 TTS 引擎的中文语音指令（instruct），"
                + " 固定句式为'你应该以 XX 的...'，比如'你应该以撒娇的、带着一点甜腻和委屈的语气说'。"
                + " 具体怎么写（修饰词、情绪、节奏）由 LLM 根据上下文自由发挥，参考 RuleSetDefaults 中的规则。"
                + " 全局限频：每小时最多 60 条，超出后会被自动拒掉。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> emotionProp = Map.of(
                "type", "string",
                "description", "**必填**。TTS 语音指令（instruct），用中文写，固定句式：'你应该以 XX 的...'。"
                        + " 例如：'你应该以撒娇的、带着一点甜腻和委屈的语气说'、'你应该以温柔、慢热、轻声细语的的语气说'。"
                        + " 不要只传一个情绪关键词（'撒娇' / '温柔'）——必须写完整的中文指令句。"
                        + " 具体怎么写（修饰词、节奏、情绪细节）由 LLM 根据当前对话上下文自由发挥。"
                        + " 写不好宁可省略 emotion 参数，不要乱写。"
        );
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "text", Map.of("type", "string", "description", "要说的话（会转成语音），10-30字最合适，必须是用户明确说的内容，禁止自己加戏扩写"),
                        "emotion", emotionProp
                ),
                "required", Arrays.asList("group_id", "text", "emotion"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        String text = (String) args.get("text");
        String emotion = (String) args.get("emotion");
        if (groupId == null || text == null) return "缺少 group_id 或 text";

        // 全局小时配额检查（CAS 抢占式）
        if (!tryAcquireHourlyQuota()) {
            int used = hourlyUsed.get();
            return "本小时语音额度已用完（" + used + "/" + HOURLY_QUOTA + "），下小时再来";
        }

        if (text.length() > 100) text = text.substring(0, 100);

        // emotion 字段就是 LLM 写好的完整中文 instruct，**直接传给 TTS 引擎**
        // （句式 "你应该以 XX 的..." 由 LLM 保证，代码不做任何映射）
        String instruct = (emotion != null && !emotion.isBlank()) ? emotion.trim() : null;

        try {
            byte[] audio = ttsService.synthesize(text, "tangguoxiong", instruct);
            if (audio == null || audio.length == 0) {
                // 失败：退还配额
                releaseHourlyQuota();
                return "语音合成失败，TTS 服务未就绪";
            }

            // base64 → CQ 码发送
            String b64 = java.util.Base64.getEncoder().encodeToString(audio);
            String cqCode = "[CQ:record,file=base64://" + b64 + "]";
            bot.sendGroupReply(Long.parseLong(groupId), cqCode);

            bot.getBaiLianService().getBotMemory().record(
                    groupId,
                    com.start.service.BotMemoryService.EntryType.VOICE,
                    null,
                    (instruct == null ? "" : "[" + instruct + "] ") + text
            );

            int used = hourlyUsed.get();
            return "已发送语音" + (instruct != null ? "(" + instruct + ")" : "") + ": " + text
                    + " (本小时已用 " + used + "/" + HOURLY_QUOTA + ")";
        } catch (Exception e) {
            // 异常也退还配额
            releaseHourlyQuota();
            return "语音发送失败: " + e.getMessage();
        }
    }

    /**
     * 抢占式获取一次小时配额。新窗口自动重置。
     */
    private boolean tryAcquireHourlyQuota() {
        long now = System.currentTimeMillis();
        long start = windowStartMs.get();
        // 新窗口则尝试滚动（CAS 防止并发重置）
        if (now - start >= HOURLY_WINDOW_MS) {
            if (windowStartMs.compareAndSet(start, now)) {
                hourlyUsed.set(0);
            }
        }
        // CAS 抢占 +1
        while (true) {
            int cur = hourlyUsed.get();
            if (cur >= HOURLY_QUOTA) return false;
            if (hourlyUsed.compareAndSet(cur, cur + 1)) return true;
        }
    }

    private void releaseHourlyQuota() {
        while (true) {
            int cur = hourlyUsed.get();
            if (cur <= 0) return;
            if (hourlyUsed.compareAndSet(cur, cur - 1)) return;
        }
    }
}
