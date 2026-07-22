package com.start.agent;

import com.start.model.CandyBearEventLog;
import com.start.repository.EventLogRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 在聊天中记录值得记住的事件。这些事件会在晚上汇总成糖果熊的日记，
 * 让日记反映真实发生的互动而非凭空编造。
 */
public class LogEventTool implements Tool {
    private static final Logger logger = LoggerFactory.getLogger(LogEventTool.class);
    private final EventLogRepository repo;

    public LogEventTool(EventLogRepository repo) {
        this.repo = repo;
    }

    @Override public String getName() { return "log_event"; }

    @Override public String getDescription() {
        return "记录今天发生的一件值得记住的事。当你跟人聊天时发生了以下情况，调用此工具："
               + "1) 有人祝你生日快乐/节日快乐、送你祝福"
               + "2) 你告诉了别人今天做了什么（去了医院、逛了商场、打了游戏）"
               + "3) 有人对你表达了强烈的情绪（特别开心/难过/生气）"
               + "4) 你表达了强烈的情绪波动"
               + "这些事件会在晚上汇总成你的日记，让你第二天还能记住今天发生了什么。"
               + "参数：summary(事件描述，一句话), event_type(SCHEDULE/CHAT/MOOD/MANUAL，默认CHAT), "
               + "emotion(情绪：开心/emo/累/兴奋/烦躁/感动), emotion_impact(情绪影响：-5到+5，正=正面负=负面，默认0)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "summary", Map.of("type", "string", "description", "事件描述，一句话，如'归儿祝我生日快乐'"),
                        "event_type", Map.of("type", "string", "description", "事件类型：SCHEDULE/CHAT/MOOD/MANUAL，默认CHAT"),
                        "emotion", Map.of("type", "string", "description", "此事件带来的情绪：开心/emo/累/无聊/充实/焦虑/兴奋/慵懒/烦躁/感动"),
                        "emotion_impact", Map.of("type", "string", "description", "情绪影响 -5到+5。生日祝福=+3，吵架=-4，日常闲聊=0")
                ),
                "required", Arrays.asList("summary"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String summary = (String) args.get("summary");
        if (summary == null || summary.isBlank()) return "缺少 summary 参数";

        String eventType = (String) args.getOrDefault("event_type", "CHAT");
        String emotion = (String) args.getOrDefault("emotion", "");
        int impact = parseIntSafe((String) args.get("emotion_impact"), 0);
        impact = Math.max(-5, Math.min(5, impact)); // clamp

        CandyBearEventLog e = new CandyBearEventLog();
        e.setEventTime(LocalDateTime.now());
        e.setEventDate(LocalDate.now());
        e.setEventType(eventType);
        e.setSummary(summary.trim());
        e.setEmotion(emotion != null ? emotion : "");
        e.setEmotionImpact(impact);

        try {
            repo.insert(e);
            return "已记录事件: " + summary.trim()
                    + (emotion != null && !emotion.isEmpty() ? "（情绪：" + emotion + "）" : "");
        } catch (Exception ex) {
            logger.error("log_event 失败", ex);
            return "记录事件失败: " + ex.getMessage();
        }
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return def; }
    }
}
