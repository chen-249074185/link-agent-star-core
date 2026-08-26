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
// 魔数检测格式 → 写临时文件（带正确扩展名）→ recognition.call → 解析 sentences 拼文本
// 参照 our-project VoiceRecognitionService：RecognitionParam 必须带 format/sampleRate（否则 paraformer 拒收/识别差）
// 失败返回 null（VoiceTranscriber 合同：降级信号，调用方换话术，不崩）
@Component
public class DashScopeAsrService implements VoiceTranscriber {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeAsrService.class);
    private static final String MODEL = "paraformer-realtime-v2";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final Recognition recognition;
    private final AudioFormatDetector formatDetector;

    @Autowired
    public DashScopeAsrService(AppConfig config) {
        this(config.dashScopeApiKey(), new Recognition(), new AudioFormatDetector());
    }

    DashScopeAsrService(String apiKey, Recognition recognition, AudioFormatDetector formatDetector) {
        this.apiKey = apiKey;
        this.recognition = recognition;
        this.formatDetector = formatDetector;
    }

    @Override
    public String transcribe(byte[] audioBytes) {
        Path tmp = null;
        try {
            AudioFormat format = formatDetector.detect(audioBytes);
            String ext = switch (format) {
                case WAV -> "wav";
                case MP3 -> "mp3";
                case OGG -> "ogg";
                case AMR -> "amr";
                default -> "silk";   // SILK/未知：paraformer 不认也会尽量按原样传
            };
            tmp = Files.createTempFile("voice_", "." + ext);
            Files.write(tmp, audioBytes);
            // 关键：format/sampleRate 必须声明（参照 our-project，缺了 paraformer 不知道音频格式）
            RecognitionParam param = RecognitionParam.builder()
                    .model(MODEL)
                    .format("wav".equals(ext) ? "wav" : ext)
                    .sampleRate(16000)
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