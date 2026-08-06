package com.start.model;

import java.time.LocalDateTime;

/**
 * 工具调用审计记录。
 * 由 ToolAuditService 写入 tool_audit_logs 表，用于追溯、调试、滥用检测。
 */
public class ToolAuditLog {
    private Long id;
    private String toolName;
    private String callerUserId;
    private String groupId;
    private String sessionId;
    private String argsSummary;       // 截断到 500 字符
    private String resultSummary;     // 截断到 1000 字符
    private boolean rejected = false; // 是否被授权层拒绝
    private boolean success = true;   // 是否执行成功
    private String errorMessage;
    private long latencyMs;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getCallerUserId() { return callerUserId; }
    public void setCallerUserId(String callerUserId) { this.callerUserId = callerUserId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getArgsSummary() { return argsSummary; }
    public void setArgsSummary(String argsSummary) { this.argsSummary = argsSummary; }

    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }

    public boolean isRejected() { return rejected; }
    public void setRejected(boolean rejected) { this.rejected = rejected; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
