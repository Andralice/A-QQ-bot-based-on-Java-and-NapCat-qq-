package com.start.agent;

import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.time.LocalDateTime;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 存储长期记忆。当用户说了值得记住的信息（个人事实、偏好、事件），调用此工具写入 DB。
 * 插入前检查相似记忆，找到则强化已有记忆而非重复插入。
 */
public class RememberFactTool implements Tool {
    private static final Logger logger = LoggerFactory.getLogger(RememberFactTool.class);
    private final LongTermMemoryRepository repo;

    public RememberFactTool(LongTermMemoryRepository repo) {
        this.repo = repo;
    }

    @Override public String getName() { return "remember_fact"; }

    @Override public String getDescription() {
        return "记录一条关于用户的长期记忆。遇到以下情况必须调用（不等用户说'记住'）：" +
               "1) 用户说了自己的事实：我是程序员、我高二了、我在北京、我家有只猫、我数学很烂。" +
               "2) 用户表达了偏好：我喜欢喝奶茶、我讨厌下雨天、我不吃香菜。" +
               "3) 用户提到了未来的事：下周五我生日、明天要考试、暑假要去日本。" +
               "4) 用户说了和他人的关系：XX是我同学、YY是我哥。" +
               "5) 用户情绪低落或高兴时说了原因：今天被老师骂了不开心、抽到SSR了好开心。" +
               "铁律：看到以上任何一种情况→立即调此工具记下，不要等、不要犹豫。" +
               "参数：user_id(用户QQ), group_id(群号), content(记忆内容，一句话), memory_type(fact/preference/event/relation), keywords(逗号分隔的关键词), importance(1-5重要性，普通事实3，重要偏好4，生日/大事件5)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "用户QQ号"),
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "content", Map.of("type", "string", "description", "记忆内容，简洁的一句话"),
                        "memory_type", Map.of("type", "string", "description", "类型：fact(事实)/preference(偏好)/event(事件)/relation(关系)"),
                        "keywords", Map.of("type", "string", "description", "关键词，逗号分隔，方便以后检索"),
                        "importance", Map.of("type", "string", "description", "重要性 1-5，5 为非常重要")
                ),
                "required", Arrays.asList("user_id", "group_id", "content"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        String groupId = (String) args.get("group_id");
        String content = (String) args.get("content");
        if (userId == null || content == null || content.isBlank()) return "缺少 user_id 或 content";

        String trimmedContent = content.trim();
        String keywords = (String) args.get("keywords");

        // 检查是否有相似记忆，有则强化而非重复插入
        try {
            List<LongTermMemory> similar = repo.findSimilar(userId, groupId, trimmedContent, keywords);
            if (!similar.isEmpty()) {
                LongTermMemory existing = similar.get(0);
                repo.upsertConfirm(existing.getId());
                return "已更新记忆: " + trimmedContent + "（之前已存在，已强化确认）";
            }
        } catch (Exception e) {
            logger.warn("Upsert check failed, falling through to insert: {}", e.getMessage());
        }

        LongTermMemory m = new LongTermMemory();
        m.setUserId(userId);
        m.setGroupId(groupId);
        m.setContent(trimmedContent);
        m.setMemoryType((String) args.getOrDefault("memory_type", "fact"));
        m.setKeywords(keywords);
        m.setImportance(parseIntSafe((String) args.get("importance"), 3));
        m.setSource("SELF_REPORTED");
        m.setStatus("ACTIVE");
        m.setConfidence(0.9);
        LocalDateTime now = LocalDateTime.now();
        m.setLastSeenAt(now);
        m.setLastConfirmedAt(now);

        try {
            repo.insert(m);
            return "已记住: " + trimmedContent;
        } catch (Exception e) {
            return "记录失败: " + e.getMessage();
        }
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
