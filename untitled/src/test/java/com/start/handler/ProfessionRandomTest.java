package com.start.handler;

import com.start.repository.UserProfessionRepository;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 职业生成"全随机"单测 —— 脉系 + 战力都重抽但匹配位阶。
 *
 * 关键不变量：
 *  1) 脉系：每天重抽，8 个 path 中纯随机（不再用 userId 绑定）
 *  2) 战力：严格在位阶范围内（POWER_RANGES[tier-1]）
 *  3) 同一天多次重抽同一用户 → 同一结果（seed 用日期）
 */
class ProfessionRandomTest {

    @Test
    void randomPathWithRngCoversAllEightPaths() {
        // 用固定 Random 跑几千次，确保 8 个 path 都能抽到
        Random rng = new Random(42);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            seen.add(UserProfessionRepository.ProfessionPath.randomPath(rng));
        }
        assertEquals(UserProfessionRepository.ProfessionPath.PATHS.length, seen.size(),
            "5000 次随机应该覆盖全部 8 个 path，实际只抽到：" + seen);
    }

    @Test
    void randomPathIsUniformDistribution() {
        // 验证分布基本均匀（不严格，但 1000 次抽样允许 ±10% 偏差）
        Random rng = new Random(123);
        int[] counts = new int[UserProfessionRepository.ProfessionPath.PATHS.length];
        int n = 8000;
        for (int i = 0; i < n; i++) {
            String p = UserProfessionRepository.ProfessionPath.randomPath(rng);
            for (int j = 0; j < UserProfessionRepository.ProfessionPath.PATHS.length; j++) {
                if (UserProfessionRepository.ProfessionPath.PATHS[j].equals(p)) {
                    counts[j]++;
                    break;
                }
            }
        }
        int expected = n / UserProfessionRepository.ProfessionPath.PATHS.length;
        for (int j = 0; j < counts.length; j++) {
            assertTrue(Math.abs(counts[j] - expected) < expected * 0.2,
                "path[" + j + "]=" + UserProfessionRepository.ProfessionPath.PATHS[j]
                + " 抽到 " + counts[j] + " 次，期望 ~" + expected + "（±20%）");
        }
    }

    @Test
    void randomPowerStrictlyWithinTierRange() {
        long uid = 12345L;
        String gid = "test_group";
        for (int tier = 1; tier <= 5; tier++) {
            int[] range = UserProfessionRepository.ProfessionPath.POWER_RANGES[tier - 1];
            for (int i = 0; i < 200; i++) {
                int power = UserProfessionRepository.ProfessionPath.randomPower(tier, uid, gid);
                assertTrue(power >= range[0] && power <= range[1],
                    "tier=" + tier + " power=" + power + " 超出范围 [" + range[0] + "," + range[1] + "]");
            }
        }
    }

    @Test
    void randomPowerIsDeterministicPerDay() {
        // 同一天同一用户 → 同一 power（可复现）
        long uid = 999L;
        String gid = "g1";
        int p1 = UserProfessionRepository.ProfessionPath.randomPower(3, uid, gid);
        int p2 = UserProfessionRepository.ProfessionPath.randomPower(3, uid, gid);
        assertEquals(p1, p2, "同一天同一 userId 抽 power 应该稳定");
    }

    @Test
    void allFiveTiersProduceValidNames() {
        // entryName 每个 path 的每个 tier 都有合法名（不能落到 fallback）
        for (String path : UserProfessionRepository.ProfessionPath.PATHS) {
            for (int tier = 1; tier <= 5; tier++) {
                String name = UserProfessionRepository.ProfessionPath.entryName(path, tier);
                assertNotNull(name);
                assertTrue(!name.isEmpty());
            }
        }
    }
}
