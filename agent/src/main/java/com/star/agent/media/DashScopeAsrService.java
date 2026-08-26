package com.star.agent.media;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.infra.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

// 语音转文字真实实现（paraformer-realtime-v2，DashScope 官方 SDK）：
// 16k WAV → 临时文件 → recognition.call → 解析 sentences 拼文本
// 失败返回 null（VoiceTranscriber 合同：降级信号，调用方换话术，不崩）
@Component
public class DashScopeAsrService implements VoiceTranscriber {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeAsrService.class);
    private static final String MODEL = "paraformer-realtime-v2";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final Recognition recognition;

    @Autowired
    public DashScopeAsrService(AppConfig config) {
        this(config.dashScopeApiKey(), new Recognition());
    }

    DashScopeAsrService(String apiKey, Recognition recognition) {
        this.apiKey = apiKey;
        this.recognition = recognition;
    }

    @Override
    public String transcribe(byte[] wavBytes) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("voice_", ".wav");
            Files.write(tmp, wavBytes);
            RecognitionParam param = RecognitionParam.builder()
                    .model(MODEL)
                    .apiKey(apiKey)
                    .build();
            String result = recognition.call(param, tmp.toFile());
            String text = parseSentences(result);
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            logger.warn("语音识别失败: {}", e.getMessage());
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // 临时文件清理失败无碍
                }
            }
        }
    }

    // 从 ASR 响应 JSON 提取识别文本（sentences[].text 逗号拼接；纯函数，单测直接喂 JSON）
    static String parseSentences(String json) {
        try {
            StringBuilder sb = new StringBuilder();
            JsonNode root = new ObjectMapper().readTree(json);
            for (JsonNode s : root.path("sentences")) {
                String t = s.path("text").asText("");
                if (!t.isBlank()) {
                    if (sb.length() > 0) sb.append("，");
                    sb.append(t);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}