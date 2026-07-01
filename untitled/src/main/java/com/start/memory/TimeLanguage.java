package com.start.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 自然语言时间翻译器。将精确时间戳转为人脑理解的时间表达。
 * 通过 Clock 注入实现可测试性。
 */
public class TimeLanguage {

    private final Clock clock;
    private final ZoneId zone;

    public TimeLanguage() {
        this(Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public TimeLanguage(Clock clock) {
        this.clock = clock;
        this.zone = clock.getZone();
    }

    /**
     * 描述记忆的时间感。
     * 如果最近确认过且创建已久 → 返回空（用 stabilityHint 表达时间感）。
     */
    public String describeAge(LocalDateTime createdAt, LocalDateTime lastConfirmedAt, LocalDateTime lastSeenAt) {
        if (createdAt == null) return "";

        LocalDateTime now = LocalDateTime.now(clock);

        // 如果最近确认过（7天内）且本条记忆创建超过30天 → 这是稳定偏好，不需要年龄
        if (lastConfirmedAt != null) {
            Duration sinceConfirm = Duration.between(lastConfirmedAt, now);
            Duration sinceCreate = Duration.between(createdAt, now);
            if (sinceConfirm.toDays() <= 7 && sinceCreate.toDays() > 30) {
                return "";
            }
        }

        return describeDuration(Duration.between(createdAt, now));
    }

    /** 将 Duration 翻译为中文自然语言 */
    public String describeDuration(Duration d) {
        long seconds = d.getSeconds();
        if (seconds < 0) seconds = 0;

        if (seconds < 60) return "刚刚";
        if (seconds < 3600) return (seconds / 60) + "分钟前";
        if (seconds < 21600) return (seconds / 3600) + "小时前";

        long hours = seconds / 3600;
        if (hours < 24) return "今天";
        if (hours < 48) return "昨天";
        if (hours < 72) return "前几天";

        long days = seconds / 86400;
        if (days < 7) return "上周";
        if (days < 30) return "几周前";
        if (days < 60) return "约两个月前";
        if (days < 180) return "约" + (days / 30) + "个月前";
        if (days < 365) return "约一年前";

        return "约" + (days / 365) + "年前";
    }
}
