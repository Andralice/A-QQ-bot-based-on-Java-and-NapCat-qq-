package com.start.service;

import com.start.config.DatabaseConfig;
import com.start.repository.BaseRepository.DatabaseResult;
import com.start.model.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第二阶段 2.2 关键回归测试（默认跳过，需真实 MySQL）。
 *
 * 跑法：$env:RUN_DB_TESTS="true"; mvn test -Dtest=MessageServiceContextIntegrationTest
 *
 * 验证核心修复：用户消息和 AI 回复写入同一个 session 后，
 * MessageService.getConversationContext 能同时查到两者。
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class MessageServiceContextIntegrationTest {

    private static MessageService messageService;
    private String testSession;
    private String testGroup;
    private String testUser;

    @BeforeAll
    static void setUpAll() {
        messageService = new MessageService();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        testSession = "ctx-test-" + UUID.randomUUID();
        testGroup = "ctx-test-group-" + UUID.randomUUID();
        testUser = "ctx-test-user-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM messages WHERE session_id = ?")) {
            ps.setString(1, testSession);
            ps.executeUpdate();
        }
    }

    @Test
    void userAndAiMessagesInSameSessionAreRetrievable() {
        // 1. 保存用户消息
        messageService.saveUserMessage(testSession, testUser, testGroup,
                "今天天气怎么样", false);

        // 2. 保存 AI 回复（修复前会写到 group_{g}_bot，修复后写到 testSession）
        messageService.saveAIReply(testSession, testGroup, "今天晴天，适合出门", null, false);

        // 3. 按 sessionId 查上下文
        String context = messageService.getConversationContext(testSession, 10);
        assertNotNull(context);
        assertTrue(context.contains("今天天气怎么样"),
                "应能找到用户消息，实际=" + context);
        assertTrue(context.contains("今天晴天"),
                "应能找到 AI 回复，实际=" + context);
    }

    @Test
    void legacyGroupBotSessionIsolatedFromUserSession() {
        // 模拟旧的"错误"行为：AI 写到 group_{g}_bot
        String legacySession = "group_" + testGroup + "_bot";
        try {
            messageService.saveAIReply(legacySession, testGroup, "旧数据 bot reply", null, false);
            messageService.saveUserMessage(testSession, testUser, testGroup,
                    "用户新消息", false);
            // 旧 bot session 查不到用户消息
            String botCtx = messageService.getConversationContext(legacySession, 10);
            assertNotNull(botCtx);
            assertTrue(botCtx.contains("旧数据 bot reply"));
            assertFalse(botCtx.contains("用户新消息"),
                    "修复后语义：bot 旧 session 不应包含用户新消息（修复前 bug 是分两个 session）");
        } finally {
            try (Connection conn = DatabaseConfig.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM messages WHERE session_id = ?")) {
                ps.setString(1, legacySession);
                ps.executeUpdate();
            } catch (Exception ignored) {}
        }
    }

    @Test
    void privateChatSessionUnchangedBehavior() {
        // 私聊：用户消息和 AI 回复都用 private_{u}，修复前后一致
        String privateSession = SessionId.privateChat(testUser);
        messageService.saveUserMessage(privateSession, testUser, null,
                "私聊问个事", true);
        messageService.saveAIReply(privateSession, null, "私聊回复", null, true);

        String ctx = messageService.getConversationContext(privateSession, 10);
        assertTrue(ctx.contains("私聊问个事"));
        assertTrue(ctx.contains("私聊回复"));
    }
}
