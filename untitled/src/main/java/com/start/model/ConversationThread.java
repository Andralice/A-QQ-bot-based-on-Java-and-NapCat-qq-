package com.start.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 群聊话题线程。群聊的认知对象是 Event，不是 Person。Thread 是群内事件/话题的载体。 */
public class ConversationThread {
    private Long id;
    private String groupId;
    private String topic;
    private String status;          // ACTIVE, IDLE, RESOLVED, MERGED
    private Long mergedIntoId;
    private String participantIds;  // JSON array: ["qq1","qq2"]
    private Instant lastMessageAt;
    private int messageCount;
    private double attentionWeight;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getMergedIntoId() { return mergedIntoId; }
    public void setMergedIntoId(Long mergedIntoId) { this.mergedIntoId = mergedIntoId; }
    public String getParticipantIds() { return participantIds; }
    public void setParticipantIds(String participantIds) { this.participantIds = participantIds; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public double getAttentionWeight() { return attentionWeight; }
    public void setAttentionWeight(double attentionWeight) { this.attentionWeight = attentionWeight; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** 解析 participantIds JSON 为列表。 */
    public List<String> getParticipants() {
        if (participantIds == null || participantIds.isBlank()) return List.of();
        String inner = participantIds.trim();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        if (inner.isBlank()) return List.of();
        List<String> list = new ArrayList<>();
        for (String part : inner.split(",")) {
            String id = part.trim().replace("\"", "");
            if (!id.isEmpty()) list.add(id);
        }
        return list;
    }

    /** 序列化参与者列表为 JSON 数组字符串。 */
    public void setParticipants(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            this.participantIds = "[]";
            return;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(ids.get(i)).append("\"");
        }
        sb.append("]");
        this.participantIds = sb.toString();
    }
}
