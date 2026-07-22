package com.start.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 糖果熊事件流：记录一天中实际发生的事。日程执行、聊天互动、心情变化都写入此表。 */
public class CandyBearEventLog {
    private Long id;
    private LocalDateTime eventTime;
    private LocalDate eventDate;
    private String eventType = "MANUAL";  // SCHEDULE / CHAT / MOOD / MANUAL
    private String summary;
    private String emotion = "";
    private int emotionImpact = 0;        // -5 ~ +5
    private String sourceGroupId = "";
    private String sourceUserId = "";
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }

    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getEmotion() { return emotion; }
    public void setEmotion(String emotion) { this.emotion = emotion; }

    public int getEmotionImpact() { return emotionImpact; }
    public void setEmotionImpact(int emotionImpact) { this.emotionImpact = emotionImpact; }

    public String getSourceGroupId() { return sourceGroupId; }
    public void setSourceGroupId(String sourceGroupId) { this.sourceGroupId = sourceGroupId; }

    public String getSourceUserId() { return sourceUserId; }
    public void setSourceUserId(String sourceUserId) { this.sourceUserId = sourceUserId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
