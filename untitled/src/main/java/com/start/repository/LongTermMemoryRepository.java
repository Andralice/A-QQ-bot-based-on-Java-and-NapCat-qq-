package com.start.repository;

import com.start.model.LongTermMemory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class LongTermMemoryRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public LongTermMemoryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 插入一条新记忆（含可选的定时触发时间） */
    public void insert(LongTermMemory m) throws SQLException {
        String sql = "INSERT INTO long_term_memories (user_id, group_id, source_message_id, content, memory_type, keywords, importance, trigger_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, m.getUserId());
            ps.setString(2, m.getGroupId());
            if (m.getSourceMessageId() != null) ps.setLong(3, m.getSourceMessageId());
            else ps.setNull(3, Types.BIGINT);
            ps.setString(4, m.getContent());
            ps.setString(5, m.getMemoryType() != null ? m.getMemoryType() : "fact");
            ps.setString(6, m.getKeywords());
            ps.setInt(7, m.getImportance());
            if (m.getTriggerAt() != null) ps.setTimestamp(8, Timestamp.valueOf(m.getTriggerAt()));
            else ps.setNull(8, Types.TIMESTAMP);
            if (m.getExpiresAt() != null) ps.setTimestamp(9, Timestamp.valueOf(m.getExpiresAt()));
            else ps.setNull(9, Types.TIMESTAMP);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) m.setId(keys.getLong(1));
        }
    }

    /** 按用户+群检索记忆，关键词模糊匹配，排除已触发的定时事件 */
    public List<LongTermMemory> search(String userId, String groupId, String keyword, int limit) throws SQLException {
        return search(userId, groupId, keyword, limit, null, null);
    }

    /** 按用户+群检索记忆，支持日期范围过滤 */
    public List<LongTermMemory> search(String userId, String groupId, String keyword, int limit,
                                       String dateFrom, String dateTo) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM long_term_memories WHERE user_id = ? AND group_id ");
        boolean hasGroup = groupId != null && !groupId.isBlank();
        sql.append(hasGroup ? "= ? AND triggered = FALSE AND (expires_at IS NULL OR expires_at > NOW()) "
                         : "IS NULL AND triggered = FALSE AND (expires_at IS NULL OR expires_at > NOW()) ");
        List<String> params = new ArrayList<>();
        params.add(userId);
        if (hasGroup) params.add(groupId);

        if (keyword != null && !keyword.isBlank()) {
            sql.append("AND (content LIKE ? OR keywords LIKE ?) ");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }

        if (dateFrom != null && !dateFrom.isBlank()) {
            sql.append("AND created_at >= ? ");
            params.add(normalizeDateFrom(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sql.append("AND created_at <= ? ");
            params.add(normalizeDateTo(dateTo));
        }

        sql.append("ORDER BY importance DESC, recall_count DESC, created_at DESC LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            ps.setInt(params.size() + 1, limit);

            ResultSet rs = ps.executeQuery();
            List<LongTermMemory> results = new ArrayList<>();
            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;
        }
    }

    /** 按群组检索记忆（不限用户），关键词模糊匹配 */
    public List<LongTermMemory> searchByGroup(String groupId, String keyword, int limit) throws SQLException {
        return searchByGroup(groupId, keyword, limit, null, null);
    }

    /** 按群组检索记忆（不限用户），支持日期范围过滤 */
    public List<LongTermMemory> searchByGroup(String groupId, String keyword, int limit,
                                              String dateFrom, String dateTo) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM long_term_memories WHERE group_id ");
        boolean hasGroup = groupId != null && !groupId.isBlank();
        sql.append(hasGroup ? "= ? AND triggered = FALSE AND (expires_at IS NULL OR expires_at > NOW()) "
                         : "IS NULL AND triggered = FALSE AND (expires_at IS NULL OR expires_at > NOW()) ");
        List<String> params = new ArrayList<>();
        if (hasGroup) params.add(groupId);

        if (keyword != null && !keyword.isBlank()) {
            sql.append("AND (content LIKE ? OR keywords LIKE ?) ");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }

        if (dateFrom != null && !dateFrom.isBlank()) {
            sql.append("AND created_at >= ? ");
            params.add(normalizeDateFrom(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sql.append("AND created_at <= ? ");
            params.add(normalizeDateTo(dateTo));
        }

        sql.append("ORDER BY importance DESC, created_at DESC LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            ps.setInt(params.size() + 1, limit);

            ResultSet rs = ps.executeQuery();
            List<LongTermMemory> results = new ArrayList<>();
            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;
        }
    }

    /** 查询所有到期的定时事件（trigger_at <= NOW() AND triggered = FALSE） */
    public List<LongTermMemory> findDueEvents() throws SQLException {
        String sql = "SELECT * FROM long_term_memories WHERE trigger_at IS NOT NULL AND triggered = FALSE " +
                "AND trigger_at <= NOW() AND (trigger_claimed_at IS NULL OR trigger_claimed_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE)) " +
                "ORDER BY trigger_at ASC LIMIT 20";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            List<LongTermMemory> results = new ArrayList<>();
            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;
        }
    }

    /** 查用户/群最近记忆，用于矛盾检测的扩展上下文 */
    public List<LongTermMemory> findRecentByUser(String userId, String groupId, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM long_term_memories WHERE user_id = ? AND group_id ");
        boolean hasGroup = groupId != null && !groupId.isBlank();
        sql.append(hasGroup ? "= ? AND triggered = FALSE AND (expires_at IS NULL OR expires_at > NOW()) "
                         : "IS NULL AND triggered = FALSE AND (expires_at IS NULL OR expires_at > NOW()) ");
        sql.append("ORDER BY created_at DESC LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, userId);
            int idx = 2;
            if (hasGroup) ps.setString(idx++, groupId);
            ps.setInt(idx, limit);

            ResultSet rs = ps.executeQuery();
            List<LongTermMemory> results = new ArrayList<>();
            while (rs.next()) results.add(mapRow(rs));
            return results;
        }
    }

    /** 标记事件已触发 */
    public void markTriggered(long id) throws SQLException {
        String sql = "UPDATE long_term_memories SET triggered = TRUE, trigger_claimed_at = NULL WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 抢占一个到期事件，避免多个扫描周期或实例重复执行。 */
    public boolean claimDueEvent(long id) throws SQLException {
        String sql = "UPDATE long_term_memories SET trigger_claimed_at = NOW() " +
                "WHERE id = ? AND trigger_at IS NOT NULL AND triggered = FALSE AND trigger_at <= NOW() " +
                "AND (trigger_claimed_at IS NULL OR trigger_claimed_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE))";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    /** 发送失败时释放事件租约，允许下一轮扫描重试。 */
    public void releaseEventClaim(long id) throws SQLException {
        String sql = "UPDATE long_term_memories SET trigger_claimed_at = NULL " +
                "WHERE id = ? AND triggered = FALSE";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 更新召回时间和计数 */
    public void markRecalled(long id) throws SQLException {
        String sql = "UPDATE long_term_memories SET last_recalled = NOW(), recall_count = recall_count + 1 WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 标记记忆被 Prompt 使用 */
    public void markUsed(long id) throws SQLException {
        String sql = "UPDATE long_term_memories SET last_used_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 查找相似记忆，用于去重（插入前检查） */
    public List<LongTermMemory> findSimilar(String userId, String groupId, String content, String keywords) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM long_term_memories WHERE user_id = ? AND (content LIKE ? OR keywords LIKE ?) ");
        boolean hasGroup = groupId != null && !groupId.isBlank();
        sql.append(hasGroup ? "AND group_id = ? " : "AND group_id IS NULL ");
        sql.append("AND (expires_at IS NULL OR expires_at > NOW()) ");
        sql.append("ORDER BY created_at DESC LIMIT 5");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, userId);
            ps.setString(2, "%" + content + "%");
            ps.setString(3, "%" + (keywords != null ? keywords : content) + "%");
            if (hasGroup) ps.setString(4, groupId);

            ResultSet rs = ps.executeQuery();
            List<LongTermMemory> results = new ArrayList<>();
            while (rs.next()) results.add(mapRow(rs));
            return results;
        }
    }

    /** 确认已有记忆：更新确认时间和重要性 */
    public void upsertConfirm(long id) throws SQLException {
        String sql = "UPDATE long_term_memories SET last_confirmed_at = NOW(), last_seen_at = NOW(), "
                + "importance = LEAST(importance + 1, 5), status = 'CONFIRMED', updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 删除记忆 */
    public void delete(long id) throws SQLException {
        String sql = "DELETE FROM long_term_memories WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 查找同名已过期的事实（用于刷新而非重复插入） */
    public List<LongTermMemory> findExpiredByUser(String userId, String content) throws SQLException {
        String sql = "SELECT * FROM long_term_memories WHERE user_id = ? AND content LIKE ? "
                + "AND expires_at IS NOT NULL AND expires_at <= NOW() ORDER BY expires_at DESC LIMIT 3";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, "%" + content + "%");
            ResultSet rs = ps.executeQuery();
            List<LongTermMemory> results = new ArrayList<>();
            while (rs.next()) results.add(mapRow(rs));
            return results;
        }
    }

    /** 刷新过期事实：更新内容、关键词、有效期，重置为 ACTIVE */
    public void refreshExpired(long id, String content, String keywords, LocalDateTime expiresAt) throws SQLException {
        String sql = "UPDATE long_term_memories SET content = ?, keywords = ?, expires_at = ?, "
                + "last_confirmed_at = NOW(), last_seen_at = NOW(), status = 'ACTIVE', "
                + "confidence = 0.4, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, content);
            ps.setString(2, keywords);
            if (expiresAt != null) ps.setTimestamp(3, Timestamp.valueOf(expiresAt));
            else ps.setNull(3, Types.TIMESTAMP);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    /** "2026-06-05" → "2026-06-05 00:00:00" */
    private String normalizeDateFrom(String s) {
        s = s.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) s += " 00:00:00";
        return s;
    }
    private String normalizeDateTo(String s) {
        s = s.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) s += " 23:59:59";
        return s;
    }

    private LongTermMemory mapRow(ResultSet rs) throws SQLException {
        LongTermMemory m = new LongTermMemory();
        m.setId(rs.getLong("id"));
        m.setUserId(rs.getString("user_id"));
        m.setGroupId(rs.getString("group_id"));
        long srcMsgId = rs.getLong("source_message_id");
        if (!rs.wasNull()) m.setSourceMessageId(srcMsgId);
        m.setContent(rs.getString("content"));
        m.setMemoryType(rs.getString("memory_type"));
        m.setKeywords(rs.getString("keywords"));
        m.setImportance(rs.getInt("importance"));
        Timestamp lr = rs.getTimestamp("last_recalled");
        if (lr != null) m.setLastRecalled(lr.toLocalDateTime());
        m.setRecallCount(rs.getInt("recall_count"));
        Timestamp ta = rs.getTimestamp("trigger_at");
        if (ta != null) m.setTriggerAt(ta.toLocalDateTime());
        m.setTriggered(rs.getBoolean("triggered"));
        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) m.setCreatedAt(ca.toLocalDateTime());
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) m.setUpdatedAt(ua.toLocalDateTime());
        // 新列可能不存在（未执行迁移时），全部 try-catch 防御，使用默认值
        try { m.setSource(rs.getString("source") != null ? rs.getString("source") : "SELF_REPORTED"); } catch (SQLException ignored) {}
        try { Timestamp lca = rs.getTimestamp("last_confirmed_at"); if (lca != null) m.setLastConfirmedAt(lca.toLocalDateTime()); } catch (SQLException ignored) {}
        try { Timestamp lsa = rs.getTimestamp("last_seen_at"); if (lsa != null) m.setLastSeenAt(lsa.toLocalDateTime()); } catch (SQLException ignored) {}
        try { Timestamp lua = rs.getTimestamp("last_used_at"); if (lua != null) m.setLastUsedAt(lua.toLocalDateTime()); } catch (SQLException ignored) {}
        try { m.setConfidence(rs.getDouble("confidence")); if (rs.wasNull()) m.setConfidence(1.0); } catch (SQLException ignored) {}
        try { String st = rs.getString("status"); m.setStatus(st != null ? st : "ACTIVE"); } catch (SQLException ignored) {}
        try { Timestamp ea = rs.getTimestamp("expires_at"); if (ea != null) m.setExpiresAt(ea.toLocalDateTime()); } catch (SQLException ignored) {}
        return m;
    }
}
