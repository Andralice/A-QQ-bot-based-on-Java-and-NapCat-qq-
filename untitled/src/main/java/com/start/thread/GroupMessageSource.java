package com.start.thread;

import java.util.List;

/** 群消息窗口数据源。ThreadManager 通过此接口读取群最近消息，不直接依赖 BaiLianService。 */
@FunctionalInterface
public interface GroupMessageSource {
    List<String> getRecentMessages(String groupId, int count);
}
