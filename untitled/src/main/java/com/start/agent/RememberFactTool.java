package com.start.agent;

import com.start.memory.SemanticSimilarity;
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
    private final String currentUserId;
    private final String currentGroupId;

    public RememberFactTool(LongTermMemoryRepository repo, String currentUserId, String currentGroupId) {
        this.repo = repo;
        this.currentUserId = currentUserId;
        this.currentGroupId = currentGroupId;
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
                 "required", Arrays.asList("content"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = currentUserId;
        String groupId = currentGroupId;
        String content = (String) args.get("content");
        if (userId == null || content == null || content.isBlank()) return "缺少 user_id 或 content";

        String trimmedContent = content.trim();
        String keywords = (String) args.get("keywords");

        // 语义相似度精排：先拉 20 条候选（不限关键词覆盖召回），再用 SemanticSimilarity 排序
        // 避免 LIKE '%英短%' 漏掉 '%橘猫%' 这种字面不匹配但语义相近的情况
        try {
            List<LongTermMemory> candidates = repo.findRecentByUser(userId, groupId, 20);
            LongTermMemory best = rankBySemanticSimilarity(candidates, trimmedContent, keywords);
            if (best != null) {
                repo.upsertConfirm(best.getId());
                return "已更新记忆: " + trimmedContent + "（之前已存在，已强化确认）";
            }
        } catch (Exception e) {
            logger.warn("Semantic upsert check failed, falling through to insert: {}", e.getMessage());
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

    /** 阈值：≥ 0.50 强相似 → 走 upsertConfirm。0.30~0.50 视为不同主题，< 0.30 太弱都视为不同。 */
    private static final double STRONG_SIMILARITY_THRESHOLD = 0.50;

    /**
     * 对候选记忆做语义相似度精排，返回最相似的那条（如果超过阈值）。
     * 改自原 findSimilar 版的纯 LIKE 召回：现在用 SemanticSimilarity
     * 综合 Jaccard（关键词重合）+ 字符 n-gram（字面相似）。
     */
    private LongTermMemory rankBySemanticSimilarity(List<LongTermMemory> candidates,
                                                     String newContent, String newKeywords) {
        if (candidates == null || candidates.isEmpty()) return null;
        LongTermMemory best = null;
        double bestScore = 0.0;
        for (LongTermMemory m : candidates) {
            // 已过期/已触发的定时事件不参与 upsert，直接跳过
            if (m.isTriggered()) continue;
            if (m.getExpiresAt() != null && m.getExpiresAt().isBefore(LocalDateTime.now())) continue;
            double s = SemanticSimilarity.score(
                    newContent, newKeywords,
                    m.getContent(), m.getKeywords());
            if (s > bestScore) {
                bestScore = s;
                best = m;
            }
        }
        return bestScore >= STRONG_SIMILARITY_THRESHOLD ? best : null;
    }
}
