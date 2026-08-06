package com.start.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SessionId 工厂单元测试（第二阶段 2.2 改造）。
 */
class SessionIdTest {

    @Test
    void privateChatFormat() {
        assertEquals("private_456", SessionId.privateChat("456"));
    }

    @Test
    void groupConversationFormat() {
        assertEquals("group_123_456", SessionId.groupConversation("123", "456"));
    }

    @Test
    void groupBotReplyFormat() {
        assertEquals("group_123_bot", SessionId.groupBotReply("123"));
    }

    @Test
    void eventFormat() {
        assertEquals("event_100", SessionId.event(100L));
    }

    @Test
    void recurringTaskFormat() {
        assertEquals("recurring_5_1691234567", SessionId.recurringTask(5L, 1691234567L));
    }

    @Test
    void deriveFromMessageReturnsUserDimensionForGroup() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode msg = m.createObjectNode();
        msg.put("message_type", "group");
        msg.put("user_id", 456);
        msg.put("group_id", 123);
        // 修复后的推导：群用 group_{g}_{u}（用户维度，与 AI 回复同 session）
        assertEquals("group_123_456", SessionId.deriveFromMessage(msg));
    }

    @Test
    void deriveFromMessageReturnsPrivateForPrivate() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode msg = m.createObjectNode();
        msg.put("message_type", "private");
        msg.put("user_id", 456);
        assertEquals("private_456", SessionId.deriveFromMessage(msg));
    }

    @Test
    void deriveFromMessageReturnsNullForNull() {
        assertEquals(null, SessionId.deriveFromMessage(null));
    }
}
