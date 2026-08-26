package com.start.service;

import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 白名单服务 — 运行时可改的群/私聊白名单。
 *
 * <p>DB 存储走 {@link RuntimeConfigService}（key: {@code whitelist.allowed_groups} /
 * {@code whitelist.allowed_private_users}），启动时优先从 DB 读，DB 为空时从
 * {@link BotConfig} 读初始值并写回 DB（保证后续以 DB 为准）。
 *
 * <p>为什么不用 BotConfig 静态字段：BotConfig.java 属 SelfEvolveTool 硬阻断的安全文件，
 * 且 BotConfig 是从 application.properties 读静态值，运行时无法热改。
 *
 * <p>懒加载：首次 {@link #getInstance()} 时初始化，不需要在 BotBootstrap 显式调用 init。
 */
public class WhitelistService {
    private static final Logger logger = LoggerFactory.getLogger(WhitelistService.class);

    private static final String KEY_GROUPS = "whitelist.allowed_groups";
    private static final String KEY_PRIVATE = "whitelist.allowed_private_users";

    private static volatile WhitelistService instance;

    private final Set<Long> allowedGroups = ConcurrentHashMap.newKeySet();
    private final Set<Long> allowedPrivateUsers = ConcurrentHashMap.newKeySet();

    private final RuntimeConfigService configService;

    private WhitelistService() {
        this.configService = new RuntimeConfigService();
        loadInitial();
    }

    public static WhitelistService getInstance() {
        WhitelistService local = instance;
        if (local == null) {
            synchronized (WhitelistService.class) {
                local = instance;
                if (local == null) {
                    local = new WhitelistService();
                    instance = local;
                }
            }
        }
        return local;
    }

    private void loadInitial() {
        // 1. 优先从 DB 加载（之前的修改会持久化在这里）
        String groupsDb = configService.get(KEY_GROUPS);
        String privDb = configService.get(KEY_PRIVATE);
        if (groupsDb != null && !groupsDb.isBlank()) {
            parseAndAdd(allowedGroups, groupsDb, "DB");
        }
        if (privDb != null && !privDb.isBlank()) {
            parseAndAdd(allowedPrivateUsers, privDb, "DB");
        }

        // 2. DB 为空时从 BotConfig 加载（首次启动或 DB 被清空）
        if (allowedGroups.isEmpty()) {
            Set<Long> fromConfig = BotConfig.getAllowedGroups();
            if (fromConfig != null && !fromConfig.isEmpty()) {
                allowedGroups.addAll(fromConfig);
                logger.info("群白名单从 BotConfig 初始化: {}", allowedGroups);
                persist(KEY_GROUPS);
            }
        }
        if (allowedPrivateUsers.isEmpty()) {
            Set<Long> fromConfig = BotConfig.getAllowedPrivateUsers();
            if (fromConfig != null && !fromConfig.isEmpty()) {
                allowedPrivateUsers.addAll(fromConfig);
                logger.info("私聊白名单从 BotConfig 初始化: {}", allowedPrivateUsers);
                persist(KEY_PRIVATE);
            }
        }

        logger.info("WhitelistService 初始化完成：群白名单 {} 个，私聊白名单 {} 个",
                allowedGroups.size(), allowedPrivateUsers.size());
    }

    private void parseAndAdd(Set<Long> target, String csv, String source) {
        for (String s : csv.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            try {
                target.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                logger.warn("忽略非法 {} 值: {}", source, s);
            }
        }
    }

    private void persist(String key) {
        Set<Long> set = KEY_GROUPS.equals(key) ? allowedGroups : allowedPrivateUsers;
        String csv = set.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (set.isEmpty()) {
            // 空集合也要写 ""，覆盖之前的值
            configService.directSet(key, "", "WhitelistService");
        } else {
            configService.directSet(key, csv, "WhitelistService");
        }
    }

    // ==================== 群白名单 ====================

    public boolean isAllowedGroup(long groupId) {
        return allowedGroups.contains(groupId);
    }

    public boolean addGroup(long groupId) {
        boolean added = allowedGroups.add(groupId);
        if (added) {
            persist(KEY_GROUPS);
            logger.info("群 {} 加入白名单（当前 {} 个）", groupId, allowedGroups.size());
        }
        return added;
    }

    public boolean removeGroup(long groupId) {
        boolean removed = allowedGroups.remove(groupId);
        if (removed) {
            persist(KEY_GROUPS);
            logger.info("群 {} 移出白名单（当前 {} 个）", groupId, allowedGroups.size());
        }
        return removed;
    }

    public Set<Long> getAllowedGroups() {
        return Collections.unmodifiableSet(allowedGroups);
    }

    // ==================== 私聊白名单 ====================

    public boolean isAllowedPrivateUser(long userId) {
        return allowedPrivateUsers.contains(userId);
    }

    public boolean addPrivateUser(long userId) {
        boolean added = allowedPrivateUsers.add(userId);
        if (added) {
            persist(KEY_PRIVATE);
            logger.info("私聊用户 {} 加入白名单（当前 {} 个）", userId, allowedPrivateUsers.size());
        }
        return added;
    }

    public boolean removePrivateUser(long userId) {
        boolean removed = allowedPrivateUsers.remove(userId);
        if (removed) {
            persist(KEY_PRIVATE);
            logger.info("私聊用户 {} 移出白名单（当前 {} 个）", userId, allowedPrivateUsers.size());
        }
        return removed;
    }

    public Set<Long> getAllowedPrivateUsers() {
        return Collections.unmodifiableSet(allowedPrivateUsers);
    }
}
