package com.start.agent;

import com.start.config.BotConfig;
import com.start.service.WhitelistService;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * 白名单管理工具（QQ 端热改）。
 *
 * <p>仅管理员(归儿)可用。允许加/删/查询群白名单和私聊白名单。
 * 改完立刻生效，无需重启。底层走 {@link WhitelistService}，DB 持久化。
 *
 * <p>注意：黑名单（PRIVATE_BLACKLIST）不在本 Tool 范围内，仍需改 .env 重启。
 */
public class WhitelistTool implements Tool {

    private final String realUserId;

    public WhitelistTool(String realUserId) {
        this.realUserId = realUserId;
    }

    @Override public String getName() { return "manage_whitelist"; }

    @Override
    public String getDescription() {
        return "管理白名单（群白名单 + 私聊白名单），仅管理员可用。改完立即生效，无需重启。\n" +
               "参数: action(list/add/remove), type(group/private), id(群号或QQ号)。\n" +
               "示例:\n" +
               "  action=list 查看所有白名单\n" +
               "  action=add type=group id=123456 添加群\n" +
               "  action=add type=private id=12345 添加私聊用户\n" +
               "  action=remove type=group id=123456 移除群\n" +
               "  action=remove type=private id=12345 移除私聊用户";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "description", "list(查询) / add(添加) / remove(移除)"),
                        "type", Map.of("type", "string",
                                "description", "group(群白名单) / private(私聊白名单)"),
                        "id", Map.of("type", "string",
                                "description", "群号或 QQ 号（add/remove 时必填）")
                ),
                "required", Arrays.asList("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        // 1. admin-only
        long uid;
        try { uid = Long.parseLong(realUserId); }
        catch (NumberFormatException e) { return "无法确定用户身份"; }
        if (uid != BotConfig.getAdminQq()) {
            return "manage_whitelist 仅对管理员开放。";
        }

        String action = (String) args.get("action");
        if (action == null || action.isBlank()) return "请指定 action（list/add/remove）";

        WhitelistService ws = WhitelistService.getInstance();

        return switch (action.toLowerCase()) {
            case "list" -> listAll(ws);
            case "add" -> handleAdd(ws, args);
            case "remove" -> handleRemove(ws, args);
            default -> "不支持的操作: " + action + "。支持: list / add / remove";
        };
    }

    private String listAll(WhitelistService ws) {
        StringBuilder sb = new StringBuilder("📋 当前白名单（DB 持久化，热生效）:\n");
        Set<Long> groups = ws.getAllowedGroups();
        sb.append("  群白名单 (").append(groups.size()).append(" 个):\n");
        if (groups.isEmpty()) sb.append("    (空)\n");
        else groups.stream().sorted().forEach(g -> sb.append("    • ").append(g).append("\n"));

        Set<Long> privs = ws.getAllowedPrivateUsers();
        sb.append("  私聊白名单 (").append(privs.size()).append(" 个");
        if (!BotConfig.isPrivateWhitelistEnabled()) {
            sb.append("，但开关关闭中（private.whitelist.enabled=false），暂不生效");
        }
        sb.append("):\n");
        if (privs.isEmpty()) sb.append("    (空)\n");
        else privs.stream().sorted().forEach(p -> sb.append("    • ").append(p).append("\n"));
        return sb.toString();
    }

    private String handleAdd(WhitelistService ws, Map<String, Object> args) {
        String type = (String) args.get("type");
        String idStr = (String) args.get("id");
        if (type == null) return "请指定 type（group/private）";
        if (idStr == null || idStr.isBlank()) return "请指定 id（群号或 QQ 号）";

        long id;
        try { id = Long.parseLong(idStr.trim()); }
        catch (NumberFormatException e) { return "id 格式错误，应为数字: " + idStr; }

        if ("group".equalsIgnoreCase(type)) {
            boolean added = ws.addGroup(id);
            return added ? "✅ 群 " + id + " 已加入白名单，立即生效。"
                         : "ℹ️ 群 " + id + " 已经在白名单中。";
        } else if ("private".equalsIgnoreCase(type)) {
            boolean added = ws.addPrivateUser(id);
            if (BotConfig.isPrivateWhitelistEnabled()) {
                return added ? "✅ 用户 " + id + " 已加入私聊白名单，立即生效。"
                             : "ℹ️ 用户 " + id + " 已经在私聊白名单中。";
            } else {
                return added ? "✅ 用户 " + id + " 已加入私聊白名单（但 private.whitelist.enabled=false 开关未开，暂不生效）。\n" +
                              "要让它生效，需到服务器 application.properties 把 private.whitelist.enabled 设为 true 后重启。"
                             : "ℹ️ 用户 " + id + " 已经在私聊白名单中。";
            }
        } else {
            return "type 应为 group 或 private，当前: " + type;
        }
    }

    private String handleRemove(WhitelistService ws, Map<String, Object> args) {
        String type = (String) args.get("type");
        String idStr = (String) args.get("id");
        if (type == null) return "请指定 type（group/private）";
        if (idStr == null || idStr.isBlank()) return "请指定 id（群号或 QQ 号）";

        long id;
        try { id = Long.parseLong(idStr.trim()); }
        catch (NumberFormatException e) { return "id 格式错误，应为数字: " + idStr; }

        if ("group".equalsIgnoreCase(type)) {
            boolean removed = ws.removeGroup(id);
            return removed ? "✅ 群 " + id + " 已移出白名单，立即生效。"
                           : "ℹ️ 群 " + id + " 不在白名单中。";
        } else if ("private".equalsIgnoreCase(type)) {
            boolean removed = ws.removePrivateUser(id);
            return removed ? "✅ 用户 " + id + " 已移出私聊白名单，立即生效。"
                           : "ℹ️ 用户 " + id + " 不在私聊白名单中。";
        } else {
            return "type 应为 group 或 private，当前: " + type;
        }
    }
}
