package com.start.memory;

/** 记忆类型：决定召回策略和 TTL 行为 */
public enum MemoryType {
    /** 身份事实，几乎不变（"Alice 是后端工程师"） */
    IDENTITY,
    /** 偏好，可能变化（"Alice 喜欢奶茶"） */
    PREFERENCE,
    /** 事件，过期后不再主动提及（"Alice 昨天发烧了"） */
    EVENT,
    /** 未来计划（"Alice 下个月考研"） */
    PLAN,
    /** 情绪状态，很快失效（"Alice 今天很难过"） */
    EMOTION;

    /** 将旧的 String memory_type 转为新枚举 */
    public static MemoryType fromLegacy(String legacy) {
        if (legacy == null) return EVENT;
        return switch (legacy.toLowerCase()) {
            case "fact", "identity", "relation" -> IDENTITY;
            case "preference" -> PREFERENCE;
            case "event" -> EVENT;
            case "plan" -> PLAN;
            case "emotion" -> EMOTION;
            default -> EVENT;
        };
    }
}
