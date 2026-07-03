package com.start.runtime;

/** 运行时事件监听器。通过 ConversationRuntime.addListener 注册。 */
public interface RuntimeListener {
    default void onEvent(RuntimeEvent e) {}
}
