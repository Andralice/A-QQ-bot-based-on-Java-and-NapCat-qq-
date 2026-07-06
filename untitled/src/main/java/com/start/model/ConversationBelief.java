package com.start.model;

import java.time.Instant;

/** 会话认知快照。不是 CoT，是 Agent 对外部世界的认知状态。 */
public class ConversationBelief {
    private Long id;
    private String groupId;
    private String userId;
    private String topic;
    private String userEmotion;
    private String botIntent;
    private String unresolvedQuestion;
    private String relationshipState;
    private Instant createdAt;
    private Instant updatedAt;
    private boolean active;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getUserEmotion() { return userEmotion; }
    public void setUserEmotion(String userEmotion) { this.userEmotion = userEmotion; }
    public String getBotIntent() { return botIntent; }
    public void setBotIntent(String botIntent) { this.botIntent = botIntent; }
    public String getUnresolvedQuestion() { return unresolvedQuestion; }
    public void setUnresolvedQuestion(String unresolvedQuestion) { this.unresolvedQuestion = unresolvedQuestion; }
    public String getRelationshipState() { return relationshipState; }
    public void setRelationshipState(String relationshipState) { this.relationshipState = relationshipState; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
