package com.start.agent;

import com.start.service.StickerIngestService;
import com.start.service.ToolAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 表情包管理工具（管理员专用）。
 *
 * <p>由 LLM 在管理员会话中调用，也可以被 {@code AIHandler} 在管理员私聊命令路径直接调用。
 * 所有动作都先校验管理员身份（{@link ToolAuthorizationService#isAdmin(String)}）。
 *
 * <p>支持动作：
 * <ul>
 *     <li>{@code fix_keywords} — 覆盖 sticker 的关键词（保留 auto_keywords 作为审计）</li>
 *     <li>{@code list} — 列出当前 sticker 库（可按关键词过滤）</li>
 *     <li>{@code remove} — 删除 sticker</li>
 * </ul>
 */
public class StickerAdminTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(StickerAdminTool.class);

    private final String callerUserId;

    /** 构造时绑定调用者。AIHandler 在管理员私聊命令路径直接 new，传管理员 QQ。 */
    public StickerAdminTool(String callerUserId) {
        this.callerUserId = callerUserId;
    }

    @Override
    public String getName() { return "sticker_admin"; }

    @Override
    public String getDescription() {
        return "管理表情包库（仅管理员可用）。"
                + "action=fix_keywords 覆盖关键词；action=list 列出库；action=remove 删除。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "description", "操作类型：fix_keywords | list | remove"),
                        "sticker_id", Map.of("type", "string",
                                "description", "sticker ID（MD5 哈希或 legacy_xxx）"),
                        "keywords", Map.of("type", "string",
                                "description", "新关键词，逗号或空格分隔，用于 fix_keywords"),
                        "filter", Map.of("type", "string",
                                "description", "list 时按关键词过滤（可选）")
                ),
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (!ToolAuthorizationService.getInstance().isAdmin(callerUserId)) {
            return "权限拒绝：仅管理员可调用 sticker_admin";
        }
        String action = (String) args.get("action");
        if (action == null || action.isBlank()) return "缺少 action";

        try {
            StickerIngestService service = StickerIngestService.getInstance();
            switch (action) {
                case "fix_keywords": {
                    String id = (String) args.get("sticker_id");
                    String kwRaw = (String) args.get("keywords");
                    List<String> kws = StickerIngestService.parseKeywords(kwRaw);
                    return service.correctKeywords(id, kws, callerUserId);
                }
                case "list": {
                    String filter = (String) args.get("filter");
                    List<StickerIngestService.StickerRecord> all = service.getAllStickers();
                    if (filter != null && !filter.isBlank()) {
                        all = service.searchByKeyword(filter);
                    }
                    if (all.isEmpty()) return "(sticker 库为空)";
                    return all.stream()
                            .map(r -> String.format("%s | file=%s | kw=[%s] | auto=[%s] | src=%s",
                                    r.id, r.file,
                                    String.join(",", r.keywords),
                                    String.join(",", r.autoKeywords),
                                    r.sourceGroup != null ? r.sourceGroup : "-"))
                            .collect(Collectors.joining("\n"));
                }
                case "remove": {
                    return service.remove((String) args.get("sticker_id"));
                }
                default:
                    return "未知 action: " + action;
            }
        } catch (Exception e) {
            logger.warn("sticker_admin 执行失败: {}", e.getMessage(), e);
            return "执行失败: " + e.getMessage();
        }
    }
}
