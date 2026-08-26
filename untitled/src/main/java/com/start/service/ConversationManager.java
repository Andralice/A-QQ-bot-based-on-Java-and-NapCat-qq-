package com.start.service;

import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理 ConversationState 生命周期。State 是临时的：首条消息创建，回复发送后销毁。
 */
public class ConversationManager {
    private static final Logger logger = LoggerFactory.getLogger(ConversationManager.class);

    private final ConcurrentHashMap<String, ConversationState> states = new ConcurrentHashMap<>();

    public ConversationState getOrCreatePending(String groupId, String userId, long staleAfterMs) {
        return getOrCreatePending(groupId, userId, staleAfterMs, System.currentTimeMillis());
    }

    /**
     * Keeps a short unsubmitted buffer for immediate follow-up context, but never
     * lets it survive long enough to contaminate a later explicit request.
     */
    ConversationState getOrCreatePending(String groupId, String userId, long staleAfterMs, long nowMs) {
        String key = key(groupId, userId);
        return states.compute(key, (ignored, existing) -> {
            if (existing == null) return new ConversationState(groupId, userId);
            boolean staleUnsubmitted = !existing.isSubmitted()
                    && nowMs - existing.getLastMessageAt() > staleAfterMs;
            if (staleUnsubmitted) {
                logger.debug("Discarding stale unsubmitted conversation: {}_{} idle={}ms",
                        groupId, userId, nowMs - existing.getLastMessageAt());
                return new ConversationState(groupId, userId);
            }
            return existing;
        });
    }

    public ConversationState get(String groupId, String userId) {
        return states.get(key(groupId, userId));
    }

    public ConversationState remove(String groupId, String userId) {
        ConversationState removed = states.remove(key(groupId, userId));
        if (removed != null) {
            logger.debug("ConversationState removed: {}_{}", groupId, userId);
        }
        return removed;
    }

    private static String key(String groupId, String userId) {
        return groupId + "_" + userId;
    }
}
