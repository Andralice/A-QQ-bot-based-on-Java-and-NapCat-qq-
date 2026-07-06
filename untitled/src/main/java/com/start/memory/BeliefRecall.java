package com.start.memory;

/** 认知召回片段：Belief 经翻译后的富对象，供 PromptBuilder 渲染叙事语言。 */
public record BeliefRecall(
        String topic,
        String userEmotion,
        String botIntent,
        String unresolvedQuestion,
        int ageMinutes,
        boolean active) {
}
