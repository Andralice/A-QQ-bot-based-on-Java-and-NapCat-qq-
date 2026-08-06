package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SessionId 工厂（第二阶段 2.2 改造）。
 *
 * 集中所有 sessionId 拼装逻辑，避免散落在各 handler 里。
 * 第一版只封装现有 4 种格式，不引入新格式（保持向后兼容）。
 *
 * 格式：
 * - 私聊：private_{userId}        —— 双方都用此 sessionId（用户消息 + AI 回复）
 * - 群对话：group_{groupId}_{userId} —— 群内某用户的私有会话上下文
 * - 群 AI 回复（旧行为）：group_{groupId}_bot —— 仅用于非 AIHandler 路径的命令响应
 * - 定时事件：event_{eventId}
 * - 周期任务：recurring_{taskId}_{fireTimestamp}
 *
 * 后续 group_public / group_private 拆分留待第二阶段。
 */
public final class SessionId {

    private SessionId() {}

    /** 私聊上下文（用户消息 + AI 回复共用同一个 sessionId） */
    public static String privateChat(String userId) {
        return "private_" + userId;
    }

    /** 群内某用户的私有对话上下文（用户消息 + AI 回复共用） */
    public static String groupConversation(String groupId, String userId) {
        return "group_" + groupId + "_" + userId;
    }

    /** 群内非 AI 对话命令响应的 session（保持旧 group_*_bot 行为） */
    public static String groupBotReply(String groupId) {
        return "group_" + groupId + "_bot";
    }

    /** 定时事件 session（按 eventId 唯一标识） */
    public static String event(long eventId) {
        return "event_" + eventId;
    }

    /** 周期任务 session（每次触发带时间戳，避免不同触发共用 session） */
    public static String recurringTask(long taskId, long fireTimestamp) {
        return "recurring_" + taskId + "_" + fireTimestamp;
    }

    /**
     * 从原始 OneBot 消息推断 sessionId。
     * 仅用于"无法拿到真实 sessionId 的兼容场景"（如旧 sendReply(msg, reply)）。
     * 优先用 groupConversation（用户维度），保持用户消息和 AI 回复的 sessionId 一致。
     */
    public static String deriveFromMessage(JsonNode msg) {
        if (msg == null) return null;
        String msgType = msg.path("message_type").asText();
        long userId = msg.path("user_id").asLong();
        if ("group".equals(msgType)) {
            long groupId = msg.path("group_id").asLong();
            return groupConversation(String.valueOf(groupId), String.valueOf(userId));
        }
        return privateChat(String.valueOf(userId));
    }
}
