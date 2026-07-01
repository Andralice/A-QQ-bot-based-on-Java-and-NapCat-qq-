package com.start.service;

import com.start.memory.MemoryRecall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一记忆服务。聚合多个 MemoryProvider，提供单一查询入口。
 * 不做存储，只做聚合查询。
 */
public class MemoryService {
    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);

    private final List<MemoryProvider> providers = new ArrayList<>();

    public void register(MemoryProvider provider) {
        providers.add(provider);
        logger.info("MemoryProvider registered: {}", provider.name());
    }

    /**
     * 用关键词列表检索所有 Provider，返回 MemoryRecall 列表供 PromptBuilder 渲染。
     * @return 召回结果列表，无结果时返回空列表
     */
    public List<MemoryRecall> queryForPrompt(String userId, String groupId, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return List.of();

        Set<String> seen = new LinkedHashSet<>(); // 按小写 content 去重
        List<MemoryRecall> merged = new ArrayList<>();

        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            if (merged.size() >= 10) break;

            for (MemoryProvider p : providers) {
                try {
                    List<MemoryRecall> results = p.search(MemoryQuery.of(userId, groupId, kw));
                    for (MemoryRecall r : results) {
                        String dedupKey = r.content().toLowerCase();
                        if (seen.add(dedupKey)) {
                            merged.add(r);
                            if (merged.size() >= 10) break;
                        }
                    }
                } catch (Exception ex) {
                    logger.warn("MemoryProvider {} search failed: {}", p.name(), ex.getMessage());
                }
            }
        }

        if (merged.isEmpty()) return List.of();

        // 排序：状态优先级 DESC → 置信度 DESC
        merged.sort(MemoryRecall::compareByPriority);
        return merged;
    }
}
