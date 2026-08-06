package com.start.repository;

import com.start.model.ToolAuditLog;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

/**
 * 工具审计日志仓储。单一职责：写入 tool_audit_logs。
 * 读取/统计查询放在专用方法，按需添加。
 */
public class ToolAuditLogRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public ToolAuditLogRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 写入一条审计日志。调用方负责 truncate 字段长度。 */
    public void insert(ToolAuditLog log) throws SQLException {
        String sql = "INSERT INTO tool_audit_logs (tool_name, caller_user_id, group_id, session_id, " +
                "args_summary, result_summary, rejected, success, error_message, latency_ms, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, log.getToolName());
            setNullableString(ps, 2, log.getCallerUserId());
            setNullableString(ps, 3, log.getGroupId());
            setNullableString(ps, 4, log.getSessionId());
            setNullableString(ps, 5, log.getArgsSummary());
            setNullableString(ps, 6, log.getResultSummary());
            ps.setBoolean(7, log.isRejected());
            ps.setBoolean(8, log.isSuccess());
            setNullableString(ps, 9, log.getErrorMessage());
            ps.setLong(10, log.getLatencyMs());
            ps.setTimestamp(11, log.getCreatedAt() != null
                    ? Timestamp.valueOf(log.getCreatedAt()) : new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    private static void setNullableString(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.VARCHAR);
        } else {
            ps.setString(idx, value);
        }
    }
}
