package com.start.repository;

import com.start.model.WorkingMemory;
import com.start.model.WorkingMemoryStatus;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作记忆存储。绑定 Thread，表在构造时自动创建。
 *
 * 白板模型：有生命周期。status=ACTIVE 且未过期 才注入 Prompt；
 * COMPLETED/EXPIRED 的行保留供审计（不物理删除）。
 */
public class WorkingMemoryRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public WorkingMemoryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
        ensureColumns();
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
                status VARCHAR(16) DEFAULT 'ACTIVE',
                expires_at TIMESTAMP NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_thread_active (thread_id, active),
                INDEX idx_thread_status (thread_id, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create working_memory table", e);
        }
    }

    /** 老表补字段：用 try-catch 防御（IF NOT EXISTS 在 MySQL 5.7 不支持） */
    private void ensureColumns() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE working_memory ADD COLUMN status VARCHAR(16) DEFAULT 'ACTIVE'");
        } catch (SQLException ignored) { /* 列已存在 */ }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE working_memory ADD COLUMN expires_at TIMESTAMP NULL");
        } catch (SQLException ignored) { /* 列已存在 */ }
        // 老数据的 active=1 迁移到 status=ACTIVE
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE working_memory SET status = 'ACTIVE' WHERE active = 1 AND (status IS NULL OR status = '')");
        } catch (SQLException ignored) {}
    }

    /** 保存新工作记忆（同时失效该 Thread 的 ACTIVE 旧记录）。 */
    public void save(WorkingMemory wm) {
        deactivateByThread(wm.getThreadId());
        String sql = "INSERT INTO working_memory (thread_id, goal, context_summary, pending_action, attention_target, user_emotions, constraints, active, status, expires_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, NOW(), NOW())";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, wm.getThreadId());
            ps.setString(2, wm.getGoal());
            ps.setString(3, wm.getContextSummary());
            ps.setString(4, wm.getPendingAction());
            ps.setString(5, wm.getAttentionTarget());
            ps.setString(6, wm.getUserEmotions());
            ps.setString(7, wm.getConstraints());
            ps.setString(8, wm.getStatus() != null ? wm.getStatus().name() : WorkingMemoryStatus.ACTIVE.name());
            if (wm.getExpiresAt() != null) {
                ps.setTimestamp(9, Timestamp.from(wm.getExpiresAt()));
            } else {
                ps.setNull(9, Types.TIMESTAMP);
            }
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) wm.setId(keys.getLong(1));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save WorkingMemory", e);
        }
    }

    /**
     * 查询 Thread 当前有效的工作记忆（最多一条）。
     * 有效 = status='ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
     */
    public WorkingMemory findActiveByThread(long threadId) {
        String sql = "SELECT * FROM working_memory WHERE thread_id = ? AND status = 'ACTIVE' " +
                "AND (expires_at IS NULL OR expires_at > NOW()) " +
                "ORDER BY updated_at DESC LIMIT 1";
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

    /**
     * LLM 主动标记任务完成。保留行（不物理删除），供审计。
     */
    public boolean markCompleted(long id) {
        String sql = "UPDATE working_memory SET status = 'COMPLETED', active = 0, updated_at = NOW() WHERE id = ? AND status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark working memory completed", e);
        }
    }

    /** 失效指定 Thread 的所有 ACTIVE 工作记忆（标记 COMPLETED）。 */
    public void deactivateByThread(long threadId) {
        String sql = "UPDATE working_memory SET status = 'COMPLETED', active = 0, updated_at = NOW() " +
                "WHERE thread_id = ? AND status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, threadId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to deactivate working memory", e);
        }
    }

    /**
     * 把所有过期的 ACTIVE 行批量标为 EXPIRED。
     * 由生命周期扫描器或启动时跑。
     */
    public int markExpired() {
        String sql = "UPDATE working_memory SET status = 'EXPIRED', active = 0, updated_at = NOW() " +
                "WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= NOW()";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark expired working memory", e);
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
        try {
            String s = rs.getString("status");
            if (s != null) wm.setStatus(WorkingMemoryStatus.valueOf(s));
        } catch (SQLException | IllegalArgumentException ignored) {}
        try {
            Timestamp ea = rs.getTimestamp("expires_at");
            if (ea != null) wm.setExpiresAt(ea.toInstant());
        } catch (SQLException ignored) {}
        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) wm.setCreatedAt(ca.toInstant());
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) wm.setUpdatedAt(ua.toInstant());
        return wm;
    }
}
