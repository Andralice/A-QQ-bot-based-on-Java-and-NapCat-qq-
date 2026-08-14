package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 火山引擎 - 豆包语音合成 2.0（单向流式 HTTP）引擎。
 *
 * <p>接入豆包语音合成大模型 2.0 的流式接口，支持：
 * <ul>
 *   <li>声音复刻 2.0 训练得到的 voice_type（spekaer=S_ZSLtoFZb2 等）</li>
 *   <li>语音指令 context_texts（instruct），让 LLM 根据上下文生成撒娇/吐槽/安慰等语气</li>
 *   <li>mp3 / pcm / wav / ogg_opus 多种格式</li>
 * </ul>
 *
 * <p>API 文档：https://www.volcengine.com/docs/6561/2528925
 *
 * <p>调用流程：HTTP POST → https://openspeech.bytedance.com/api/v3/tts/unidirectional
 * 响应是 Chunked JSON 流，逐行解析 base64 音频块，code=20000000 表示流结束。
 */
public class VolcTtsEngine {

    private static final Logger logger = LoggerFactory.getLogger(VolcTtsEngine.class);
    private static final String TTS_ENDPOINT = "https://openspeech.bytedance.com/api/v3/tts/unidirectional";

    /** 声音复刻 2.0 训练得到的 voice_type 对应的 resource_id。 */
    private static final String RESOURCE_ID_CLONE_2 = "seed-icl-2.0";

    /** 豆包语音合成大模型 2.0 自带音色对应的 resource_id。 */
    private static final String RESOURCE_ID_TTS_2 = "seed-tts-2.0";

    private static final int MAX_TEXT_LEN = 300;

    private final String apiKey;
    private final String voiceType;
    private final String resourceId;
    private final int timeoutMs;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VolcTtsEngine() {
        this.apiKey = BotConfig.getVolcTtsAccessToken(); // access_token 直接当 X-Api-Key 用
        this.voiceType = BotConfig.getVolcTtsVoiceType();
        this.resourceId = BotConfig.getVolcTtsResourceId();
        this.timeoutMs = BotConfig.getVolcTtsTimeoutMs();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(BotConfig.getHttpConnectTimeoutMs()))
                .build();
        logger.info("🔥 豆包语音合成 2.0 初始化: voiceType={}, resourceId={}, timeout={}ms",
                voiceType, resourceId, timeoutMs);
    }

    /** 检查是否配置完整可用 */
    public boolean isReady() {
        return apiKey != null && !apiKey.isBlank()
                && voiceType != null && !voiceType.isBlank();
    }

    /**
     * 合成语音（无 instruct）。返回 MP3 字节数组；失败返回 null。
     */
    public byte[] synthesize(String text) {
        return synthesize(text, null);
    }

    /**
     * 合成语音（支持 instruct）。
     *
     * @param text    待合成文本
     * @param instruct 语音指令（可选）。如"撒娇地"、"委屈地"、"用沙哑且颤抖的语气"。
     *                 仅当 speaker 是豆包语音合成 2.0 自带音色时官方支持；
     *                 复刻音色传 context_texts 大概率会被服务端拒绝，失败时由调用方 fallback。
     * @return MP3 字节数组；失败返回 null。
     */
    public byte[] synthesize(String text, String instruct) {
        if (text == null || text.isBlank()) {
            logger.warn("VolcTTS text is empty");
            return null;
        }
        if (text.length() > MAX_TEXT_LEN) {
            text = text.substring(0, MAX_TEXT_LEN);
        }
        try {
            Map<String, Object> body = buildRequestBody(text, instruct);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .header("X-Api-Resource-Id", resourceId)
                    .header("X-Api-Request-Id", UUID.randomUUID().toString())
                    .header("X-Control-Require-Usage-Tokens-Return", "*")
                    .header("User-Agent", "CandyBearBot/1.0")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            byte[] respBody = resp.body();

            if (status != 200) {
                String errBody = new String(respBody, StandardCharsets.UTF_8);
                logger.warn("VolcTTS HTTP {}: {}", status,
                        errBody.substring(0, Math.min(300, errBody.length())));
                return null;
            }

            // 成功：body 是 Chunked JSON 流（每行一个 JSON object）
            String bodyStr = new String(respBody, StandardCharsets.UTF_8);
            byte[] audio = parseStreamBody(bodyStr);
            if (audio != null && audio.length > 0) {
                logger.info("VolcTTS 合成成功: {} bytes (text='{}', instruct='{}')",
                        audio.length, text, instruct == null ? "" : instruct);
                return audio;
            }
            logger.warn("VolcTTS 响应解析后音频为空: text='{}'", text);
            return null;
        } catch (Exception e) {
            logger.warn("VolcTTS 异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析流式响应：按行 JSON，每行一个对象。data 字段是 base64 音频，code=20000000 表示结束。
     */
    private byte[] parseStreamBody(String body) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            String[] lines = body.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                JsonNode node = objectMapper.readTree(trimmed);
                int code = node.path("code").asInt(0);
                String message = node.path("message").asText("");

                if (code != 0 && code != 20000000) {
                    logger.warn("VolcTTS 错误: code={}, message={}", code, message);
                    return null;
                }
                if (code == 20000000) {
                    // 正常结束
                    break;
                }
                String data = node.path("data").asText("");
                if (!data.isEmpty()) {
                    byte[] chunk = Base64.getDecoder().decode(data);
                    baos.write(chunk);
                }
            }
            return baos.toByteArray();
        } catch (Exception e) {
            logger.warn("VolcTTS 解析响应失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildRequestBody(String text, String instruct) {
        // audio_params
        Map<String, Object> audioParams = new LinkedHashMap<>();
        audioParams.put("format", "mp3");
        audioParams.put("sample_rate", 24000);
        audioParams.put("speech_rate", 0);
        audioParams.put("loudness_rate", 0);
        audioParams.put("disable_markdown_filter", true);
        audioParams.put("disable_emoji_filter", true);

        // req_params
        Map<String, Object> reqParams = new LinkedHashMap<>();
        reqParams.put("text", text);
        reqParams.put("speaker", voiceType);
        reqParams.put("audio_params", audioParams);

        // 语音指令（instruct）
        if (instruct != null && !instruct.isBlank()) {
            List<String> contextTexts = new ArrayList<>();
            contextTexts.add(instruct);
            reqParams.put("context_texts", contextTexts);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("req_params", reqParams);
        return root;
    }
}
