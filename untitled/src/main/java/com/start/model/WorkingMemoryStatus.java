package com.start.model;

/**
 * 工作记忆生命周期状态。WorkingMemory 是"白板"不是"档案柜"，必须有完成机制。
 *
 * - ACTIVE    进行中：默认状态，会注入 Prompt
 * - COMPLETED LLM 主动关闭：任务结束，从 Prompt 消失（保留 DB 行供审计）
 * - EXPIRED   超时未完成：自动从 Prompt 消失（保留 DB 行供审计）
 */
public enum WorkingMemoryStatus {
    ACTIVE,
    COMPLETED,
    EXPIRED
}
