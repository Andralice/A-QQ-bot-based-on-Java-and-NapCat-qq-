package com.start.memory;

/** 记忆召回结果：经 MemoryInterpreter 翻译后的富对象，供 PromptBuilder 渲染叙事语言 */
public record MemoryRecall(
        String content,
        MemoryType type,
        String ageText,
        double confidence,
        MemoryStatus status,
        MemorySource source,
        boolean recentlyConfirmed,
        String stabilityHint) {

    /** 按召回优先级比较：状态 > 置信度 */
    public static int compareByPriority(MemoryRecall a, MemoryRecall b) {
        int cmp = Integer.compare(b.status.priority(), a.status.priority());
        if (cmp != 0) return cmp;
        cmp = Double.compare(b.confidence, a.confidence);
        return cmp;
    }
}
