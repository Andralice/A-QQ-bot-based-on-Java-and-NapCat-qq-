package com.start.repository;

import com.start.model.CandyBearEventLog;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 糖果熊事件流的数据访问层 */
public class EventLogRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public EventLogRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 插入一条事件 */
    public void insert(CandyBearEventLog e) throws SQLException {
        String sql = "INSERT INTO candy_bear_event_log (event_time, event_date, event_type, summary, emotion, emotion_impact, source_group_id, source_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setTimestamp(1, e.getEventTime() != null ? Timestamp.valueOf(e.getEventTime()) : Timestamp.valueOf(LocalDateTime.now()));
            ps.setDate(2, Date.valueOf(e.getEventDate() != null ? e.getEventDate() : LocalDate.now()));
            ps.setString(3, e.getEventType() != null ? e.getEventType() : "MANUAL");
            ps.setString(4, e.getSummary());
            ps.setString(5, e.getEmotion() != null ? e.getEmotion() : "");
            ps.setInt(6, e.getEmotionImpact());
            ps.setString(7, e.getSourceGroupId() != null ? e.getSourceGroupId() : "");
            ps.setString(8, e.getSourceUserId() != null ? e.getSourceUserId() : "");
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) e.setId(keys.getLong(1));
        }
    }

    /** 查询某天全部事件，按时间排序 */
    public List<CandyBearEventLog> findByDate(LocalDate date) throws SQLException {
        String sql = "SELECT * FROM candy_bear_event_log WHERE event_date = ? ORDER BY event_time ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            return mapResults(ps);
        }
    }

    /** 查询最近 N 天的事件，按时间倒序 */
    public List<CandyBearEventLog> findRecent(int days) throws SQLException {
        String sql = "SELECT * FROM candy_bear_event_log WHERE event_date >= ? ORDER BY event_date DESC, event_time ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(LocalDate.now().minusDays(days - 1)));
            return mapResults(ps);
        }
    }

    /** 检查某天是否已有事件 */
    public boolean hasEvents(LocalDate date) throws SQLException {
        String sql = "SELECT COUNT(*) FROM candy_bear_event_log WHERE event_date = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private List<CandyBearEventLog> mapResults(PreparedStatement ps) throws SQLException {
        ResultSet rs = ps.executeQuery();
        List<CandyBearEventLog> list = new ArrayList<>();
        while (rs.next()) {
            CandyBearEventLog e = new CandyBearEventLog();
            e.setId(rs.getLong("id"));
            Timestamp et = rs.getTimestamp("event_time");
            if (et != null) e.setEventTime(et.toLocalDateTime());
            Date ed = rs.getDate("event_date");
            if (ed != null) e.setEventDate(ed.toLocalDate());
            e.setEventType(rs.getString("event_type"));
            e.setSummary(rs.getString("summary"));
            e.setEmotion(rs.getString("emotion") != null ? rs.getString("emotion") : "");
            e.setEmotionImpact(rs.getInt("emotion_impact"));
            e.setSourceGroupId(rs.getString("source_group_id") != null ? rs.getString("source_group_id") : "");
            e.setSourceUserId(rs.getString("source_user_id") != null ? rs.getString("source_user_id") : "");
            Timestamp ca = rs.getTimestamp("created_at");
            if (ca != null) e.setCreatedAt(ca.toLocalDateTime());
            list.add(e);
        }
        return list;
    }
}
