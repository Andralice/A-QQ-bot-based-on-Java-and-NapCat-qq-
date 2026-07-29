package com.start.agent;

import com.start.config.BotConfig;
import com.start.model.ConversationThread;
import com.start.model.WorkingMemory;
import com.start.model.WorkingMemoryStatus;
import com.start.repository.ThreadRepository;
import com.start.repository.WorkingMemoryRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * LLM 在需要时主动调用，为当前 Thread 设置工作记忆。
 * 不同于 SetBeliefTool（per-user 的会话认知），此工具绑定 Thread——记录 bot 在这个话题中"正在做什么"。
 *
 * 工作记忆是"白板"不是"档案柜"：必须有生命周期。
 * - LLM 写时设 expires_at_hours（默认 24h），到期自动从 Prompt 消失
 * - LLM 觉得"这任务聊完了"→ 传 status=COMPLETED，立即关闭（保留 DB 行供审计）
 * - 兼容旧 API：goal='none' 等同 COMPLETED
 */
public class SetWorkingMemoryTool implements Tool {

    private final WorkingMemoryRepository wmRepo;
    private final ThreadRepository threadRepo;
    private final String groupId;
    private final long botQq;

    public SetWorkingMemoryTool(WorkingMemoryRepository wmRepo, ThreadRepository threadRepo,
                                 String groupId, long botQq) {
        this.wmRepo = wmRepo;
        this.threadRepo = threadRepo;
        this.groupId = groupId;
        this.botQq = botQq;
    }

    @Override public String getName() { return "set_working_memory"; }

    @Override
    public String getDescription() {
        return "为当前 Thread 设置/更新工作记忆（白板，不是档案柜）。仅在群聊中存在需要多轮协作的持续话题时调用。" +
               "调用场景：" +
               "1) 连续讨论——群内在持续讨论同一件事（debug、计划、决策），你需要记住目标和进展。" +
               "2) 待办与承诺——你答应了帮某人做某事、查东西，需要记住等待什么。" +
               "3) 信息收集——你在等待某人提供信息（日志、截图、数据）。" +
               "生命周期：\n" +
               "- 默认 24h 后自动从 Prompt 消失（可传 expires_at_hours 调整）\n" +
               "- 任务完成或话题结束→传 status='COMPLETED' 立即关闭，DB 保留行供审计\n" +
               "- 兼容：goal='none' 等同 COMPLETED（旧 API）";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "goal", Map.of("type", "string", "description", "当前目标，如'帮A解决Redis连接失败'。任务完成传 status=COMPLETED"),
                        "context_summary", Map.of("type", "string", "description", "已讨论到哪里，关键信息总结"),
                        "pending_action", Map.of("type", "string", "description", "等待什么或等待谁（可选）"),
                        "attention_target", Map.of("type", "string", "description", "当前关注对象的名字或QQ号（可选）"),
                        "user_emotions", Map.of("type", "string", "description", "相关用户的情绪，JSON格式（可选）"),
                        "constraints", Map.of("type", "string", "description", "限制条件或注意事项（可选）"),
                        "status", Map.of("type", "string", "description", "状态：ACTIVE(默认，进行中)/COMPLETED(任务完成，立即关闭)"),
                        "expires_at_hours", Map.of("type", "string", "description", "过期小时数，0 表示用 BotConfig.workingMemoryDefaultExpireHours（默认 24h）")
                ),
                "required", Arrays.asList("goal", "context_summary"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String goal = (String) args.get("goal");
        String contextSummary = (String) args.get("context_summary");
        String statusArg = (String) args.get("status");
        String expiresAtHoursArg = (String) args.get("expires_at_hours");

        // 找到当前 Thread（bot 参与的最高权重 Thread，否则最高权重）
        Long threadId = findCurrentThreadId();
        if (threadId == null) {
            return "当前群没有活跃话题，无需记录工作记忆。";
        }

        // 兼容老 API：goal='none' 等同 status=COMPLETED
        if (goal == null || "none".equalsIgnoreCase(goal) || goal.isBlank()
                || "COMPLETED".equalsIgnoreCase(statusArg)) {
            // 如果有现存 ACTIVE 记忆，标 COMPLETED；否则提示
            WorkingMemory existing = wmRepo.findActiveByThread(threadId);
            if (existing != null) {
                wmRepo.markCompleted(existing.getId());
                return "已关闭当前话题的工作记忆（任务结束）。";
            }
            return "当前话题没有活跃的工作记忆，无需关闭。";
        }

        WorkingMemory wm = new WorkingMemory();
        wm.setThreadId(threadId);
        wm.setGoal(goal.trim());
        wm.setContextSummary(contextSummary != null ? contextSummary.trim() : null);
        wm.setPendingAction(args.get("pending_action") instanceof String s && !s.isBlank() ? s.trim() : null);
        wm.setAttentionTarget(args.get("attention_target") instanceof String s && !s.isBlank() ? s.trim() : null);
        wm.setUserEmotions(args.get("user_emotions") instanceof String s && !s.isBlank() ? s.trim() : null);
        wm.setConstraints(args.get("constraints") instanceof String s && !s.isBlank() ? s.trim() : null);
        wm.setStatus(WorkingMemoryStatus.ACTIVE);
        wm.setActive(true);

        // 计算 expires_at：expire_hours 显式传 > 0 用它；否则用 BotConfig 默认值
        int expireHours;
        try {
            expireHours = expiresAtHoursArg != null ? Integer.parseInt(expiresAtHoursArg) : 0;
        } catch (NumberFormatException e) {
            expireHours = 0;
        }
        if (expireHours <= 0) expireHours = BotConfig.getWorkingMemoryDefaultExpireHours();
        wm.setExpiresAt(Instant.now().plus(expireHours, ChronoUnit.HOURS));

        try {
            wmRepo.save(wm);
            return "已更新工作记忆：目标=" + goal
                    + "，进展=" + contextSummary
                    + "，" + expireHours + "h 后自动失效";
        } catch (Exception e) {
            return "更新失败: " + e.getMessage();
        }
    }

    private Long findCurrentThreadId() {
        List<ConversationThread> threads = threadRepo.findActiveByGroup(groupId);
        if (threads.isEmpty()) return null;

        String botId = String.valueOf(botQq);
        for (ConversationThread t : threads) {
            if (t.getParticipants().contains(botId)) return t.getId();
        }
        // bot 未参与任何 Thread → 返回最高权重的
        return threads.get(0).getId();
    }
}
