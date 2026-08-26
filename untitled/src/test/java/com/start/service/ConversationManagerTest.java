package com.start.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

class ConversationManagerTest {

    @Test
    void replacesOnlyStaleUnsubmittedConversation() {
        ConversationManager manager = new ConversationManager();
        ConversationState state = manager.getOrCreatePending("group", "user", 120_000L, 1_000L);
        state.addMessage("刚才群里在聊的内容");

        ConversationState retained = manager.getOrCreatePending("group", "user", 120_000L,
                state.getLastMessageAt() + 119_999L);
        assertSame(state, retained, "短期普通消息应保留，供紧接着的触发理解语境");

        ConversationState replaced = manager.getOrCreatePending("group", "user", 120_000L,
                state.getLastMessageAt() + 120_001L);
        assertNotSame(state, replaced, "过期且未提交的缓冲不能污染之后的新请求");
    }

    @Test
    void doesNotReplaceConversationAlreadySubmittedToAi() {
        ConversationManager manager = new ConversationManager();
        ConversationState state = manager.getOrCreatePending("group", "user", 120_000L, 1_000L);
        state.markSubmitted();

        assertSame(state, manager.getOrCreatePending("group", "user", 120_000L,
                state.getLastMessageAt() + 120_001L),
                "AI 生成中的会话必须保留，允许补充消息触发再生成");
    }
}
