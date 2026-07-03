package com.start.runtime;

import com.start.service.GenerationResult;

/** 运行时生命周期事件。AIHandler 在关键节点触发，Listener 消费。 */
public sealed interface RuntimeEvent {

    /** 收到一条群消息 */
    record MessageReceived(String groupId, String userId, String text) implements RuntimeEvent {}

    /** AI 生成完成、结果已提交。result 为 null 表示无生成（如直接回复）。 */
    record CommitFinished(String groupId, String userId, GenerationResult result,
                          long latencyMs) implements RuntimeEvent {}
}
