package com.start.memory;

import com.hankcs.hanlp.HanLP;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆语义相似度：纯 Java 静态工具，不引入新依赖。
 *
 * 目标：解决 LIKE '%xxx%' 召回不到"我家新养了只英短" vs "用户养了只橘猫小柴"
 * 这种语义近似但字面不匹配的问题。
 *
 * 算法（两层加权）：
 *   score = 0.6 * jaccard(keywordsA, keywordsB)
 *         + 0.4 * charNgramOverlap(contentA, contentB, n=3)
 *
 * - Jaccard 处理"同义/同类"情况（橘猫/英短 都属于"猫"概念，关键词重叠率高）
 * - n-gram 处理"短文本字符级相似"（弥补 Jaccard 对未登录词的低敏感）
 * - keywords 为空时退化为纯 n-gram（LLM 偶尔忘传 keywords）
 *
 * 阈值（在 RememberFactTool 决定行为）：
 *   ≥ 0.50 强相似 → upsertConfirm（强化已有）
 *   0.30~0.50 中等相似 → 仍走 insert（不同主题）
 *   < 0.30 弱相似 → 直接 insert（新记忆）
 */
public final class SemanticSimilarity {

    private static final int NGRAM_SIZE = 3;
    private static final double KEYWORD_WEIGHT = 0.6;
    private static final double NGRAM_WEIGHT = 0.4;

    private SemanticSimilarity() {}

    /**
     * 用 HanLP 提取新内容的关键词，封装为"内容 + 关键词"对。
     * LLM 也可能传 keywords（来自 remember_fact 工具参数），优先使用 LLM 提供的。
     */
    public static String[] extractKeywords(String content, String llmKeywords) {
        if (llmKeywords != null && !llmKeywords.isBlank()) {
            String[] parts = llmKeywords.split("[,，;；\\s]+");
            String[] cleaned = Arrays.stream(parts)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toArray(String[]::new);
            if (cleaned.length > 0) return cleaned;
        }
        if (content == null || content.isBlank()) return new String[0];
        try {
            List<String> kws = HanLP.extractKeyword(content, 5);
            if (kws == null) return new String[0];
            return kws.stream()
                    .filter(k -> k != null && !k.isBlank())
                    .distinct()
                    .toArray(String[]::new);
        } catch (Exception e) {
            return new String[0];
        }
    }

    /**
     * 计算两条记忆的综合相似度。返回 0.0 ~ 1.0。
     */
    public static double score(String contentA, String keywordsA,
                                String contentB, String keywordsB) {
        String[] ka = extractKeywords(contentA, keywordsA);
        String[] kb = extractKeywords(contentB, keywordsB);

        double j = (ka.length == 0 || kb.length == 0)
                ? 0.0
                : jaccard(ka, kb);

        double n = charNgramOverlap(
                contentA == null ? "" : contentA,
                contentB == null ? "" : contentB,
                NGRAM_SIZE);

        return KEYWORD_WEIGHT * j + NGRAM_WEIGHT * n;
    }

    /**
     * 关键词集合的 Jaccard 相似度：|A ∩ B| / |A ∪ B|
     */
    public static double jaccard(String[] a, String[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0.0;
        Set<String> setA = new HashSet<>(Arrays.asList(a));
        Set<String> setB = new HashSet<>(Arrays.asList(b));
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        if (intersection.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return (double) intersection.size() / union.size();
    }

    /**
     * 字符 n-gram 重叠率：先把两边切成 n 字符的 gram 集合，再算 Jaccard。
     * n=3 时对中文短句粒度合适。
     */
    public static double charNgramOverlap(String a, String b, int n) {
        if (n < 1) return 0.0;
        String sa = normalize(a);
        String sb = normalize(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;
        if (sa.length() < n || sb.length() < n) {
            // 文本太短，n-gram 退化为"包含关系"：a 完全在 b 里得高分
            return sa.equals(sb) ? 1.0 : 0.0;
        }
        Set<String> gramsA = ngrams(sa, n);
        Set<String> gramsB = ngrams(sb, n);
        if (gramsA.isEmpty() || gramsB.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(gramsA);
        intersection.retainAll(gramsB);
        if (intersection.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(gramsA);
        union.addAll(gramsB);
        return (double) intersection.size() / union.size();
    }

    private static String normalize(String s) {
        if (s == null) return "";
        // 去标点 + 折叠空白，保留中英数字
        return s.replaceAll("[\\p{Punct}\\s]+", "").toLowerCase();
    }

    private static Set<String> ngrams(String s, int n) {
        Set<String> set = new HashSet<>();
        if (s.length() < n) {
            set.add(s);
            return set;
        }
        for (int i = 0; i <= s.length() - n; i++) {
            set.add(s.substring(i, i + n));
        }
        return Collections.unmodifiableSet(set);
    }
}
