package com.start.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行时事件总线。AIHandler 在关键节点触发事件，Listener 消费事件。
 * 不是消息处理入口——AIHandler 仍然是唯一的 Handler，EventBus 只负责横切关注点分发。
 */
public class ConversationRuntime {
    private static final Logger logger = LoggerFactory.getLogger(ConversationRuntime.class);

    private final List<RuntimeListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(RuntimeListener l) { listeners.add(l); }
    public void removeListener(RuntimeListener l) { listeners.remove(l); }

    public void fire(RuntimeEvent e) {
        for (RuntimeListener l : listeners) {
            try {
                l.onEvent(e);
            } catch (Exception ex) {
                logger.warn("Listener {} failed: {}", l.getClass().getSimpleName(), ex.getMessage());
            }
        }
    }
}
