package com.start.runtime.sticker;

import com.start.runtime.RuntimeEvent;
import com.start.runtime.RuntimeListener;
import com.start.service.StickerIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 表情包自动入库 Listener。
 *
 * <p>消费 {@link RuntimeEvent.ImageReceived}（消息接收阶段触发，与 bot 是否被唤醒无关），
 * 把群/私聊里收到的图片异步入库到 {@link StickerIngestService}。
 * 下载、vision 描述、入库判断都在 StickerIngestService 内部完成——不依赖 conversation 跑。
 * 不持可变状态。
 */
public class StickerHarvesterListener implements RuntimeListener {

    private static final Logger logger = LoggerFactory.getLogger(StickerHarvesterListener.class);

    @Override
    public void onEvent(RuntimeEvent e) {
        if (!(e instanceof RuntimeEvent.ImageReceived r)) {
            return;
        }
        try {
            StickerIngestService.getInstance().harvestFromUrl(
                    r.groupId(), r.userId(), r.imageUrl());
        } catch (Exception ex) {
            logger.debug("sticker harvest 失败: {}", ex.getMessage());
        }
    }
}
