package com.start.service;

import com.start.model.LongTermMemory;
import com.start.model.RecurringTask;
import com.start.repository.LongTermMemoryRepository;
import com.start.repository.RecurringTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * 调度任务执行器：把 BotBootstrap 内的「claim → 生成 → 发送 → 释放/标记」逻辑
 * 抽出来，便于单元测试。发送依赖通过函数式接口注入，避免直接依赖 Main/WebSocket。
 *
 * 设计原则：发送失败时释放租约，下次扫描能再 claim；
 * 状态更新失败时也释放租约，避免任务卡死。
 */
public class ScheduleExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleExecutor.class);

    /**
     * 执行一个定时事件（LongTermMemory.trigger_at）。
     * 流程：claim → generate → send → 失败 release / 成功 markTriggered。
     *
     * @return true=已标记触发；false=claim 失败 / 发送失败已释放租约
     */
    public static boolean executeDueEvent(LongTermMemoryRepository repo,
                                          LongTermMemory event,
                                          Supplier<String> replyGenerator,
                                          BiFunction<Long, String, Boolean> groupSendFn,
                                          BiFunction<Long, String, Boolean> privateSendFn) {
        try {
            if (!repo.claimDueEvent(event.getId())) {
                return false;  // 被别的实例/线程抢了
            }
        } catch (SQLException e) {
            logger.error("claimDueEvent 失败 id={}: {}", event.getId(), e.getMessage());
            return false;
        }

        String reply = replyGenerator.get();
        boolean delivered = reply == null || reply.trim().isEmpty();
        if (reply != null && !reply.trim().isEmpty()) {
            if (event.getGroupId() != null && !event.getGroupId().isBlank()) {
                delivered = groupSendFn.apply(Long.parseLong(event.getGroupId()), reply);
            } else {
                delivered = privateSendFn.apply(Long.parseLong(event.getUserId()), reply);
            }
        }

        if (!delivered) {
            logger.warn("定时事件消息发送失败，保留事件待下次重试 id={}", event.getId());
            try {
                repo.releaseEventClaim(event.getId());
            } catch (SQLException releaseError) {
                logger.error("释放定时事件租约失败 id={}: {}", event.getId(), releaseError.getMessage());
            }
            return false;
        }

        try {
            repo.markTriggered(event.getId());
        } catch (SQLException e) {
            logger.error("定时事件状态更新失败 id={}: {}", event.getId(), e.getMessage());
            try {
                repo.releaseEventClaim(event.getId());
            } catch (SQLException releaseError) {
                logger.error("释放定时事件租约失败 id={}: {}", event.getId(), releaseError.getMessage());
            }
        }
        logger.info("定时事件已触发: {} -> {}", event.getContent(), event.getGroupId());
        return true;
    }

    /**
     * 执行一个周期任务（RecurringTask）。
     * 流程：claim → generate → send → 失败 release / 成功 markFired(nextFire)。
     */
    public static boolean executeDueTask(RecurringTaskRepository repo,
                                          RecurringTask task,
                                          Supplier<String> replyGenerator,
                                          BiFunction<Long, String, Boolean> groupSendFn,
                                          BiFunction<Long, String, Boolean> privateSendFn,
                                          Supplier<LocalDateTime> nextFireFn) {
        try {
            if (!repo.claimDueTask(task.getId())) {
                return false;
            }
        } catch (SQLException e) {
            logger.error("claimDueTask 失败 id={}: {}", task.getId(), e.getMessage());
            return false;
        }

        String reply = replyGenerator.get();
        boolean delivered = reply == null || reply.trim().isEmpty();
        if (reply != null && !reply.trim().isEmpty()) {
            if (task.getGroupId() != null && !task.getGroupId().isBlank()) {
                delivered = groupSendFn.apply(Long.parseLong(task.getGroupId()), reply);
            } else {
                delivered = privateSendFn.apply(Long.parseLong(task.getUserId()), reply);
            }
        }

        if (!delivered) {
            logger.warn("周期任务消息发送失败，保留任务待下次重试 id={}", task.getId());
            try {
                repo.releaseTaskClaim(task.getId());
            } catch (SQLException releaseError) {
                logger.error("释放周期任务租约失败 id={}: {}", task.getId(), releaseError.getMessage());
            }
            return false;
        }

        LocalDateTime nextFire = nextFireFn.get();
        try {
            repo.markFired(task.getId(), nextFire);
        } catch (SQLException e) {
            logger.error("周期任务状态更新失败 id={}: {}", task.getId(), e.getMessage());
            try {
                repo.releaseTaskClaim(task.getId());
            } catch (SQLException releaseError) {
                logger.error("释放周期任务租约失败 id={}: {}", task.getId(), releaseError.getMessage());
            }
        }
        return true;
    }
}
