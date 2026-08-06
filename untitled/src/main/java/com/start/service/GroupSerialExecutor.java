package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 按 key 串行执行任务。群聊使用共享 worker 池和每 key 的有界队列，
 * 私聊使用独立的有界线程池。避免为每个群永久创建线程。
 */
public class GroupSerialExecutor {
    private static final Logger logger = LoggerFactory.getLogger(GroupSerialExecutor.class);

    private static final int MAX_GROUP_QUEUE_SIZE = 100;
    private static final int MAX_PRIVATE_QUEUE_SIZE = 200;

    private final Map<String, QueueState> groupQueues = new ConcurrentHashMap<>();
    private final ExecutorService groupWorkerPool;
    private final ThreadPoolExecutor privateChatExecutor;
    private final long defaultMaxQueueTimeMs;

    public GroupSerialExecutor(int privateThreads, long defaultMaxQueueTimeMs) {
        int threads = Math.max(1, privateThreads);
        this.groupWorkerPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Conversation-Worker");
            t.setDaemon(true);
            return t;
        });
        this.privateChatExecutor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PRIVATE_QUEUE_SIZE), r -> {
            Thread t = new Thread(r, "Private-AI-Worker");
            t.setDaemon(true);
            return t;
        });
        this.defaultMaxQueueTimeMs = defaultMaxQueueTimeMs;
    }

    /** 提交任务，使用默认超时 */
    public void execute(String groupId, Runnable task) {
        execute(groupId, task, defaultMaxQueueTimeMs);
    }

    /**
     * 提交任务到对应群的串行队列。超过排队时间的任务会被丢弃。
     *
     * @param groupId 群号（null 表示私聊，走共享线程池）
     * @param task    要执行的任务
     * @param maxQueueTimeMs 最大排队时间毫秒，超过则丢弃
     */
    public void execute(String groupId, Runnable task, long maxQueueTimeMs) {
        long submitTime = System.currentTimeMillis();

        QueuedTask queued = new QueuedTask(() -> {
            long waited = System.currentTimeMillis() - submitTime;
            if (waited > maxQueueTimeMs) {
                logger.debug("丢弃过期任务 group={} 排队{}ms", groupId, waited);
                return;
            }
            if (waited > 500) {
                logger.debug("任务排队{}ms group={}", waited, groupId);
            }
            task.run();
        }, submitTime, maxQueueTimeMs);

        if (groupId == null) {
            try {
                privateChatExecutor.execute(queued.task());
            } catch (RejectedExecutionException e) {
                logger.warn("私聊任务队列已满，丢弃任务");
            }
        } else {
            enqueueGroupTask(groupId, queued);
        }
    }

    private void enqueueGroupTask(String groupId, QueuedTask task) {
        while (true) {
            QueueState state = groupQueues.computeIfAbsent(groupId, ignored -> new QueueState());
            boolean shouldStart = false;
            synchronized (state) {
                // A worker may have removed this state between lookup and locking it.
                if (groupQueues.get(groupId) != state) continue;
                if (state.tasks.size() >= MAX_GROUP_QUEUE_SIZE) {
                    logger.warn("群 {} 任务队列已满，丢弃任务", groupId);
                    return;
                }
                state.tasks.addLast(task);
                if (!state.running) {
                    state.running = true;
                    shouldStart = true;
                }
            }
            if (shouldStart) scheduleNext(groupId, state);
            return;
        }
    }

    private void scheduleNext(String groupId, QueueState state) {
        try {
            groupWorkerPool.execute(() -> runNext(groupId, state));
        } catch (RejectedExecutionException e) {
            synchronized (state) {
                state.tasks.clear();
                state.running = false;
            }
            groupQueues.remove(groupId, state);
            logger.warn("群 {} worker 池已关闭，丢弃任务", groupId);
        }
    }

    private void runNext(String groupId, QueueState state) {
        QueuedTask task;
        synchronized (state) {
            task = state.tasks.pollFirst();
            if (task == null) {
                state.running = false;
                groupQueues.remove(groupId, state);
                return;
            }
        }

        try {
            if (System.currentTimeMillis() - task.submittedAt() <= task.maxQueueTimeMs()) {
                task.task().run();
            } else {
                logger.debug("丢弃过期任务 group={} 排队{}ms", groupId,
                        System.currentTimeMillis() - task.submittedAt());
            }
        } catch (Throwable t) {
            logger.error("群 {} 任务执行异常", groupId, t);
        }

        boolean continueRunning;
        synchronized (state) {
            continueRunning = !state.tasks.isEmpty();
            if (!continueRunning) {
                state.running = false;
                groupQueues.remove(groupId, state);
            }
        }
        if (continueRunning) scheduleNext(groupId, state);
    }

    private static final class QueueState {
        private final ArrayDeque<QueuedTask> tasks = new ArrayDeque<>();
        private boolean running;
    }

    private record QueuedTask(Runnable task, long submittedAt, long maxQueueTimeMs) {}

    /** 关闭所有执行器 */
    public void shutdown() {
        groupWorkerPool.shutdownNow();
        privateChatExecutor.shutdownNow();
        groupQueues.clear();
    }
}
