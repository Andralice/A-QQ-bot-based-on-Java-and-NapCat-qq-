package com.start.service;

import java.util.List;

/** Conservative detector for feedback explicitly aimed at CandyBear. */
public final class ProactiveFeedbackDetector {

    private ProactiveFeedbackDetector() {}

    public static boolean isDirectedNegativeFeedback(String message, List<Long> atUserIds, long botQq) {
        if (message == null || message.isBlank()) return false;
        String text = message.trim().toLowerCase();
        boolean directed = atUserIds != null && atUserIds.contains(botQq)
                || text.contains("糖果熊");
        if (!directed) return false;
        return text.contains("闭嘴") || text.contains("别说话") || text.contains("别插话")
                || text.contains("好吵") || text.contains("无语") || text.contains("烦死了");
    }
}
