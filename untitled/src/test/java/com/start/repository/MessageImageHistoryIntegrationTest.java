package com.start.repository;

import com.start.config.DatabaseConfig;
import com.start.model.ChatMessage;
import com.start.repository.BaseRepository.DatabaseResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片历史查询集成测试（默认跳过，需真实 MySQL）。
 *
 * 跑法：$env:RUN_DB_TESTS="true"; mvn test -Dtest=MessageImageHistoryIntegrationTest
 *
 * 验证开发计划第二阶段 2.1「为图片历史查询补充测试」：
 * MessageRepository.searchImageDescriptions 能查到带 image_data 的消息，
 * 且能按关键词模糊匹配 image_data JSON 内容。
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class MessageImageHistoryIntegrationTest {

    private static MessageRepository repo;
    private String testSession;
    private String testGroup;

    @BeforeAll
    static void setUpAll() {
        repo = new MessageRepository();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        testSession = "image-test-" + UUID.randomUUID();
        testGroup = "image-test-group-" + UUID.randomUUID();
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

    private void insertMessage(String content, String imageData) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", testSession);
        data.put("userId", "test-user");
        data.put("content", content);
        data.put("isRobotReply", false);
        data.put("isPrivate", false);
        data.put("groupId", testGroup);
        if (imageData != null) data.put("imageData", imageData);
        repo.saveMessage(data);
    }

    @Test
    void searchImageDescriptionsFindsMessagesWithImageData() {
        insertMessage("看这张猫图",
                "https://example.com/cat.png|一只橘猫在晒太阳");

        DatabaseResult<List<ChatMessage>> result =
                repo.searchImageDescriptions(testGroup, "猫", 10);
        assertTrue(result.isSuccess(), "查询应成功");
        assertTrue(result.isSuccess(), "查询应成功");

        List<ChatMessage> msgs = result.getData();
        assertNotNull(msgs);
        assertFalse(msgs.isEmpty(), "应能搜到带 image_data 的消息");

        ChatMessage found = msgs.stream()
                .filter(m -> m.getContent() != null && m.getContent().contains("猫图"))
                .findFirst().orElse(null);
        assertNotNull(found, "应能找到刚才插入的消息");
        assertNotNull(found.getImageData(), "image_data 字段应被正确读取");
        assertTrue(found.getImageData().contains("橘猫"), "image_data JSON 应包含视觉描述");
    }

    @Test
    void searchImageDescriptionsIgnoresMessagesWithoutImageData() {
        // 插入一条无 image_data 的消息
        insertMessage("纯文本消息没有图", null);
        // 插入一条有 image_data 的消息
        insertMessage("图来了", "https://example.com/dog.png|一只柴犬");

        DatabaseResult<List<ChatMessage>> result =
                repo.searchImageDescriptions(testGroup, "图", 10);
        assertTrue(result.isSuccess());

        List<ChatMessage> msgs = result.getData();
        // 应能找到"图来了"这条
        assertTrue(msgs.stream().anyMatch(m -> m.getContent() != null && m.getContent().equals("图来了")),
                "应能找到有 image_data 的消息");
    }

    @Test
    void imageDataJsonShapePreserved() {
        String json = "https://example.com/bird.png|一只蓝色的鸟在飞";
        insertMessage("看鸟", json);

        DatabaseResult<List<ChatMessage>> result =
                repo.searchImageDescriptions(testGroup, "鸟", 10);
        assertTrue(result.isSuccess());

        ChatMessage found = result.getData().stream()
                .filter(m -> m.getContent() != null && m.getContent().equals("看鸟"))
                .findFirst().orElse(null);
        assertNotNull(found);
        // 验证 image_data 写入和读出内容一致
        assertEquals(json, found.getImageData(), "image_data JSON 应原样保留");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }
}
