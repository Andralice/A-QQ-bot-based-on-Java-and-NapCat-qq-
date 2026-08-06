package com.start;

import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.handler.CPTracker;
import com.start.handler.DailyProfessionHandler;
import com.start.handler.HandlerRegistry;
import com.start.runtime.ConversationRuntime;
import com.start.runtime.conversation.ConversationRuntimeConfig;
import com.start.runtime.trace.DecisionTraceListener;
import com.start.runtime.trace.MetricsListener;
import com.start.runtime.trace.WebDashboardListener;
import com.start.model.LongTermMemory;
import com.start.service.ConversationMetrics;
import com.start.model.RecurringTask;
import com.start.repository.*;
import com.start.service.*;
import static com.start.service.ScheduleExecutor.executeDueEvent;
import com.start.util.DatabaseErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.sql.SQLException;
import java.util.List;

/**
 * 服务装配与后台任务启动器。
 * 将 Main 从冗长的构造器和 init() 中解放，保持其专注 WebSocket + 消息分发。
 */
public final class BotBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(BotBootstrap.class);

    private BotBootstrap() {}

    /** 创建并装配所有核心服务，注入到 Main 实例。 */
    public static void wireServices(Main bot) {
        // 启动时数据库连通性检查
        if (!DatabaseErrorHandler.isDatabaseAvailable()) {
            logger.error("数据库连接失败，请检查网络、SSH 隧道或数据库服务状态");
        }

        // WebSocket API 封装
        bot.oneBotWsService = new OneBotWsService(bot);

        // 基础服务
        bot.userService = new UserService();
        bot.messageService = new MessageService();

        bot.aiDatabaseService = new AIDatabaseService();

        // 知识库 & 情绪
        bot.keywordKnowledgeService = new KeywordKnowledgeService(DatabaseConfig.getDataSource());
        bot.moodService = new BotMoodService(new GroupMoodRepository(DatabaseConfig.getDataSource()));

        // TTS
        bot.ttsService = new TtsService();

        // 大模型服务
        bot.baiLianService = new BaiLianService(bot.keywordKnowledgeService, bot.userAffinityRepo, bot.ttsService);
        bot.baiLianService.setMoodService(bot.moodService);
        bot.baiLianService.setBotInstance(bot);

        // 异常监控
        bot.errorMonitorService = new ErrorMonitorService(bot.baiLianService);

        // 群聊串行执行器 & Shell 服务
        GroupSerialExecutor groupExecutor = new GroupSerialExecutor(4, 30_000);
        bot.conversationExecutor = groupExecutor;
        ServerAdminService shellService = new ServerAdminService();

        // 工具审计：单例注入，异步写库
        ToolAuditService.init(new ToolAuditLogRepository(DatabaseConfig.getDataSource()));
        // 工具授权服务：集中权限 + 频率限流
        ToolAuthorizationService.init();

        // 运行时事件总线 + 监听器
        ConversationRuntime runtime = new ConversationRuntime();
        bot.conversationRuntime = runtime;
        ConversationMetrics metrics = new ConversationMetrics();
        bot.baiLianService.setConversationMetrics(metrics);
        runtime.addListener(new MetricsListener(metrics));
        runtime.addListener(new DecisionTraceListener());

        // 运行时配置
        ConversationRuntimeConfig config = ConversationRuntimeConfig.defaults();

        // Handler 注册中心
        ConversationManager conversationManager = new ConversationManager();
        bot.handlerRegistry = new HandlerRegistry(bot.baiLianService, groupExecutor, bot, shellService, conversationManager, runtime, config);

        // DashScope API Key
        if (BotConfig.getBaiLianApiKey() != null && !BotConfig.getBaiLianApiKey().isBlank()) {
            System.setProperty("dashscope.api-key", BotConfig.getBaiLianApiKey());
        }
    }

    /** 启动所有后台定时任务（守护线程）。 */
    public static void startBackgroundTasks(Main bot) {
        // 启动自检（5.1）：必需字段 + OneBot WS URL + AI API Key
        preflightCheck();

        // 防刷检测
        bot.spamDetector = new SpamDetector(bot);
        logger.info("SpamDetector 初始化完成");

        // 糖果熊知识种子
        bot.keywordKnowledgeService.seedCandyBearKnowledge();

        // 人生引擎
        CandyBearLifeRepository lifeRepo = new CandyBearLifeRepository(DatabaseConfig.getDataSource());
        CandyBearScheduleRepository scheduleRepo = new CandyBearScheduleRepository(DatabaseConfig.getDataSource());
        CandyBearLifeEngine lifeEngine = new CandyBearLifeEngine(lifeRepo, scheduleRepo, bot.baiLianService);
        bot.baiLianService.setLifeEngine(lifeEngine);
        lifeEngine.onStartup();
        logger.info("糖果熊人生引擎已启动（四层架构：章节->周记->日记->工具查询 + LifeState + 日程表）");

        // 今日首次部署：全员职业重抽（脉系+战力每日随机）
        DailyProfessionHandler.rerollAllProfessions();

        startLifeEngineThread(bot, lifeEngine);
        startPortraitService(bot);
        startReminderService(bot);
        startEventChecker(bot);
        startRecurringScheduler(bot);
        startErrorMonitor(bot);
        startDashboard(bot);
    }

    /**
     * 启动前快速检查：缺关键配置直接拒绝启动。
     * 比启动后才发现 "BAILIAN_API_KEY 缺失" 提前暴露问题（5.1 启动自检）。
     */
    private static void preflightCheck() {
        String apiKey = BotConfig.getBaiLianApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("启动失败：BAILIAN_API_KEY 未配置（检查 application.properties）");
        }
        String wsUrl = BotConfig.getWsUrl();
        if (wsUrl == null || wsUrl.isBlank()) {
            throw new IllegalStateException("启动失败：NAPCAT_WS_URL 未配置");
        }
        // 数据库连接 + 迁移在 DatabaseConfig.initConnectionPool 内部已检查
        // Dashboard 鉴权在 WebDashboardListener.start() 内部已检查
        logger.info("✅ 启动自检通过（BAILIAN_API_KEY / WS_URL 已配置）");
    }

    private static void startDashboard(Main bot) {
        WebDashboardListener dashboard = new WebDashboardListener();
        if (bot.conversationExecutor != null) {
            dashboard.setExecutorMetricsProvider(() -> bot.conversationExecutor.getMetrics());
        }
        // 健康状态注入（5.2）
        dashboard.setHealthProvider(() -> bot.getHealth());
        dashboard.start();
        if (bot.conversationRuntime != null) {
            bot.conversationRuntime.addListener(dashboard);
        }
    }

    private static void startLifeEngineThread(Main bot, CandyBearLifeEngine lifeEngine) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(millisUntilNext3AM());
                    lifeEngine.dailyTick();
                    logger.info("人生引擎 tick 完成");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("人生引擎 tick 失败", e);
                }
            }
        }, "CandyBearLife-Thread");
        t.setDaemon(true);
        t.start();
    }

    private static void startPortraitService(Main bot) {
        bot.portraitService = new UserPortraitService(bot.baiLianService, new MessageRepository());
        bot.portraitService.runUpdateTask();
        logger.info("用户画像首次更新完成");

        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10 * 60 * 1000);
                    bot.portraitService.runUpdateTask();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("用户画像更新任务异常", e);
                }
            }
        }, "UserPortrait-Update-Thread");
        t.setDaemon(true);
        t.start();
        logger.info("用户画像系统已启动");
    }

    private static void startReminderService(Main bot) {
        ReminderService reminderService = ReminderService.getInstance();
        reminderService.setBotInstance(bot);
        reminderService.setEnabled(true);
        logger.info("私聊提醒服务已初始化");
    }

    private static void startEventChecker(Main bot) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10 * 60 * 1000);
                    List<LongTermMemory> dueEvents = bot.longTermMemoryRepo.findDueEvents();
                    for (LongTermMemory event : dueEvents) {
                        try {
                            String prompt = "你之前记下了一个定时事件：\"" + event.getContent()
                                    + "\"\n涉及用户：" + event.getUserId()
                                    + "\n现在时间到了，请自然地提醒或祝福。";
                            Runnable trigger = () -> executeDueEvent(
                                    bot.longTermMemoryRepo,
                                    event,
                                    () -> bot.baiLianService.generate(
                                            "event_" + event.getId(),
                                            event.getUserId(),
                                            prompt,
                                            event.getGroupId(),
                                            "糖果熊"),
                                    bot::sendGroupReply,
                                    bot::sendPrivateReply);
                            if (bot.conversationExecutor != null) {
                                bot.conversationExecutor.execute(event.getGroupId(), trigger);
                            } else {
                                trigger.run();
                            }
                        } catch (Exception e) {
                            logger.error("定时事件触发失败 id={}: {}", event.getId(), e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("定时事件检查异常", e);
                }
            }
        }, "EventChecker-Thread");
        t.setDaemon(true);
        t.start();
        logger.info("定时事件检查器已启动");
    }

    private static void startRecurringScheduler(Main bot) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60 * 1000);
                    bot.recurringTaskRepo.expireOldTasks();
                    List<RecurringTask> dueTasks = bot.recurringTaskRepo.findDueTasks();
                    for (RecurringTask task : dueTasks) {
                        try {
                            logger.info("周期任务触发: {} (id={})", task.getTaskName(), task.getId());
                            String sessionId = "recurring_" + task.getId() + "_" + System.currentTimeMillis();
                            Runnable fire = () -> ScheduleExecutor.executeDueTask(
                                    bot.recurringTaskRepo,
                                    task,
                                    () -> bot.baiLianService.generate(
                                            sessionId,
                                            task.getUserId(),
                                            task.getTriggerPrompt(),
                                            task.getGroupId(),
                                            "糖果熊"),
                                    bot::sendGroupReply,
                                    bot::sendPrivateReply,
                                    () -> Main.computeNextFireFromCron(task.getCronExpr()));
                            if (bot.conversationExecutor != null) {
                                bot.conversationExecutor.execute(task.getGroupId(), fire);
                            } else {
                                fire.run();
                            }
                        } catch (Exception e) {
                            logger.error("周期任务执行失败 id={}: {}", task.getId(), e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("周期任务调度异常", e);
                }
            }
        }, "RecurringTask-Scheduler");
        t.setDaemon(true);
        t.start();
        logger.info("周期任务调度器已启动");
    }

    private static void startErrorMonitor(Main bot) {
        bot.errorMonitorService.setBotInstance(bot);
        bot.errorMonitorService.start();
        logger.info("异常自动监控已启动");
    }

    private static long millisUntilNext3AM() {
        return Main.millisUntilNext3AM();
    }
}
