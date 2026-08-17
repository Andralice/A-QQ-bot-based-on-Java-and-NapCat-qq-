package com.start.service;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Decides whether a relevant group message is a good moment for CandyBear to join in.
 * The caller supplies randomness so the policy remains deterministic in tests.
 */
public final class ProactiveInterjectionPolicy {

    private static final int BUSY_MESSAGE_LIMIT = 5;
    private static final int BUSY_PARTICIPANT_LIMIT = 4;
    private static final int RECENT_AI_REPLY_LIMIT = 3;
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private final Clock clock;

    public ProactiveInterjectionPolicy() {
        this(Clock.system(SHANGHAI_ZONE));
    }

    ProactiveInterjectionPolicy(Clock clock) {
        this.clock = clock.withZone(SHANGHAI_ZONE);
    }

    public Decision assess(String message, ConversationMetrics.Snapshot snapshot, double randomValue) {
        return assess(message, snapshot, false, randomValue);
    }

    public Decision assess(String message, ConversationMetrics.Snapshot snapshot,
                           boolean negativeCooldown, double randomValue) {
        if (message == null || message.isBlank()) return Decision.skip("empty_message");
        if (negativeCooldown) return Decision.skip("negative_feedback");
        if (snapshot.messagesLast30s() > BUSY_MESSAGE_LIMIT
                || (snapshot.messagesLast30s() >= 4 && snapshot.activeParticipants() > BUSY_PARTICIPANT_LIMIT)) {
            return Decision.skip("group_is_active");
        }
        if (snapshot.aiMessagesLast5m() >= RECENT_AI_REPLY_LIMIT) {
            return Decision.skip("bot_spoke_recently");
        }

        double probability = 0.14;
        int length = message.trim().length();
        if (length >= 8 && length <= 80) probability += 0.07;
        if (isInvitation(message)) probability += 0.12;
        if (snapshot.messagesLast30s() <= 2) probability += 0.08;
        if (snapshot.activeParticipants() <= 2) probability += 0.04;
        probability = Math.max(0.08, Math.min(0.42, probability));
        int hour = clock.instant().atZone(SHANGHAI_ZONE).getHour();
        if (hour >= 23 || hour < 7) probability *= 0.5;

        return randomValue < probability
                ? Decision.schedule(probability)
                : Decision.skip("probability_miss", probability);
    }

    /** A delayed candidate is cancelled if a group becomes busy before it is sent. */
    public boolean remainsAppropriate(ConversationMetrics.Snapshot snapshot) {
        return remainsAppropriate(snapshot, false);
    }

    public boolean remainsAppropriate(ConversationMetrics.Snapshot snapshot, boolean negativeCooldown) {
        return snapshot.messagesLast30s() <= BUSY_MESSAGE_LIMIT
                && !(snapshot.messagesLast30s() >= 4 && snapshot.activeParticipants() > BUSY_PARTICIPANT_LIMIT)
                && snapshot.aiMessagesLast5m() < RECENT_AI_REPLY_LIMIT
                && !negativeCooldown;
    }

    private boolean isInvitation(String message) {
        String text = message.trim();
        return text.indexOf('?') >= 0 || text.indexOf('\uFF1F') >= 0
                || text.contains("大家") || text.contains("你们") || text.contains("有没有")
                || text.contains("怎么") || text.contains("觉得") || text.contains("推荐");
    }

    public record Decision(boolean shouldSchedule, String reason, double probability) {
        static Decision schedule(double probability) {
            return new Decision(true, "candidate", probability);
        }

        static Decision skip(String reason) {
            return skip(reason, 0.0);
        }

        static Decision skip(String reason, double probability) {
            return new Decision(false, reason, probability);
        }
    }
}
