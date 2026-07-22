package com.start.memory;

/** 记忆来源：决定基础置信度 */
public enum MemorySource {
    /** 用户自己说的 — 可信度最高 */
    SELF_REPORTED,
    /** 群友说的 — 可信度较低 */
    OTHERS_SAID,
    /** 归儿确认的 — 最高可信度 */
    GUIER_CONFIRMED,
    /** 从知识库提取 */
    KNOWLEDGE_BASE,
    /** LLM 根据人设自动形成的设定 — 基础置信度较低，随确认次数提升 */
    AUTO_FORMED
}
