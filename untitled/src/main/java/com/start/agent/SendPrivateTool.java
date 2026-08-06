package com.start.agent;

import com.start.Main;
import com.start.config.BotConfig;

import java.util.Arrays;
import java.util.Map;

/**
 * 发送私聊消息工具。用于游戏分发词语等场景。
 */
public class SendPrivateTool implements Tool {
    private final Main bot;
    private final String realUserId;

    public SendPrivateTool(Main bot, String realUserId) {
        this.bot = bot;
        this.realUserId = realUserId;
    }

    @Override
    public String getName() {
        return "send_private_msg";
    }

    @Override
    public String getDescription() {
        return "向指定用户发送一条私聊消息。谁是被卧底时，用它给每个玩家私发词语。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "接收私聊的用户 QQ"),
                        "message", Map.of("type", "string", "description", "私聊内容"),
                        "group_id", Map.of("type", "string", "description", "来源群号")
                ),
                "required", Arrays.asList("user_id", "message")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        String message = (String) args.get("message");
        String groupId = (String) args.get("group_id");
        // 黑名单检查：黑名单用户不能指挥糖果熊私聊别人
        try {
            long rid = Long.parseLong(realUserId);
            if (BotConfig.getPrivateBlacklist().contains(rid)) {
                return "私聊功能不可用：你已被限制使用此功能";
            }
        } catch (NumberFormatException e) {
            return "无法确定发起者身份";
        }

        if (userId == null || message == null) return "缺少 user_id 或 message";
        try {
            long gid = 0;
            if (groupId != null && !groupId.isEmpty() && !"null".equals(groupId)) {
                gid = Long.parseLong(groupId);
            }
            bot.sendPrivateReply(Long.parseLong(userId), gid, message);
            return "已发送私聊给 " + userId;
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }
}
