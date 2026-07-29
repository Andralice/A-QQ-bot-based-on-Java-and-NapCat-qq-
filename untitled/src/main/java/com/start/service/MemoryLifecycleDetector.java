package com.start.service;

import com.start.model.WorkingMemory;
import com.start.model.WorkingMemoryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 工作记忆生命周期自动检测器（纯函数，不调 DB）。
 *
 * 解决"用户已经说不用查了，但 LLM 忘记调 set_working_memory(COMPLETED)"的问题。
 * 在 BaiLianService 加载 WorkingMemory 之前调用 shouldComplete()，如果返回 true，
 * 由调用方负责执行 markCompleted。
 *
 * 设计原则：
 *   - 不上 LLM，纯正则
 *   - 误判不影响功能（只浪费一次 DB UPDATE）
 *   - 不持有 DB 引用（纯函数），调用方负责副作用
 */
public class MemoryLifecycleDetector {

    private static final Logger logger = LoggerFactory.getLogger(MemoryLifecycleDetector.class);

    /** "取消/结束"意图关键词。中英文都覆盖。 */
    private static final Pattern CANCEL_INTENT = Pattern.compile(
            "(不用了|不用查了|不用看了|不用找了|不用做了|不用管了|不用谢|" +
            "取消|算了吧|算了|作罢|" +
            "结束了|结束|完事|完事了|搞定了|搞好了|做完了|弄完了|处理完了|" +
            "已经解决|已经搞定|已经处理|已经处理了|已经完成|已经做好了|" +
            "不需要了|不需要|不用麻烦|" +
            "忘掉|别管了|别管|别想了|" +
            "cancel|forget it|never mind|drop it|skip it|done)"
    );

    /**
     * 检测用户消息是否含"取消/结束"任务意图（纯函数，不调 DB）。
     * @param currentWm 当前活跃的工作记忆；null 或非 ACTIVE 直接返回 false
     * @param userMessage 用户原始消息
     * @return true 表示应当 markCompleted；false 表示无此意图或无 ACTIVE 任务
     */
    public boolean shouldComplete(WorkingMemory currentWm, String userMessage) {
        // null-safe：避免 status 为 null 时 NPE（老数据迁移遗漏的边角）
        if (currentWm == null || currentWm.getStatus() != WorkingMemoryStatus.ACTIVE) {
            return false; // 没有活跃任务，忽略
        }
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        if (!CANCEL_INTENT.matcher(userMessage).find()) {
            return false;
        }
        // 有 ACTIVE 任务 + 用户说了"取消/结束" → 建议关闭
        logger.debug("MemoryLifecycleDetector: matched cancel intent in '{}', closing WM id={}",
                userMessage, currentWm.getId());
        return true;
    }
}
