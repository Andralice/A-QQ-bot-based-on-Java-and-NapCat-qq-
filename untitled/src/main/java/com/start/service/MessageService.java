package com.start.service;

import com.hankcs.hanlp.HanLP;
import com.start.model.ChatMessage;
import com.start.repository.MessageRepository;
import com.start.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 消息服务类
 * <p>
 * 负责处理QQ机器人消息的持久化存储、上下文检索以及群聊活跃度统计。
 * 主要功能包括：
 * 1. 保存用户发送的消息及AI生成的回复到数据库。
 * 2. 提取并存储消息中的话题标签（针对群聊非私密消息）。
 * 3. 获取指定会话的历史对话上下文，用于构建AI提示词。
 * 4. 统计指定群聊在特定时间窗口内的活跃程度。
 * </p>
 */
public class MessageService {
    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);
    private final MessageRepository messageRepo;
    private final UserRepository userRepo;

    public MessageService() {
        this.messageRepo = new MessageRepository();
        this.userRepo = new UserRepository();
    }

    /**
     * 保存用户消息（从AIHandler调用）
     */
    public void saveUserMessage(String sessionId, String userId, String groupId,
                                String content, boolean isPrivate) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("userId", userId);
        data.put("content", content);
        data.put("isRobotReply", false);
        data.put("isPrivate", isPrivate);

        if (groupId != null && !isPrivate) {
            data.put("groupId", groupId);

            // 提取话题
            String topics = extractTopics(content);
            if (!topics.isEmpty()) {
                data.put("topics", topics);
            }
        }

        userRepo.createOrUpdateUser(userId, "");
        messageRepo.saveMessage(data);
        userRepo.incrementMessageCount(userId);
    }

    /**
     * 保存AI回复
     */
    public void saveAIReply(String sessionId, String groupId, String content,
                            Long replyToId, boolean isPrivate) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("userId", "candybear");
        data.put("content", content);
        data.put("isRobotReply", true);
        data.put("isPrivate", isPrivate);
        data.put("replyToId", replyToId);

        if (groupId != null && !isPrivate) {
            data.put("groupId", groupId);
        }

        messageRepo.saveMessage(data);
    }

    /**
     * 获取对话上下文（用于AI提示词）
     */
    public String getConversationContext(String sessionId, int limit) {
        var result = messageRepo.findBySessionId(sessionId, limit);
        if (result.isSuccess()) {
            List<Map<String, Object>> messages = result.getData();
            StringBuilder context = new StringBuilder();

            for (Map<String, Object> msg : messages) {
                String role = Boolean.TRUE.equals(msg.get("is_robot_reply"))
                        ? "助手" : "用户";
                context.append(role).append(": ")
                        .append(msg.get("content")).append("\n");
            }
            return context.toString();
        }
        return "";
    }

    /**
     * 获取群聊最近活跃度
     */
    public int getGroupActivityLevel(String groupId, int minutes) {
        var result = messageRepo.findConversationContext(groupId, minutes, 50);
        if (result.isSuccess()) {
            return result.getData().size();
        }
        return 0;
    }

    /**
     * 提取消息话题标签：HanLP 关键词提取，最多 3 个；失败时回退到截断前 20 字符。
     * 模式参考 ThreadManager.extractTopic。
     */
    private String extractTopics(String content) {
        if (content == null || content.isBlank()) return "";
        try {
            String clean = content.replaceAll("[\\p{Punct}\\s]+", " ").trim();
            if (clean.isEmpty()) return "";
            List<String> kw = HanLP.extractKeyword(clean, 3);
            if (kw != null && !kw.isEmpty()) {
                return kw.stream()
                        .filter(k -> k != null && !k.isBlank())
                        .limit(3)
                        .collect(Collectors.joining(","));
            }
        } catch (Exception e) {
            logger.debug("HanLP 话题提取失败: {}", e.getMessage());
        }
        // 回退：截断前 20 字符
        String trimmed = content.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }
}
