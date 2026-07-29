package com.start.model;

import java.time.Instant;

/**
 * 工作记忆 — 绑定 Thread 的任务级认知。不是 CoT，是 Agent 对"我在这个 Thread 里正在做什么"的认知快照。
 *
 * 设计：白板，不是档案柜。必须有生命周期。
 * - status 控制是否注入 Prompt（ACTIVE 才注入）
 * - expiresAt 给 LLM 写入时一个"这条任务活多久"的锚点
 * - COMPLETED / EXPIRED 的行保留在 DB（不物理删除），供审计
 *
 * active 字段保留以兼容老数据读取路径，但写入路径全部走 status。
 */
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
    private WorkingMemoryStatus status; // 生命周期状态：ACTIVE / COMPLETED / EXPIRED
    private Instant expiresAt;         // TTL 过期时间。null 表示使用 BotConfig.workingMemoryDefaultExpireHours 默认值
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
    public WorkingMemoryStatus getStatus() { return status; }
    public void setStatus(WorkingMemoryStatus status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
