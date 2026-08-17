package sim;

import com.start.service.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class PromptDump {
    public static void main(String[] args) throws Exception {
        // === 模拟 17:29:30 那次：群 1079312807, user 1119046713, @ bot ===
        String groupId = "1079312807";
        String userId = "1119046713";
        String nickname = "群友";
        boolean isGuier = false;  // 1119046713 不是归儿
        long botQq = 356289140L;
        boolean isAtBot = true;

        // === timeContext（与 BaiLianService.generate() 同步）===
        ZoneId beijingZone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.now(beijingZone);
        DateTimeFormatter tsFormatter = DateTimeFormatter.ofPattern(
                "yyyy年M月d日 EEEE HH:mm:ss '北京时间'", java.util.Locale.CHINA);
        DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String beijingTime = now.format(tsFormatter);
        String today = java.time.LocalDate.now(beijingZone).format(dayFormatter);

        String timeContext = "【当前时间】是：" + beijingTime
            + "\n调用 search_chat_history / recall_memory 查日期时，用 yyyy-MM-dd 格式。今天=" + today
            + "（昨天/前天请自行 -1d / -2d）。"
            + "session history 已带 [MM-dd HH:mm] 时间戳，请直接看时间戳判断每条消息的时序；"
            + "**【铁律】不允许在正常回复里带任何时间戳前缀**（如 [08-14 16:32] / [16:32] / (16:32) 等），"
            + "也不允许模仿历史消息的时间戳格式输出。时间戳仅供内部判断时序，永远不要复述、引用、模仿；"
            + "只有用户明确要求查询或说明时间时才输出时间信息。"
            + "若看到时间戳跨天/隔夜，说明是新的一天。";

        // === PromptContext ===
        PromptContext ctx = new PromptContext()
                .nickname(nickname).userId(userId).groupId(groupId)
                .isGuier(isGuier)
                .userProfileText("[用户画像] 此用户是新用户，还没有足够信息。")
                .atUserIds(List.of(botQq)).botQq(botQq)
                .moodDescription("兴奋")
                .isAtBot(isAtBot)
                .timeContext(timeContext)
                .allowSilence(false);

        // === 模拟 PromptBuilder.buildRuleBook() ===
        PromptBuilder pb = new PromptBuilder();
        RuleSet ruleSet = RuleSetDefaults.defaults();
        String prompt = pb.buildRuleBook(ruleSet, ctx);

        System.out.println("===== 完整 system prompt (chars=" + prompt.length() + ") =====");
        System.out.println(prompt);
        System.out.println("===== END =====");
        System.out.println();
        System.out.println("=== timeContext 位置定位 ===");
        int idx = prompt.indexOf("【当前时间】");
        System.out.println("【当前时间】第一次出现位置: char " + idx);
        if (idx > 0) {
            String before = prompt.substring(0, idx);
            String after = prompt.substring(idx);
            System.out.println("前面已有字符数: " + before.length() + " / 总 " + prompt.length());
            System.out.println("后面还有字符数: " + after.length());
            // 截取 timeContext 上下文 200 字
            int start = Math.max(0, idx - 100);
            int end = Math.min(prompt.length(), idx + 800);
            System.out.println();
            System.out.println("=== 【当前时间】周围 +100/-800 字符 ===");
            System.out.println(prompt.substring(start, end));
        }
    }
}
