package com.start.thread;

import com.start.model.ConversationThread;
import java.util.List;

/** 群聊场景快照，由 Thread 列表计算得到。计算视图，不持久化。 */
public record SceneState(
    List<ConversationThread> activeThreads,
    String atmosphere,
    String focusedThreadTopic,
    int totalActiveThreads
) {
    public static final SceneState EMPTY = new SceneState(List.of(), "", "", 0);

    public boolean isEmpty() {
        return activeThreads.isEmpty();
    }
}
