package com.start.agent;

import com.start.model.ConversationBelief;
import com.start.repository.BeliefRepository;

import java.util.Arrays;
import java.util.Map;

/**
 * LLM 在需要时主动调用，更新当前会话认知。
 * 大多数闲聊不需要——只在连续话题、情绪支持、承诺、计划等场景调用。
 */
public class SetBeliefTool implements Tool {
    private final BeliefRepository repo;
    private final String groupId;
    private final String userId;

    public SetBeliefTool(BeliefRepository repo, String groupId, String userId) {
        this.repo = repo;
        this.groupId = groupId;
        this.userId = userId;
    }

    @Override public String getName() { return "set_belief"; }

    @Override public String getDescription() {
        return "仅在以下情况调用（大多数闲聊不需要）：" +
               "1) 连续话题——用户持续讨论同一件事（考试、旅行计划、工作项目），记录当前话题。" +
               "2) 情绪支持——用户在表达情绪（焦虑、开心、疲惫、难过），你正在安慰/鼓励/倾听。" +
               "3) 承诺与待办——你答应了帮用户查东西、做某事、或约定了什么。" +
               "4) 话题转换——当用户明显切换话题时，更新为新的认知。" +
               "注意：'哈哈哈''真的假的''带我一个'这类闲聊不要调用。默认为空。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "topic", Map.of("type", "string", "description", "当前话题，如'考试''旅行计划''工作'，闲聊填'none'"),
                        "user_emotion", Map.of("type", "string", "description", "用户当前情绪：紧张/开心/疲惫/难过/焦虑/期待/平静/未知"),
                        "bot_intent", Map.of("type", "string", "description", "你正在做什么：安慰/鼓励/倾听/确认/玩笑/帮助/聊天"),
                        "unresolved", Map.of("type", "string", "description", "还没解决的问题或还没兑现的承诺（可选）")
                ),
                "required", Arrays.asList("topic", "user_emotion", "bot_intent"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String topic = (String) args.get("topic");
        String userEmotion = (String) args.get("user_emotion");
        String botIntent = (String) args.get("bot_intent");
        String unresolved = (String) args.get("unresolved");

        if (topic == null || "none".equalsIgnoreCase(topic) || topic.isBlank()) {
            repo.deactivateByGroupAndUser(groupId, userId);
            return "已清除当前对话状态（话题结束）。";
        }

        ConversationBelief belief = new ConversationBelief();
        belief.setGroupId(groupId);
        belief.setUserId(userId);
        belief.setTopic(topic.trim());
        belief.setUserEmotion(userEmotion != null ? userEmotion.trim() : null);
        belief.setBotIntent(botIntent != null ? botIntent.trim() : null);
        belief.setUnresolvedQuestion(unresolved != null ? unresolved.trim() : null);
        belief.setActive(true);

        try {
            repo.save(belief);
            return "已更新当前对话状态：话题=" + topic + "，情绪=" + userEmotion + "，意图=" + botIntent;
        } catch (Exception e) {
            return "更新失败: " + e.getMessage();
        }
    }
}
