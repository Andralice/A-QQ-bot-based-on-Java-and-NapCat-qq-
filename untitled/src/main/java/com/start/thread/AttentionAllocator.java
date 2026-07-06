package com.start.thread;

import com.start.model.ConversationThread;

import java.time.Duration;
import java.time.Instant;

/**
 * Thread 注意力权重计算。
 *
 * 权重 = recency(0.4) + 参与人数(0.3) + bot是否参与(0.3)
 *
 * recency: 最近一条消息距今越近，权重越高
 *   5min 内 → 1.0，每过 5min 衰减 0.2，最低 0.1
 */
public class AttentionAllocator {

    /**
     * 计算单条 Thread 的注意力权重。
     * @param thread  当前 Thread
     * @param botQq   bot 的 QQ 号
     * @return 0.0 ~ 1.0 的权重值
     */
    public double calculate(ConversationThread thread, long botQq) {
        double recency = calcRecency(thread.getLastMessageAt());
        double participants = calcParticipants(thread.getParticipants().size());
        double botInvolved = thread.getParticipants().contains(String.valueOf(botQq)) ? 1.0 : 0.0;

        return 0.4 * recency + 0.3 * participants + 0.3 * botInvolved;
    }

    private double calcRecency(Instant lastMessageAt) {
        if (lastMessageAt == null) return 0.1;
        long minutes = Duration.between(lastMessageAt, Instant.now()).toMinutes();
        if (minutes < 5) return 1.0;
        if (minutes < 10) return 0.8;
        if (minutes < 15) return 0.6;
        if (minutes < 20) return 0.4;
        if (minutes < 30) return 0.2;
        return 0.1;
    }

    private double calcParticipants(int count) {
        if (count >= 5) return 1.0;
        if (count >= 3) return 0.8;
        if (count >= 2) return 0.5;
        return 0.2;
    }
}
