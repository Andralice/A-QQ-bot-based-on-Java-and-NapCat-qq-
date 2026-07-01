package com.start.memory;

/** 记忆状态：追踪认知演化 */
public enum MemoryStatus {
    /** 经多次确认，高度可信 */
    CONFIRMED(5),
    /** 近期创建或确认，当前有效 */
    ACTIVE(4),
    /** 长期未确认，可信度下降 */
    UNCERTAIN(3),
    /** 超期未交互，大概率过期 */
    OUTDATED(2),
    /** 被更新的记忆否定 */
    CONTRADICTED(1);

    private final int priority;

    MemoryStatus(int priority) { this.priority = priority; }

    /** 召回排序优先级，越高越靠前 */
    public int priority() { return priority; }
}
