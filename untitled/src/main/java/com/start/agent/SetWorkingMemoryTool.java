package com.start.agent;

import com.start.model.ConversationThread;
import com.start.model.WorkingMemory;
import com.start.repository.ThreadRepository;
import com.start.repository.WorkingMemoryRepository;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * LLM 在需要时主动调用，为当前 Thread 设置工作记忆。
 * 不同于 SetBeliefTool（per-user 的会话认知），此工具绑定 Thread——记录 bot 在这个话题中"正在做什么"。
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
        return "仅在群聊中存在需要多轮协作的持续话题时调用。大多数闲聊（'哈哈哈''带我一个''+1'）不需要。" +
               "调用场景：" +
               "1) 连续讨论——群内在持续讨论同一件事（debug、计划、决策），你需要记住目标和进展。" +
               "2) 待办与承诺——你答应了帮某人做某事、查东西，需要记住等待什么。" +
               "3) 信息收集——你在等待某人提供信息（日志、截图、数据）。" +
               "任务完成或话题结束后，goal 填 'none' 清除记忆。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "goal", Map.of("type", "string", "description", "当前目标，如'帮A解决Redis连接失败'。任务完成填'none'"),
                        "context_summary", Map.of("type", "string", "description", "已讨论到哪里，关键信息总结，如'检查了配置，确认端口正确'"),
                        "pending_action", Map.of("type", "string", "description", "等待什么或等待谁，如'等待A发错误日志'（可选）"),
                        "attention_target", Map.of("type", "string", "description", "当前关注对象的名字或QQ号（可选）"),
                        "user_emotions", Map.of("type", "string", "description", "相关用户的情绪，JSON格式，如{\"qq号\":\"焦虑\"}（可选）"),
                        "constraints", Map.of("type", "string", "description", "限制条件或注意事项，如'A是Java中级，不用解释基础概念'（可选）")
                ),
                "required", Arrays.asList("goal", "context_summary"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String goal = (String) args.get("goal");
        String contextSummary = (String) args.get("context_summary");

        // 找到当前 Thread（bot 参与的最高权重 Thread，否则最高权重）
        Long threadId = findCurrentThreadId();
        if (threadId == null) {
            return "当前群没有活跃话题，无需记录工作记忆。";
        }

        if (goal == null || "none".equalsIgnoreCase(goal) || goal.isBlank()) {
            wmRepo.deactivateByThread(threadId);
            return "已清除当前话题的工作记忆（任务结束）。";
        }

        WorkingMemory wm = new WorkingMemory();
        wm.setThreadId(threadId);
        wm.setGoal(goal.trim());
        wm.setContextSummary(contextSummary != null ? contextSummary.trim() : null);
        wm.setPendingAction(args.get("pending_action") instanceof String s && !s.isBlank() ? s.trim() : null);
        wm.setAttentionTarget(args.get("attention_target") instanceof String s && !s.isBlank() ? s.trim() : null);
        wm.setUserEmotions(args.get("user_emotions") instanceof String s && !s.isBlank() ? s.trim() : null);
        wm.setConstraints(args.get("constraints") instanceof String s && !s.isBlank() ? s.trim() : null);
        wm.setActive(true);

        try {
            wmRepo.save(wm);
            return "已更新工作记忆：目标=" + goal + "，进展=" + contextSummary;
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
