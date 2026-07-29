package com.start.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SemanticSimilarity 单元测试。
 *
 * 覆盖 6 类场景：
 *   1. 完全相同 → 1.0
 *   2. 完全无关 → < 0.3
 *   3. 字面近似 → > 0.5
 *   4. 关键词 Jaccard（用 keywords 参数）
 *   5. keywords 为空退化 n-gram
 *   6. 边界（空串、短文本）
 */
class SemanticSimilarityTest {

    @Test
    void identicalContent_returnsOne() {
        double s = SemanticSimilarity.score(
                "我家养了只猫", "猫,养",
                "我家养了只猫", "猫,养");
        assertEquals(1.0, s, 1e-6, "完全相同应该是 1.0");
    }

    @Test
    void unrelatedContent_lowScore() {
        double s = SemanticSimilarity.score(
                "今天天气真好", "天气,好",
                "我家养了只猫", "猫,养");
        assertTrue(s < 0.3, "无关内容应该 < 0.3，实际: " + s);
    }

    @Test
    void differentBreeds_keptAsDistinct() {
        // "英短"和"橘猫"是不同的品种，应该判定为不同记忆（不 upsert）
        double s = SemanticSimilarity.score(
                "我家新养了只英短", "英短,猫,养",
                "用户养了只橘猫小柴", "橘猫,猫,养");
        // Jaccard: {猫,养}/{英短,猫,养,橘猫} = 0.5
        // n-gram: "养了只" 重叠 1 个 → 较小
        // 总分约 0.33 → < 0.50 阈值 → 走 insert 而非 upsert
        assertTrue(s > 0.2 && s < 0.45,
                "英短 vs 橘猫应该中等相似（不合并），实际: " + s);
    }

    @Test
    void slightlyDifferentContent_highScore() {
        // 字面接近但不完全相同
        double s = SemanticSimilarity.score(
                "我喜欢喝奶茶", "奶茶,喜欢",
                "我喜欢喝奶茶啊", "奶茶,喜欢,啊");
        assertTrue(s > 0.6, "字面近似应该 > 0.6，实际: " + s);
    }

    @Test
    void differentAnimals_lowScore() {
        // "养了只猫" vs "养了只狗" — 关键词只共享"养"，n-gram 共享"养了只"
        double s = SemanticSimilarity.score(
                "我家养了只猫", "猫,养",
                "我家养了只狗", "狗,养");
        // 弱相似，应该 < 0.5（不同主题，不应被 upsert）
        assertTrue(s < 0.5, "不同动物应该 < 0.5，实际: " + s);
    }

    @Test
    void emptyKeywords_fallsBackToNgram() {
        // keywords 都为空，应该退化到纯字符 n-gram
        double s = SemanticSimilarity.score("我家养了只猫", null, "我家养了只猫", "");
        assertEquals(1.0, s, 1e-6);
    }

    @Test
    void emptyContent_returnsZero() {
        double s = SemanticSimilarity.score("", "", "abc", "def");
        assertEquals(0.0, s, 1e-6);
    }

    @Test
    void shortText_handlesBoundary() {
        // 短文本不会因为 n-gram 切不出 n 个字符而崩溃
        double s1 = SemanticSimilarity.score("a", "a", "a", "a");
        assertEquals(1.0, s1, 1e-6);

        double s2 = SemanticSimilarity.score("a", null, "b", null);
        assertEquals(0.0, s2, 1e-6);
    }

    @Test
    void jaccard_basicMath() {
        // 纯算法验证：Jaccard 应该 |A ∩ B| / |A ∪ B|
        // a={x,y,z}, b={y,z,w} → 2/4 = 0.5
        double s = SemanticSimilarity.jaccard(
                new String[]{"x", "y", "z"},
                new String[]{"y", "z", "w"});
        assertEquals(0.5, s, 1e-6);
    }

    @Test
    void jaccard_emptyInput_returnsZero() {
        assertEquals(0.0, SemanticSimilarity.jaccard(new String[0], new String[]{"a"}));
        assertEquals(0.0, SemanticSimilarity.jaccard(new String[]{"a"}, new String[0]));
        assertEquals(0.0, SemanticSimilarity.jaccard(null, null));
    }

    @Test
    void charNgramOverlap_basicMath() {
        // "abcde" 3-gram: {"abc", "bcd", "cde"}
        // "bcdef" 3-gram: {"bcd", "cde", "def"}
        // 重叠 2，union 4 → 0.5
        double s = SemanticSimilarity.charNgramOverlap("abcde", "bcdef", 3);
        assertEquals(0.5, s, 1e-6);
    }

    @Test
    void extractKeywords_prefersLLMProvided() {
        // LLM 传了 keywords → 应该用 LLM 的，不用 HanLP
        String[] kws = SemanticSimilarity.extractKeywords("这是一段新内容", "关键词1,关键词2");
        assertEquals(2, kws.length);
        assertEquals("关键词1", kws[0]);
        assertEquals("关键词2", kws[1]);
    }

    @Test
    void extractKeywords_fallsBackToHanLP() {
        // LLM 没传 → 用 HanLP 提取
        String[] kws = SemanticSimilarity.extractKeywords(
                "今天上海的天气真不错，温度 25 度", null);
        // HanLP 应该至少提取到 1 个关键词
        assertTrue(kws.length >= 1, "HanLP 应该能提取关键词");
    }
}
