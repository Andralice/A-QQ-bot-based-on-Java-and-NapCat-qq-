package com.start.model;

import java.time.LocalDateTime;

/**
 * 长期记忆：记录用户说过的事实、偏好、事件等，持久化到 long_term_memories 表。
 */
public class LongTermMemory {
    private Long id;
    private String userId;
    private String groupId;
    private Long sourceMessageId;
    private String content;
    private String memoryType = "fact";   // fact / preference / event / relation
    private String keywords;
    private int importance = 1;           // 1-5
    private LocalDateTime lastRecalled;
    private int recallCount = 0;
    private LocalDateTime triggerAt;         // 定时触发时间，非空表示定时事件
    private boolean triggered = false;       // 是否已触发
    private String source = "SELF_REPORTED"; // 记忆来源
    private LocalDateTime lastConfirmedAt;   // 最近确认时间
    private LocalDateTime lastSeenAt;        // 最近再次出现时间
    private LocalDateTime lastUsedAt;        // 最近被 Prompt 使用时间
    private double confidence = 1.0;         // 置信度 0.0-1.0
    private String status = "ACTIVE";        // 状态：ACTIVE/UNCERTAIN/OUTDATED/CONTRADICTED/CONFIRMED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long sourceMessageId) { this.sourceMessageId = sourceMessageId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public int getImportance() { return importance; }
    public void setImportance(int importance) { this.importance = importance; }

    public LocalDateTime getLastRecalled() { return lastRecalled; }
    public void setLastRecalled(LocalDateTime lastRecalled) { this.lastRecalled = lastRecalled; }

    public int getRecallCount() { return recallCount; }
    public void setRecallCount(int recallCount) { this.recallCount = recallCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getTriggerAt() { return triggerAt; }
    public void setTriggerAt(LocalDateTime triggerAt) { this.triggerAt = triggerAt; }

    public boolean isTriggered() { return triggered; }
    public void setTriggered(boolean triggered) { this.triggered = triggered; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public LocalDateTime getLastConfirmedAt() { return lastConfirmedAt; }
    public void setLastConfirmedAt(LocalDateTime lastConfirmedAt) { this.lastConfirmedAt = lastConfirmedAt; }

    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
