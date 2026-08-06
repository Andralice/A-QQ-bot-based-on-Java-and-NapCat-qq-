package com.start;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotBootstrap.preflightCheck 行为测试（5.1 启动自检）。
 *
 * <p>BotConfig 是静态单例，JVM 生命周期内只加载一次。直接对 BotConfig.* 做"模拟缺失"会污染
 * 其他测试。所以把判定逻辑抽成 collectMissingConfigs(...) 纯函数，只依赖基本类型 + Set，
 * 测试里直接传 mock snapshot 覆盖每一种缺失场景。
 *
 * <p>4 个核心场景：缺 BOT_QQ / 缺 ALLOWED_GROUPS / 缺 ALLOWED_PRIVATE_USERS（仅在
 * privateWhitelistEnabled=true 时） / 缺 BAILIAN_API_KEY。
 */
class BotBootstrapPreflightTest {

    private static final long VALID_BOT_QQ = 123456789L;
    private static final String VALID_API_KEY = "sk-test-key-12345";
    private static final String VALID_WS_URL = "ws://127.0.0.1:5700";
    private static final Set<Long> VALID_GROUPS = Set.of(111111L, 222222L);
    private static final Set<Long> VALID_PRIVATE_USERS = Set.of(999999L);

    // ── 正常通过：所有必填项都齐 ──

    @Test
    void allRequiredPresentNoMissing() {
        List<String> missing = BotBootstrap.collectMissingConfigs(
                VALID_BOT_QQ, VALID_API_KEY, VALID_WS_URL, VALID_GROUPS,
                false /* privateWhitelistEnabled */, Collections.emptySet());
        assertTrue(missing.isEmpty(), "Expected no missing, got: " + missing);
    }

    @Test
    void allRequiredPresentWithPrivateWhitelist() {
        // 私聊白名单开启 + 白名单用户非空 → 通过
        List<String> missing = BotBootstrap.collectMissingConfigs(
                VALID_BOT_QQ, VALID_API_KEY, VALID_WS_URL, VALID_GROUPS,
                true, VALID_PRIVATE_USERS);
        assertTrue(missing.isEmpty(), "Expected no missing, got: " + missing);
    }

    // ── 4 种缺失场景 ──

    @Test
    void missingBotQqReported() {
        List<String> missing = BotBootstrap.collectMissingConfigs(
                0L /* botQq */, VALID_API_KEY, VALID_WS_URL, VALID_GROUPS,
                false, Collections.emptySet());
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).contains("BOT_QQ"),
                "Expected BOT_QQ in missing, got: " + missing.get(0));
    }

    @Test
    void missingAllowedGroupsReported() {
        List<String> missing = BotBootstrap.collectMissingConfigs(
                VALID_BOT_QQ, VALID_API_KEY, VALID_WS_URL, Collections.emptySet(),
                false, Collections.emptySet());
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).contains("ALLOWED_GROUPS"),
                "Expected ALLOWED_GROUPS in missing, got: " + missing.get(0));
    }

    @Test
    void missingAllowedPrivateUsersOnlyWhenWhitelistEnabled() {
        // 私聊白名单关闭 + ALLOWED_PRIVATE_USERS 空 → 不报（dev 默认）
        List<String> missingWhitelistOff = BotBootstrap.collectMissingConfigs(
                VALID_BOT_QQ, VALID_API_KEY, VALID_WS_URL, VALID_GROUPS,
                false, Collections.emptySet());
        assertFalse(missingWhitelistOff.stream().anyMatch(s -> s.contains("ALLOWED_PRIVATE_USERS")),
                "Should not report ALLOWED_PRIVATE_USERS when whitelist disabled, got: " + missingWhitelistOff);

        // 私聊白名单开启 + ALLOWED_PRIVATE_USERS 空 → 报
        List<String> missingWhitelistOn = BotBootstrap.collectMissingConfigs(
                VALID_BOT_QQ, VALID_API_KEY, VALID_WS_URL, VALID_GROUPS,
                true, Collections.emptySet());
        assertTrue(missingWhitelistOn.stream().anyMatch(s -> s.contains("ALLOWED_PRIVATE_USERS")),
                "Should report ALLOWED_PRIVATE_USERS when whitelist enabled but empty, got: " + missingWhitelistOn);
    }

    @Test
    void missingBaiLianApiKeyReported() {
        List<String> missing = BotBootstrap.collectMissingConfigs(
                VALID_BOT_QQ, "" /* apiKey */, VALID_WS_URL, VALID_GROUPS,
                false, Collections.emptySet());
        assertTrue(missing.stream().anyMatch(s -> s.contains("BAILIAN_API_KEY")),
                "Expected BAILIAN_API_KEY in missing, got: " + missing);
    }

    @Test
    void missingWsUrlReported() {
        List<String> missing = BotBootstrap.collectMissingConfigs(
                VALID_BOT_QQ, VALID_API_KEY, "" /* wsUrl */, VALID_GROUPS,
                false, Collections.emptySet());
        assertTrue(missing.stream().anyMatch(s -> s.contains("NAPCT_WS_URL")),
                "Expected NAPCT_WS_URL in missing, got: " + missing);
    }

    // ── 复合场景：多缺失项都列出 ──

    @Test
    void allMissingReportedTogether() {
        // 同时缺 4 项：每一项都应该在 missing 列表里
        List<String> missing = BotBootstrap.collectMissingConfigs(
                0L, "", "", Collections.emptySet(),
                true /* privateWhitelistEnabled */, Collections.emptySet());
        // 预期 5 项：botqq + apikey + wsurl + groups + privateusers
        assertEquals(5, missing.size(), "Expected 5 missing, got: " + missing);
        assertTrue(missing.stream().anyMatch(s -> s.contains("BOT_QQ")));
        assertTrue(missing.stream().anyMatch(s -> s.contains("BAILIAN_API_KEY")));
        assertTrue(missing.stream().anyMatch(s -> s.contains("NAPCT_WS_URL")));
        assertTrue(missing.stream().anyMatch(s -> s.contains("ALLOWED_GROUPS")));
        assertTrue(missing.stream().anyMatch(s -> s.contains("ALLOWED_PRIVATE_USERS")));
    }

    // ── 边界值 ──

    @Test
    void negativeBotQqTreatedAsMissing() {
        // botQq < 0 也算未配置（防止有人传 -1 占位）
        List<String> missing = BotBootstrap.collectMissingConfigs(
                -1L, VALID_API_KEY, VALID_WS_URL, VALID_GROUPS,
                false, Collections.emptySet());
        assertTrue(missing.stream().anyMatch(s -> s.contains("BOT_QQ")),
                "Negative botQq should be treated as missing, got: " + missing);
    }

    @Test
    void nullApiKeyTreatedAsMissing() {
        List<String> missing = BotBootstrap.collectMissingConfigs(
                VALID_BOT_QQ, null, VALID_WS_URL, VALID_GROUPS,
                false, Collections.emptySet());
        assertTrue(missing.stream().anyMatch(s -> s.contains("BAILIAN_API_KEY")),
                "Null apiKey should be treated as missing, got: " + missing);
    }

    @Test
    void nullAllowedGroupsTreatedAsMissing() {
        // null 集合也算缺失（防御性）
        List<String> missing = BotBootstrap.collectMissingConfigs(
                VALID_BOT_QQ, VALID_API_KEY, VALID_WS_URL, null,
                false, Collections.emptySet());
        assertTrue(missing.stream().anyMatch(s -> s.contains("ALLOWED_GROUPS")),
                "Null allowedGroups should be treated as missing, got: " + missing);
    }
}
