package com.start.memory;

import com.start.model.LongTermMemory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 记忆认知翻译层。把数据库记录翻译成人脑里的"印象"。
 * 纯机械规则，不做语义决策（符合 CLAUDE.md 第 1 条）。
 */
public class MemoryInterpreter {

    private final TimeLanguage timeLang;
    private final Clock clock;

    public MemoryInterpreter() {
        this(new TimeLanguage(), Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public MemoryInterpreter(TimeLanguage timeLang, Clock clock) {
        this.timeLang = timeLang;
        this.clock = clock;
    }

    /** 批量翻译 */
    public List<MemoryRecall> interpretAll(List<LongTermMemory> entities) {
        if (entities == null || entities.isEmpty()) return Collections.emptyList();

        List<MemoryRecall> recalls = new ArrayList<>(entities.size());
        for (LongTermMemory m : entities) {
            recalls.add(interpret(m, entities));
        }
        return recalls;
    }

    /** 翻译单条记忆 */
    public MemoryRecall interpret(LongTermMemory m, List<LongTermMemory> allUserMemories) {
        MemoryType type = MemoryType.fromLegacy(m.getMemoryType());
        MemorySource source = parseSource(m.getSource());
        MemoryStatus status = determineStatus(m, allUserMemories);
        double confidence = computeConfidence(m, source, status);
        String ageText = timeLang.describeAge(m.getCreatedAt(), m.getLastConfirmedAt(), m.getLastSeenAt());
        String stabilityHint = generateStabilityHint(m, status, confidence);
        boolean recentlyConfirmed = m.getLastConfirmedAt() != null
                && Duration.between(m.getLastConfirmedAt(), LocalDateTime.now(clock)).toDays() <= 7;

        return new MemoryRecall(
                m.getContent(),
                type,
                ageText,
                confidence,
                status,
                source,
                recentlyConfirmed,
                stabilityHint);
    }

    // ---- confidence ----

    private double computeConfidence(LongTermMemory m, MemorySource source, MemoryStatus status) {
        double base = switch (source) {
            case SELF_REPORTED -> 0.9;
            case OTHERS_SAID -> 0.45;
            case GUIER_CONFIRMED -> 0.95;
            case KNOWLEDGE_BASE -> 0.7;
        };

        LocalDateTime lastSignal = m.getLastConfirmedAt() != null ? m.getLastConfirmedAt() : m.getCreatedAt();
        if (lastSignal != null) {
            long days = Duration.between(lastSignal, LocalDateTime.now(clock)).toDays();
            if (days > 365) base -= 0.25;
            else if (days > 180) base -= 0.15;
            else if (days > 90) base -= 0.1;
            else if (days > 30) base -= 0.05;
        }

        double boost = Math.min(m.getRecallCount() * 0.03, 0.1);
        base += boost;

        if (status == MemoryStatus.CONTRADICTED && base > 0.3) base = 0.3;

        double clamped = Math.max(0.0, Math.min(1.0, base));
        return Math.round(clamped * 100.0) / 100.0;
    }

    // ---- status ----

    private MemoryStatus determineStatus(LongTermMemory m, List<LongTermMemory> allUserMemories) {
        // DB stored status overrides initial calculation
        String dbStatus = m.getStatus();
        if (dbStatus != null) {
            try {
                return MemoryStatus.valueOf(dbStatus.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 检查是否有更新的矛盾记忆
        if (allUserMemories != null && allUserMemories.size() > 1 && m.getCreatedAt() != null) {
            Set<String> myKeywords = splitKeywords(m.getKeywords());
            for (LongTermMemory other : allUserMemories) {
                if (other.getId() != null && other.getId().equals(m.getId())) continue;
                if (other.getCreatedAt() == null) continue;
                if (!other.getCreatedAt().isAfter(m.getCreatedAt())) continue;

                Set<String> otherKeywords = splitKeywords(other.getKeywords());
                if (!Collections.disjoint(myKeywords, otherKeywords)) {
                    if (myKeywords.size() >= 2 && otherKeywords.size() >= 2) {
                        return MemoryStatus.CONTRADICTED;
                    }
                }
            }
        }

        if (m.getCreatedAt() == null) return MemoryStatus.ACTIVE;

        long daysSinceCreate = Duration.between(m.getCreatedAt(), now).toDays();

        if (m.getLastConfirmedAt() != null) {
            long daysSinceConfirm = Duration.between(m.getLastConfirmedAt(), now).toDays();
            if (daysSinceConfirm <= 30 && daysSinceCreate > 30) return MemoryStatus.CONFIRMED;
            if (daysSinceConfirm <= 90) return MemoryStatus.ACTIVE;
            if (daysSinceConfirm > 90) return MemoryStatus.UNCERTAIN;
        }

        if (daysSinceCreate <= 30) return MemoryStatus.ACTIVE;

        // 检查 lastSeenAt
        LocalDateTime lastInteraction = m.getLastSeenAt() != null ? m.getLastSeenAt()
                : m.getUpdatedAt() != null ? m.getUpdatedAt() : m.getCreatedAt();
        long daysSinceInteraction = Duration.between(lastInteraction, now).toDays();
        if (daysSinceInteraction > 365) return MemoryStatus.OUTDATED;

        if (daysSinceCreate > 90) return MemoryStatus.UNCERTAIN;

        return MemoryStatus.ACTIVE;
    }

    // ---- stability hint ----

    private String generateStabilityHint(LongTermMemory m, MemoryStatus status, double confidence) {
        long daysSinceCreate = m.getCreatedAt() != null
                ? Duration.between(m.getCreatedAt(), LocalDateTime.now(clock)).toDays()
                : 0;

        return switch (status) {
            case CONFIRMED -> daysSinceCreate > 90 ? "一直都" : "";
            case ACTIVE -> daysSinceCreate <= 30 ? "最近" : "";
            case UNCERTAIN -> confidence < 0.5 ? "以前" : "好像";
            case OUTDATED -> confidence < 0.5 ? "很久以前" : "当时";
            case CONTRADICTED -> "曾经";
        };
    }

    // ---- helpers ----

    private MemorySource parseSource(String s) {
        if (s == null) return MemorySource.SELF_REPORTED;
        try {
            return MemorySource.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemorySource.SELF_REPORTED;
        }
    }

    private Set<String> splitKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) return Collections.emptySet();
        Set<String> set = new HashSet<>();
        for (String kw : keywords.split("[,，]")) {
            String t = kw.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }
}
