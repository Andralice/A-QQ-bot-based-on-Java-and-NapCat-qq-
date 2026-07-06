package com.start.repository;

import com.start.model.WorkingMemory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 工作记忆存储。绑定 Thread，表在构造时自动创建。 */
public class WorkingMemoryRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public WorkingMemoryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    private void ensureTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS working_memory (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                thread_id BIGINT NOT NULL,
                goal TEXT,
                context_summary TEXT,
                pending_action TEXT,
                attention_target VARCHAR(128),
                user_emotions JSON,
                constraints TEXT,
                active TINYINT(1) DEFAULT 1,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_thread_active (thread_id, active)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create working_memory table", e);
        }
    }

    /** 保存新工作记忆，同时失效该 Thread 的旧记录。 */
    public void save(WorkingMemory wm) {
        deactivateByThread(wm.getThreadId());
        String sql = "INSERT INTO working_memory (thread_id, goal, context_summary, pending_action, attention_target, user_emotions, constraints, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, NOW(), NOW())";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, wm.getThreadId());
            ps.setString(2, wm.getGoal());
            ps.setString(3, wm.getContextSummary());
            ps.setString(4, wm.getPendingAction());
            ps.setString(5, wm.getAttentionTarget());
            ps.setString(6, wm.getUserEmotions());
            ps.setString(7, wm.getConstraints());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) wm.setId(keys.getLong(1));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save WorkingMemory", e);
        }
    }

    /** 查询 Thread 当前活跃的工作记忆（最多一条）。 */
    public WorkingMemory findActiveByThread(long threadId) {
        String sql = "SELECT * FROM working_memory WHERE thread_id = ? AND active = 1 ORDER BY updated_at DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query active working memory", e);
        }
        return null;
    }

    /** 失效指定 Thread 的所有工作记忆。 */
    public void deactivateByThread(long threadId) {
        String sql = "UPDATE working_memory SET active = 0 WHERE thread_id = ? AND active = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, threadId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to deactivate working memory", e);
        }
    }

    private static WorkingMemory mapRow(ResultSet rs) throws SQLException {
        WorkingMemory wm = new WorkingMemory();
        wm.setId(rs.getLong("id"));
        wm.setThreadId(rs.getLong("thread_id"));
        wm.setGoal(rs.getString("goal"));
        wm.setContextSummary(rs.getString("context_summary"));
        wm.setPendingAction(rs.getString("pending_action"));
        wm.setAttentionTarget(rs.getString("attention_target"));
        wm.setUserEmotions(rs.getString("user_emotions"));
        wm.setConstraints(rs.getString("constraints"));
        wm.setActive(rs.getBoolean("active"));
        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) wm.setCreatedAt(ca.toInstant());
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) wm.setUpdatedAt(ua.toInstant());
        return wm;
    }
}
