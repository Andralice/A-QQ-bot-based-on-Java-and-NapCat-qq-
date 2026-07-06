package com.start.model;

import java.time.Instant;

/** 工作记忆 — 绑定 Thread 的任务级认知。不是 CoT，是 Agent 对"我在这个 Thread 里正在做什么"的认知快照。 */
public class WorkingMemory {
    private Long id;
    private Long threadId;
    private String goal;              // 正在解决什么
    private String contextSummary;    // 已经讨论到哪里
    private String pendingAction;     // 等待谁 / 等什么
    private String attentionTarget;   // 当前关注谁（QQ号或名字）
    private String userEmotions;      // JSON: {"qq1":"焦虑","qq2":"平静"}
    private String constraints;        // 不能忘记的限制条件
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getThreadId() { return threadId; }
    public void setThreadId(Long threadId) { this.threadId = threadId; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getContextSummary() { return contextSummary; }
    public void setContextSummary(String contextSummary) { this.contextSummary = contextSummary; }
    public String getPendingAction() { return pendingAction; }
    public void setPendingAction(String pendingAction) { this.pendingAction = pendingAction; }
    public String getAttentionTarget() { return attentionTarget; }
    public void setAttentionTarget(String attentionTarget) { this.attentionTarget = attentionTarget; }
    public String getUserEmotions() { return userEmotions; }
    public void setUserEmotions(String userEmotions) { this.userEmotions = userEmotions; }
    public String getConstraints() { return constraints; }
    public void setConstraints(String constraints) { this.constraints = constraints; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
