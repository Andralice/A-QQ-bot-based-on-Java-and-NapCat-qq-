package com.start.runtime;

import com.start.model.DecisionContext;
import com.start.service.GenerationResult;

/** 运行时生命周期事件。AIHandler 在关键节点触发，Listener 消费。 */
public sealed interface RuntimeEvent {

    /** 收到一条群消息 */
    record MessageReceived(String groupId, String userId, String text) implements RuntimeEvent {}

    /** AI 生成完成、结果已提交。result 为 null 表示无生成（如直接回复）。context 为决策输入快照，Replay 用。 */
    record CommitFinished(String groupId, String userId, GenerationResult result,
                          long latencyMs, DecisionContext context) implements RuntimeEvent {

        /** 兼容旧调用（无 DecisionContext 时）。 */
        public CommitFinished(String groupId, String userId, GenerationResult result, long latencyMs) {
            this(groupId, userId, result, latencyMs, null);
        }
    }

    /**
     * 一条消息里包含图片（消息接收阶段触发，与 bot 是否被唤醒/速率限制/无关键 prompt 无关）。
     * StickerHarvesterListener 消费此事件，自己做下载 + vision + 入库——不依赖 conversation 流程。
     * 这样未被唤醒的图片也能进 sticker 库。
     *
     * <p>groupId 可能是 "private"（私聊消息没 groupId 时）或 null（未指定）。
     */
    record ImageReceived(String groupId, String userId, String imageUrl) implements RuntimeEvent {}

    /**
     * 图片已被 Vision 模型描述完成。AIHandler 在调完 describeImages 后触发。
     * 保留以便其他 listener 复用（例如未来想在 desc 完成后做别的事）。
     *
     * <p>groupId 可能是 "private"（私聊消息没 groupId 时）或 null（未指定）。
     * description 是 Vision 模型对图片的自然语言描述（可能是多张图的拼接）。
     */
    record ImageDescribed(String groupId, String userId, String imageUrl, String description) implements RuntimeEvent {}
}
