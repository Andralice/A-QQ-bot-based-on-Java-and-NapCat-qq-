package com.start.repository;

import com.start.model.ConversationBelief;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 会话认知存储。表在构造时自动创建，不依赖外部 migration。 */
public class BeliefRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public BeliefRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    private void ensureTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS conversation_beliefs (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                group_id VARCHAR(64) NOT NULL,
                user_id VARCHAR(64) NOT NULL,
                topic VARCHAR(128),
                user_emotion VARCHAR(64),
                bot_intent VARCHAR(64),
                unresolved_question TEXT,
                relationship_state VARCHAR(64),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                active TINYINT(1) DEFAULT 1,
                INDEX idx_group_user_active (group_id, user_id, active)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create conversation_beliefs table", e);
        }
    }

    /** 保存新认知，同时失效该群+用户的旧 Belief。 */
    public void save(ConversationBelief belief) {
        deactivateByGroupAndUser(belief.getGroupId(), belief.getUserId());
        String sql = "INSERT INTO conversation_beliefs (group_id, user_id, topic, user_emotion, bot_intent, unresolved_question, relationship_state, created_at, updated_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 1)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, belief.getGroupId());
            ps.setString(2, belief.getUserId());
            ps.setString(3, belief.getTopic());
            ps.setString(4, belief.getUserEmotion());
            ps.setString(5, belief.getBotIntent());
            ps.setString(6, belief.getUnresolvedQuestion());
            ps.setString(7, belief.getRelationshipState());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) belief.setId(keys.getLong(1));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save ConversationBelief", e);
        }
    }

    /** 查询群+用户当前活跃的认知（通常只有一条）。 */
    public List<ConversationBelief> findActiveByGroupAndUser(String groupId, String userId) {
        String sql = "SELECT id, group_id, user_id, topic, user_emotion, bot_intent, unresolved_question, relationship_state, created_at, updated_at, active FROM conversation_beliefs WHERE group_id = ? AND user_id = ? AND active = 1 ORDER BY updated_at DESC LIMIT 3";
        List<ConversationBelief> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query active beliefs", e);
        }
        return list;
    }

    /** 批量失效某群+用户的所有 Belief。 */
    public void deactivateByGroupAndUser(String groupId, String userId) {
        String sql = "UPDATE conversation_beliefs SET active = 0 WHERE group_id = ? AND user_id = ? AND active = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setString(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to deactivate beliefs", e);
        }
    }

    private static ConversationBelief mapRow(ResultSet rs) throws SQLException {
        ConversationBelief b = new ConversationBelief();
        b.setId(rs.getLong("id"));
        b.setGroupId(rs.getString("group_id"));
        b.setUserId(rs.getString("user_id"));
        b.setTopic(rs.getString("topic"));
        b.setUserEmotion(rs.getString("user_emotion"));
        b.setBotIntent(rs.getString("bot_intent"));
        b.setUnresolvedQuestion(rs.getString("unresolved_question"));
        b.setRelationshipState(rs.getString("relationship_state"));
        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) b.setCreatedAt(ca.toInstant());
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) b.setUpdatedAt(ua.toInstant());
        b.setActive(rs.getBoolean("active"));
        return b;
    }
}
