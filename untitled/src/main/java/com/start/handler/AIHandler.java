package com.start.handler;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.service.BaiLianService;
import com.start.service.BotMoodService;
import com.start.service.ConversationEvent;
import com.start.service.ConversationInterpreter;
import com.start.service.ConversationManager;
import com.start.service.ConversationMetrics;
import com.start.service.ConversationState;
import com.start.service.GenerationResult;
import com.start.service.GroupSerialExecutor;
import com.start.service.LinkPreviewService;
import com.start.memory.MemoryInterpreter;
import com.start.memory.MemoryRecall;
import com.start.model.DecisionContext;
import com.start.model.DecisionTrace;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;
import com.start.runtime.ConversationRuntime;
import com.start.runtime.conversation.ConversationRuntimeConfig;
import com.start.runtime.conversation.ConversationSession;
import com.start.runtime.RuntimeEvent;
import com.start.runtime.trace.WebDashboardListener;
import com.start.util.MessageUtil;
import com.start.vision.ImageUtils;
import com.hankcs.hanlp.HanLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.start.util.MessageUtil.extractAts;

/**
 * AIHandler  ai模块入口
 */
public class AIHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(AIHandler.class);
    private static final long MAX_QUEUE_MS = 30_000; // 排队超过30秒则丢弃

    private final BaiLianService aiService;
    private final BotMoodService moodService;
    private final GroupSerialExecutor groupExecutor;
    private final ConversationManager conversationManager;
    private final ConversationInterpreter interpreter;
    private final ConversationRuntime runtime;
    private final ConversationRuntimeConfig config;
    /** 长期记忆仓库：rate_limited 时的快速记忆查询用（不调 LLM） */
    private final LongTermMemoryRepository memoryRepo = new LongTermMemoryRepository(DatabaseConfig.getDataSource());
    private final MemoryInterpreter memoryInterpreter = new MemoryInterpreter();
    private final Random random = new Random();
    private final ConcurrentHashMap<String, Long> lastReactionTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastGroupReplyTime = new ConcurrentHashMap<>();
    private static final Logger DECISION_LOGGER = LoggerFactory.getLogger("com.start.decision");

    /** 记忆查询意图关键词：rate_limited 时如果用户明显在问"你还记得..."，不沉默，先查记忆给短回复 */
    private static final String[] MEMORY_QUERY_KEYWORDS = {
            "记得", "还记得", "之前说过", "之前说", "你说过", "你说",
            "你之前", "你刚说", "我说过", "我说", "你提过", "上次",
            "你说过吧", "我说过吧"
    };

    public AIHandler(BaiLianService aiService, GroupSerialExecutor groupExecutor, ConversationManager conversationManager,
                     ConversationInterpreter interpreter, ConversationRuntime runtime, ConversationRuntimeConfig config) {
        this.aiService = aiService;
        this.moodService = aiService.getMoodService();
        this.groupExecutor = groupExecutor;
        this.conversationManager = conversationManager;
        this.interpreter = interpreter;
        this.runtime = runtime;
        this.config = config;
    }

    @Override
    public boolean match(JsonNode msg) {
        String messageType = msg.path("message_type").asText();
        if ("private".equals(messageType)) {
            String raw = msg.path("raw_message").asText().trim();
            if (raw.isEmpty()) return false;
            if (raw.startsWith("!") &&
                    !raw.startsWith("!ai ") &&
                    !raw.startsWith("！ai ") &&
                    !raw.startsWith("#ai ")) {
                return false;
            }
            return true;
        } else if ("group".equals(messageType)) {
            return true;
        }
        return false;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        long selfId = msg.path("self_id").asLong();
        long userId = msg.path("user_id").asLong();
        String messageType = msg.path("message_type").asText();
        long groupId = msg.path("group_id").asLong();
        JsonNode messageArray = msg.path("message");
        List<Long> ats = extractAts(messageArray);
        String nickname = msg.path("sender").path("nickname").asText();
        if (userId == selfId) return;

        String plainText = MessageUtil.extractPlainText(msg.path("message")).trim();
        String rawMessage = msg.path("raw_message").asText();
        String senderNick = msg.path("sender").path("card").asText();
        if (senderNick.isEmpty()) {
            senderNick = msg.path("sender").path("nickname").asText();
        }

        // 提取图片信息（只在 WebSocket 线程提取 URL，下载在 executor 内完成）
        List<Map<String, String>> imageInfos = MessageUtil.extractImages(msg.path("message"));

        // 提取文件信息 → 存入缓存，由副 AI 处理，主 AI 通过 query_file 工具主动获取
        List<Map<String, String>> fileInfos = MessageUtil.extractFiles(msg.path("message"));
        if (!fileInfos.isEmpty()) {
            String fileKey = "group".equals(messageType)
                    ? "group_" + groupId + "_" + userId
                    : "private_" + userId;
            aiService.addPendingFiles(fileKey, fileInfos);
        }

        // 提取链接（分享卡片 + 纯文本 URL）
        List<String> linksToFetch = new ArrayList<>();
        for (Map<String, String> share : MessageUtil.extractShares(msg.path("message"))) {
            String url = share.get("url");
            if (url != null && !url.isEmpty()) {
                linksToFetch.add(url);
            }
        }
        linksToFetch.addAll(MessageUtil.extractUrls(rawMessage));

        // 私聊
        if ("private".equals(messageType)) {
            handlePrivateMessage(bot, msg, userId, rawMessage, plainText, nickname, imageInfos, linksToFetch);
            return;
        }

        // 群聊：先记录原始消息到上下文（WebSocket 线程，无竞争）
        aiService.recordPublicGroupMessage(
                String.valueOf(groupId),
                String.valueOf(userId),
                senderNick,
                plainText
        );

        String gid = String.valueOf(groupId);
        String uid = String.valueOf(userId);

        // 群聊情绪追踪
        if (moodService != null) {
            moodService.recordGroupActivity(gid);
            if (ats.contains(BotConfig.getBotQq())) {
                moodService.onMentioned(gid);
            }
        }

        // 缓冲消息到 ConversationState（WebSocket 线程）
        ConversationState conv = conversationManager.getOrCreate(gid, uid);
        conv.addMessage(plainText);
        if (!imageInfos.isEmpty()) {
            for (Map<String, String> img : imageInfos) {
                conv.addImageInfo(img.get("url"), img.get("file"));
            }
        }
        for (String link : linksToFetch) {
            conv.addLink(link);
        }
        Long replyId = MessageUtil.extractReplyId(msg.path("message"));
        if (replyId != null) conv.setReplyToMessageId(replyId);
        conv.incrementRevision();

        // Thread 更新：每条群消息都维护群级 Thread 状态
        // 提交到 groupExecutor，保证与 AI 生成串行、不阻塞 WebSocket 线程
        groupExecutor.execute(gid, () -> {
            aiService.processThreadMessage(gid, uid, plainText);
        });

        // 明确触发（#ai / !ai / @）
        if (isExplicitTrigger(msg, rawMessage)) {
            aiService.cancelPendingAwait(gid, uid);
            String strippedPrompt = extractPrompt(rawMessage, plainText);
            if (isClearCommand(strippedPrompt)) {
                aiService.clearContext("group_" + groupId + "_" + uid);
                bot.sendReply(msg, "已清除我们的聊天记忆！");
                conversationManager.remove(gid, uid);
                logDecision(gid, uid, "MENTION", "REPLY", "clear", 0, 0, 0);
                return;
            }
            if (strippedPrompt.isEmpty() && imageInfos.isEmpty()) {
                bot.sendReply(msg, "问点什么吧～");
                conversationManager.remove(gid, uid);
                return;
            }
            long t0 = System.currentTimeMillis();
            runGroupConversation(bot, groupId, gid, uid, nickname, ats, false, t0, ConversationEvent.MENTION);
            return;
        }

        // 记录群聊节奏
        runtime.fire(new RuntimeEvent.MessageReceived(gid, uid, plainText));

        // ConversationInterpreter 识别事件类型
        ConversationInterpreter.InterpretResult result = interpreter.interpret(
                gid, uid, senderNick, plainText, ats);

        // 纯图片消息的追问处理
        boolean imageFollowUp = !imageInfos.isEmpty() && result.isNothing()
                && aiService.isWithinFollowUpWindow(gid, uid);

        if (result.isNothing() && !imageFollowUp) {
            logDecision(gid, uid, "NOTHING", "SILENT", "no_trigger", 0, 0, 0);
            return;
        }

        long now = System.currentTimeMillis();

        // 速率限制（PASSIVE_TRIGGER 可以绕过）
        boolean rateLimited = false;
        if (result.event() != ConversationEvent.PASSIVE_TRIGGER && !imageFollowUp) {
            rateLimited = !aiService.canReact(gid);
        }

        // 群级冷却
        long groupCooldown = config.groupReplyCooldown().toMillis();
        Long lastGroupReply = lastGroupReplyTime.get(gid);
        if (lastGroupReply != null && now - lastGroupReply < groupCooldown) {
            rateLimited = true;
        }

        // 用户冷却
        long userCooldown = config.userReplyCooldown().toMillis();
        String userKey = gid + "_" + userId;
        Long last = lastReactionTime.get(userKey);
        if (last != null && now - last < userCooldown) {
            rateLimited = true;
        }

        if (rateLimited) {
            // 记忆查询快路径：rate_limited 但用户明显在问"你还记得..."
            // 不沉默，DB 查一下就发短回复——成本极低，避免"我刚才走神"答错体验。
            // 注意：只更新 groupReplyTime 不更新 userReactionTime，避免被刷屏
            String shortReply = tryFastMemoryReply(uid, gid, plainText);
            if (shortReply != null) {
                lastGroupReplyTime.put(gid, now);
                // 第二阶段 2.2：传 sessionId 让 AI 回复与用户消息同 session
                String fastSessionId = com.start.service.SessionId.groupConversation(gid, uid);
                sendSplitGroupReplies(bot, groupId, shortReply, fastSessionId);
                // 留痕：让后续 Interpreter 能识别到这条 ai_reply
                // （否则 fast path 回复后的 3 分钟内，AI_COMMENTED 事件识别不到）
                logDecision(gid, uid, result.event().name(), "REPLY", "memory_recall_fast", 0, 0, 0);
                return;
            }
            logDecision(gid, uid, result.event().name(), "SILENT", "rate_limited", 0, 0, 0);
            return;
        }

        lastReactionTime.put(userKey, now);

        if (result.isDirect()) {
            // 被动触发直接回复，不走 AI
            lastGroupReplyTime.put(gid, now);
            aiService.recordReaction(gid);
            sendSplitGroupReplies(bot, groupId, result.directReply(),
                    com.start.service.SessionId.groupConversation(gid, uid));
            conversationManager.remove(gid, uid);
            logDecision(gid, uid, result.event().name(), "REPLY", "direct", 0, 0, now - System.currentTimeMillis());
            return;
        }

        // 需要 AI 生成：只有 PROBABILISTIC 允许沉默
        lastGroupReplyTime.put(gid, now);
        aiService.recordReaction(gid);
        boolean allowSilence = result.event().allowsSilence();
        long startMs = System.currentTimeMillis();
        runGroupConversation(bot, groupId, gid, uid, nickname, ats, allowSilence, startMs, result.event());
    }

    private void logDecision(String gid, String uid, String eventType, String decision,
                             String reason, int toolCalls, int tokensUsed, long latencyMs) {
        DecisionTrace trace = new DecisionTrace(System.currentTimeMillis(), gid, uid, eventType,
                decision, reason, toolCalls, tokensUsed, latencyMs, 0, 0, false);
        DECISION_LOGGER.info(trace.toLogLine());
        WebDashboardListener.recordDecision(gid, uid, eventType, decision, reason,
                toolCalls, tokensUsed, latencyMs);
    }

    private static final HttpClient auditHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5000))
            .build();

    /** 从 ConversationState 读取累积消息，生成回复并发送。在 executor 线程中执行。 */
    private void runGroupConversation(Main bot, long groupId, String gid, String userId, String nickname,
                                       List<Long> ats, boolean allowSilence, long startMs, ConversationEvent event) {
        groupExecutor.execute(gid, () -> {
            ConversationState state = conversationManager.get(gid, userId);
            if (state == null || !state.hasContent()) return;

            long startRevision = state.getMessageRevision();
            int startMsgCount = state.getPendingMessages().size();
            state.incrementGeneration();
            GenerationResult genResult = null;
            DecisionContext dc = null;

            // 图片 + 链接上下文在一次对话中不变，提到循环外避免重复下载/描述
            String replyContext = "";
            Long replyToMsgId = state.getReplyToMessageId();
            if (replyToMsgId != null) {
                replyContext = fetchReplyContext(replyToMsgId, bot);
            }
            List<Map<String, String>> imageInfoMaps = new ArrayList<>();
            for (ConversationState.ImageInfo img : state.getImageInfos()) {
                Map<String, String> m = new HashMap<>();
                m.put("url", img.url());
                m.put("file", img.file());
                imageInfoMaps.add(m);
            }
            List<String> imageDataUris = downloadImages(imageInfoMaps);
            String imageDesc = aiService.describeImages(imageDataUris);
            String linkContext = buildLinkContext(state.getLinksToFetch());

            for (int attempt = 0; attempt <= config.maxRegenerate(); attempt++) {
                if (attempt > 0) {
                    startRevision = state.getMessageRevision();
                    startMsgCount = state.getPendingMessages().size();
                    state.incrementGeneration();
                    state.incrementRegenerateCount();
                    logger.debug("Conversation regenerate gen={} rev={} count={} gid={} uid={}",
                            state.getGeneration(), state.getMessageRevision(), state.getRegenerateCount(), gid, userId);
                }

                // 从 buffer 读取累积的消息文本（每次循环可能变化）
                String mergedText = state.getMergedText();
                String prompt = replyContext.isEmpty() ? mergedText : replyContext + mergedText;
                if (prompt.isEmpty() && !state.getImageInfos().isEmpty()) {
                    prompt = "看一下这张图片";
                }
                if (!imageDesc.isEmpty()) prompt = prompt + "\n\n" + imageDesc;
                if (!linkContext.isEmpty()) prompt = prompt + "\n\n" + linkContext;

                // 冻结决策上下文（Replay 用）
                ConversationMetrics convMetrics = aiService.getConversationMetrics();
                ConversationMetrics.Snapshot snap = convMetrics != null
                        ? convMetrics.getSnapshot(gid) : ConversationMetrics.Snapshot.EMPTY;
                dc = DecisionContext.of(
                        event, state.getGeneration(), state.getMessageRevision(),
                        allowSilence, snap.messagesLast30s(), snap.aiMessagesLast5m());

                ConversationSession session = ConversationSession.of(gid, userId, nickname)
                        .userPrompt(prompt)
                        .atUserIds(ats)
                        .allowSilence(allowSilence)
                        .generation(state.getGeneration())
                        .revision(state.getMessageRevision())
                        .event(event)
                        .metricsSnapshot(snap)
                        .startMs(startMs)
                        .build();

                aiService.setSuppressSessionWrite(true);
                try {
                    genResult = aiService.generate(session);
                } finally {
                    aiService.setSuppressSessionWrite(false);
                }

                String reply = genResult.reply();

                // 模型沉默 — 直接退出
                if (genResult.isSilent()) {
                    long elapsed = System.currentTimeMillis() - startMs;
                    runtime.fire(new RuntimeEvent.CommitFinished(gid, userId, genResult, elapsed, dc));
                    conversationManager.remove(gid, userId);
                    return;
                }

                // 检查 LLM 调用期间是否有新消息到达
                if (state.getMessageRevision() == startRevision) {
                    break;
                }
                if (attempt >= config.maxRegenerate()) {
                    logger.debug("Max regenerate reached, sending anyway gid={} uid={}", gid, userId);
                    break;
                }

                // 有新消息 → audit 模型判断是否合并再生
                String oldText = getMergedTextRange(state.getPendingMessages(), 0, startMsgCount);
                String newText = getMergedTextRange(state.getPendingMessages(), startMsgCount, state.getPendingMessages().size());
                String auditResult = classifyMessageRelation(oldText, newText, reply);
                logger.debug("Audit classify: {} old=[{}] new=[{}] gid={} uid={}", auditResult, oldText, newText, gid, userId);

                if ("C".equals(auditResult)) {
                    continue;
                }
                break;
            }

            String reply = genResult != null ? genResult.reply() : "";
            long elapsed = System.currentTimeMillis() - startMs;

            String groupSessionId = com.start.service.SessionId.groupConversation(gid, String.valueOf(userId));
            if (reply != null && !reply.trim().isEmpty() && !reply.equals("抱歉，刚才走神了...") && !reply.equals("嗯...再问一次吧")) {
                sendSplitGroupReplies(bot, groupId, reply, groupSessionId);
                aiService.commitGeneration(groupSessionId, String.valueOf(userId),
                        state.getMergedText(), reply, gid);
                if (moodService != null) moodService.onBotSpeak(gid);
                runtime.fire(new RuntimeEvent.CommitFinished(gid, userId, genResult, elapsed, dc));
            } else {
                bot.sendGroupReply(groupId, "刚刚走神了，再说一遍？", groupSessionId);
                logDecision(gid, userId, allowSilence ? "PROBABILISTIC" : "OTHER",
                        "REPLY", "fallback", genResult != null ? genResult.toolCalls() : 0, 0, elapsed);
            }

            conversationManager.remove(gid, userId);
        });
    }

    private static String getMergedTextRange(List<ConversationState.MessageEntry> msgs, int start, int end) {
        int from = Math.max(0, start);
        int to = Math.min(msgs.size(), end);
        if (from >= to) return "";
        return msgs.subList(from, to).stream()
                .map(ConversationState.MessageEntry::text)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /** 调用 audit 模型（便宜）判断后半部分消息的性质。返回 S / C / N */
    private String classifyMessageRelation(String oldText, String newText, String generatedReply) {
        if (newText.isEmpty()) return "S";
        try {
            String prompt = "用户连续发了消息，AI已为前半部分生成了回复，但后半部分在回复生成后才到达。\n"
                    + "\n【前半部分消息】\n" + (oldText.isEmpty() ? "（空）" : oldText)
                    + "\n\n【后半部分新增消息】\n" + newText
                    + "\n\n【AI已生成的回复】\n" + generatedReply
                    + "\n\n请判断后半部分消息的性质，只回复一个大写字母：\n"
                    + "S - 后半部分是前半部分的重复/同义/废话，不需要额外回复\n"
                    + "C - 后半部分是前半部分的补充/修正/延续，应该合并后重新生成回复\n"
                    + "N - 后半部分是新的独立话题，与前半部分无关\n\n只回复一个字母：";

            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("model", BotConfig.getAuditModel());
            ArrayNode msgs = body.putArray("messages");
            ObjectNode msg = msgs.addObject();
            msg.put("role", "user");
            msg.put("content", prompt);
            body.put("max_tokens", 10);
            body.put("temperature", 0.0);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BotConfig.getAuditBaseUrl()))
                    .header("Authorization", "Bearer " + BotConfig.getAuditApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(BotConfig.getAuditTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> resp = auditHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                String content = json.path("choices").get(0).path("message").path("content").asText("S").trim();
                if (content.startsWith("S")) return "S";
                if (content.startsWith("C")) return "C";
                if (content.startsWith("N")) return "N";
                return "S"; // 默认不再生
            }
            logger.warn("Audit classify HTTP error: {}", resp.statusCode());
        } catch (Exception e) {
            logger.warn("Audit classify failed: {}", e.getMessage());
        }
        return "S"; // 出错时保守处理：不重新生成
    }

    /** 获取引用消息的文本上下文 */
    private String fetchReplyContext(Long replyMsgId, Main bot) {
        try {
            var params = new ObjectNode(JsonNodeFactory.instance);
            params.put("message_id", replyMsgId);
            var future = bot.callOneBotApi("get_msg", params);
            var resp = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (resp != null && resp.has("data")) {
                String repliedText = resp.path("data").path("raw_message").asText();
                if (!repliedText.isEmpty()) {
                    return "（对方正在回复这条消息：\"" + repliedText + "\"）";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String buildReplyContext(JsonNode msg, Main bot) {
        Long replyId = MessageUtil.extractReplyId(msg.path("message"));
        if (replyId == null) return "";
        try {
            var params = new ObjectNode(JsonNodeFactory.instance);
            params.put("message_id", replyId);
            var future = bot.callOneBotApi("get_msg", params);
            var resp = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (resp != null && resp.has("data")) {
                String repliedText = resp.path("data").path("raw_message").asText();
                if (!repliedText.isEmpty()) {
                    return "（对方正在回复这条消息：\"" + repliedText + "\"）";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void handlePrivateMessage(Main bot, JsonNode msg, long userId, String rawMessage, String plainText, String nickname, List<Map<String, String>> imageInfos, List<String> linksToFetch) {
        String prompt = buildReplyContext(msg, bot) + extractPrompt(rawMessage, plainText);
        String sessionId = "private_" + userId;

        if (isClearCommand(prompt)) {
            aiService.clearContext(sessionId);
            bot.sendReply(msg, "已清除我们的聊天记忆！");
            return;
        }

        if (prompt.isEmpty() && imageInfos.isEmpty()) {
            bot.sendReply(msg, "想聊什么？直接说就好～");
            return;
        }
        if (prompt.isEmpty()) prompt = "看一下这张图片";

        replyWithAI(bot, msg, sessionId, String.valueOf(userId), prompt, null, nickname, Collections.emptyList(), imageInfos, linksToFetch);
    }

    private boolean isExplicitTrigger(JsonNode msg, String rawMessage) {
        return rawMessage.startsWith("#ai ") ||
                rawMessage.startsWith("!ai ") ||
                rawMessage.startsWith("！ai ") ||
                MessageUtil.isAt(msg.path("message"), BotConfig.getBotQq());
    }

    private String extractPrompt(String rawMessage, String plainText) {
        if (rawMessage.startsWith("#ai ")) return rawMessage.substring(4).trim();
        if (rawMessage.startsWith("!ai ")) return rawMessage.substring(4).trim();
        if (rawMessage.startsWith("！ai ")) return rawMessage.substring(5).trim();
        return plainText;
    }

    private boolean isClearCommand(String prompt) {
        return "#clear".equals(prompt) || "!clear".equals(prompt) || "！clear".equals(prompt);
    }


    private void replyWithAI(Main bot, JsonNode originalMsg, String sessionId, String userId, String prompt, String groupId, String nickname, List<Long> atUserIds, List<Map<String, String>> imageInfos, List<String> linksToFetch) {
        groupExecutor.execute(sessionId, () -> {
            List<String> imageDataUris = downloadImages(imageInfos);
            String imageDesc = aiService.describeImages(imageDataUris);
            String linkContext = buildLinkContext(linksToFetch);
            String fullPrompt = prompt;
            if (!imageDesc.isEmpty()) fullPrompt = fullPrompt + "\n\n" + imageDesc;
            if (!linkContext.isEmpty()) fullPrompt = fullPrompt + "\n\n" + linkContext;

            // 存储图片数据到 DB
            if (!imageDesc.isEmpty()) {
                aiService.setPendingImageData(buildImageDataJson(imageInfos, imageDesc));
            }
            String reply = aiService.generate(sessionId, userId, fullPrompt, groupId, nickname, atUserIds);

            if (reply == null || reply.trim().isEmpty()) {
                bot.sendReply(originalMsg, "稍等一下，我在走神...", sessionId);
                return;
            }

            if (groupId != null) {
                long gId = Long.parseLong(groupId);
                // 第二阶段 2.2：传 sessionId 让 AI 回复写入与用户消息同 session
                sendSplitGroupReplies(bot, gId, reply, sessionId);

                String senderNick = originalMsg.path("sender").path("card").asText();
                if (senderNick.isEmpty()) senderNick = originalMsg.path("sender").path("nickname").asText();
                aiService.recordUserInteraction(groupId, userId, reply);
                } else {
                sendSplitPrivateReplies(bot, originalMsg, reply, sessionId);
            }
        });
    }

    /**
     * 将 AI 回复拆分为多条短消息，并逐条发送（带打字延迟）。
     * 第二阶段 2.2：传 sessionId，AI 回复写入与用户消息同一 session。
     */
    private void sendSplitGroupReplies(Main bot, long groupId, String fullReply, String sessionId) {
        List<String> parts = aiService.splitIntoShortMessages(fullReply);
        for (int i = 0; i < parts.size(); i++) {
            String msg = parts.get(i).trim();
            if (msg.isEmpty()) continue;

            int delayMs = (i == 0) ? (random.nextInt(300) + 200) : (random.nextInt(1000) + 500);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            bot.sendGroupReply(groupId, msg, sessionId);
        }
    }

    /** 获取链接预览上下文，失败则返回空 */
    private String buildLinkContext(List<String> linksToFetch) {
        if (linksToFetch == null || linksToFetch.isEmpty()) return "";
        LinkPreviewService lps = new LinkPreviewService();
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(linksToFetch.size(), 3);
        for (int i = 0; i < limit; i++) {
            String preview = lps.fetchPreview(linksToFetch.get(i));
            if (preview != null && !preview.isEmpty()) {
                sb.append(preview).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 下载图片并转为 base64 data URI，失败则跳过 */
    private List<String> downloadImages(List<Map<String, String>> imageInfos) {
        List<String> uris = new ArrayList<>();
        if (imageInfos == null || imageInfos.isEmpty()) return uris;
        int limit = Math.min(imageInfos.size(), 3);
        for (int i = 0; i < limit; i++) {
            String url = imageInfos.get(i).get("url");
            if (url == null || url.isEmpty()) continue;
            String dataUri = ImageUtils.downloadImageAsBase64DataUri(url);
            if (dataUri != null) uris.add(dataUri);
        }
        return uris;
    }

    private String buildImageDataJson(List<Map<String, String>> imageInfos, String imageDesc) {
        if (imageInfos == null || imageInfos.isEmpty() || imageDesc.isEmpty()) return null;
        // 解析 vision 描述中的每条 "图片N内容：xxx"，与 imageInfos 的 URL 配对
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(imageInfos.size(), 3);
        for (int i = 0; i < limit; i++) {
            String url = imageInfos.get(i).get("url");
            if (url == null) url = "";
            // 从 imageDesc 提取对应描述
            String prefix = "图片" + (i + 1) + "内容：";
            int idx = imageDesc.indexOf(prefix);
            String desc = "";
            if (idx >= 0) {
                int start = idx + prefix.length();
                int end = imageDesc.indexOf("\n", start);
                if (end < 0) end = imageDesc.length();
                desc = imageDesc.substring(start, end).trim().replace("\\", "\\\\").replace("\"", "\\\"");
            }
            if (i > 0) sb.append(",");
            sb.append("{\"url\":\"").append(url.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
              .append(",\"desc\":\"").append(desc).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /** 私聊同样拆分，避免一大段砸过去 */
    private void sendSplitPrivateReplies(Main bot, JsonNode originalMsg, String fullReply, String sessionId) {
        List<String> parts = aiService.splitIntoShortMessages(fullReply);
        for (int i = 0; i < parts.size(); i++) {
            String msg = parts.get(i).trim();
            if (msg.isEmpty()) continue;

            int delayMs = (i == 0) ? (random.nextInt(300) + 200) : (random.nextInt(1000) + 500);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            bot.sendReply(originalMsg, msg, sessionId);
        }
    }

    // ===== rate_limited 快速记忆查询（不发 LLM） =====

    /** 判断消息是否含"你还记得..."这种记忆查询意图 */
    private static boolean isMemoryQueryIntent(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        for (String kw : MEMORY_QUERY_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /**
     * rate_limited 快路径：调 DB 查 1 条最相关记忆，命中就生成短回复。
     * 仅查 DB 不调 LLM，QPS 高也无所谓。
     * @return 短回复文本；查不到或非查询类则返回 null（让上层走原沉默逻辑）
     */
    private String tryFastMemoryReply(String uid, String gid, String text) {
        if (!isMemoryQueryIntent(text)) return null;
        try {
            String kw = null;
            try {
                List<String> kws = HanLP.extractKeyword(text, 3);
                if (kws != null && !kws.isEmpty()) kw = kws.get(0);
            } catch (Exception ignored) {}
            java.util.List<LongTermMemory> results = memoryRepo.search(uid, gid, kw, 1, null, null);
            if (results.isEmpty()) return null;
            LongTermMemory m = results.get(0);
            try { memoryRepo.markRecalled(m.getId()); } catch (Exception ignored) {}
            // 用 MemoryInterpreter 翻译成叙事语言
            MemoryRecall r = memoryInterpreter.interpret(m, null);
            StringBuilder sb = new StringBuilder("记得");
            if (r.stabilityHint() != null && !r.stabilityHint().isEmpty()) sb.append(r.stabilityHint());
            sb.append(r.content());
            if (r.ageText() != null && !r.ageText().isEmpty()) sb.append("（").append(r.ageText()).append("）");
            return sb.toString();
        } catch (Exception e) {
            logger.debug("fast memory recall failed: {}", e.getMessage());
            return null;
        }
    }
}
